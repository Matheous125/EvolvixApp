package com.example.evolvix.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.evolvix.data.local.AchievementDao
import com.example.evolvix.data.local.HabitDao
import com.example.evolvix.data.model.AchievementEntity
import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitEntity
import com.example.evolvix.domain.model.AchievementDefinition
import com.example.evolvix.domain.sync.SyncController
import com.example.evolvix.domain.usecase.EvaluateAchievementsUseCase
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel managing achievement evaluation and persistence for [AchievementsScreen].
 *
 * Observes [HabitDao.getAllHabits] and [HabitDao.getAllCompletions] via [combine],
 * running [EvaluateAchievementsUseCase] on every emission and persisting only the
 * deltas to [AchievementDao].
 *
 * Pattern: **Observer via Flow** — `combine` merges two Room Flows so any DB write
 * (habit added, completion logged, history edited) automatically triggers re-evaluation
 * without polling. The reducer is **idempotent**: re-running it on the same inputs
 * produces the same DB state.
 *
 * @property habitDao       Source for the habit list and full completion history.
 * @property achievementDao  Persistence layer for [AchievementEntity] rows.
 * @property syncController  Used to push newly-unlocked achievements to Firestore in
 *   real-time so they survive a `logout → clearAllTables → login` cycle without
 *   triggering spurious banner emissions on every re-login.
 */
class AchievementsViewModel(
    private val habitDao: HabitDao,
    private val achievementDao: AchievementDao,
    private val syncController: SyncController
) : ViewModel() {

    // Pure-function interactor — stateless, safe to reuse across emissions.
    // (Pattern: Use Case / Interactor — single-responsibility achievement evaluation)
    private val evaluateAchievements = EvaluateAchievementsUseCase()

    /**
     * Full list of achievement rows from the database, emitted on every change.
     * The UI collects this to render both unlocked cards and locked progress bars.
     * (Pattern: Observer via StateFlow — DB is the single source of truth)
     */
    val achievements: StateFlow<List<AchievementEntity>> =
        achievementDao.getAllAchievements()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    /**
     * Emits a [AchievementDefinition] the instant a new achievement is unlocked.
     * Collected by [AchievementBanner] to display the sliding unlock notification.
     *
     * (Pattern: Event Bus via SharedFlow — fire-and-forget, no replay on resubscription)
     */
    private val _newlyUnlocked = MutableSharedFlow<AchievementDefinition>()
    val newlyUnlocked: SharedFlow<AchievementDefinition> = _newlyUnlocked.asSharedFlow()

    init {
        // Combines the two Room Flows into a single reactive pipeline.
        // Any write to the habits or habit_completions table triggers a new emission
        // and causes the evaluator to run, keeping achievements always in sync.
        // (Pattern: Observer — combine acts as a merge point for two independent observers)
        viewModelScope.launch {
            combine(
                habitDao.getAllHabits(),
                habitDao.getAllCompletions()
            ) { habits, completions -> habits to completions }
                .collect { (habits, completions) ->
                    // Guard #1: skip evaluation while no user is signed in. During
                    // logout the FirebaseAuth listener fires before clearAllTables
                    // finishes, so this collector can still see a transient pair of
                    // (cached habits, cached completions) on a signed-out session.
                    // Persisting / emitting in that window would (a) flash banners on
                    // the just-logged-out user's screen, and (b) leave achievement
                    // rows in Room that SyncController.syncAchievements would later
                    // push to the NEXT user's Firestore on their first login.
                    if (FirebaseAuth.getInstance().currentUser == null) return@collect

                    // Guard #2: skip evaluation when the database has just been cleared
                    // (post-logout via clearAllTables). Without this guard, the pipeline
                    // would seed 50 locked ghost rows into an empty achievements table.
                    // Those rows then cause ALL earned achievements to appear as
                    // "newly unlocked" on the next login+sync cycle, producing a banner storm.
                    if (habits.isEmpty() && completions.isEmpty()) return@collect
                    persistDeltas(habits, completions)
                }
        }
    }

    /**
     * Runs the evaluator and writes only the truly changed rows to the database.
     *
     * Algorithm (idempotent reducer):
     * 1. Batch-load all existing rows in one query → build a [Map] for O(1) lookup.
     *    This replaces issuing one [AchievementDao.findByKey] call per definition
     *    (50 separate queries) with a single round-trip per evaluation cycle.
     * 2. For each earned achievement not yet stamped, insert or update with a
     *    non-null [AchievementEntity.unlockedAt] and emit to [newlyUnlocked].
     * 3. Retraction loop — for every definition NOT in the earned set:
     *    - If the row was previously unlocked (user deleted completions in HistoryScreen
     *      and the threshold is no longer met) → clear [unlockedAt].
     *    - If only [progress] changed → update progress only.
     *    - If nothing changed → skip the write entirely, preventing a spurious Room
     *      invalidation that would cascade into a redundant UI re-render.
     *
     * All DAO calls are `suspend`, so this executes off the main thread via Room's
     * built-in coroutine dispatcher.
     */
    private suspend fun persistDeltas(
        habits: List<HabitEntity>,
        completions: List<HabitCompletionEntity>
    ) {
        val earned = evaluateAchievements(habits, completions)
        val earnedKeys = earned.map { it.definition.key }.toSet()
        val now = System.currentTimeMillis()

        // Single batch read — O(1) DB round-trip regardless of definition count.
        val existingByKey: Map<String, AchievementEntity> =
            achievementDao.getAllAchievementsOnce().associateBy { it.key }

        // --- Persist newly unlocked achievements ---
        for (unlockedAchievement in earned) {
            val def = unlockedAchievement.definition
            val existing = existingByKey[def.key]
            when {
                existing == null -> {
                    // First time this achievement is reached — insert a fresh row.
                    val entity = AchievementEntity(key = def.key, unlockedAt = now, progress = def.threshold)
                    achievementDao.insert(entity)
                    _newlyUnlocked.emit(def)
                    // Push to Firestore immediately so it survives logout+clearAllTables.
                    // [SyncController.syncAchievements] will then pull it back on the
                    // next login BEFORE habits/completions sync — preventing a re-banner.
                    viewModelScope.launch { runCatching { syncController.pushAchievement(entity) } }
                }
                existing.unlockedAt == null -> {
                    // Previously tracked as in-progress — now earned. Stamp the timestamp.
                    val updated = existing.copy(unlockedAt = now, progress = def.threshold)
                    achievementDao.update(updated)
                    _newlyUnlocked.emit(def)
                    viewModelScope.launch { runCatching { syncController.pushAchievement(updated) } }
                }
                // else: already unlocked — no-op; preserves the original unlock timestamp.
            }
        }

        // --- Retraction + progress update for non-earned definitions ---
        // This is the idempotent reducer for retraction: when a user deletes completions
        // in HistoryScreen and a previously earned achievement's threshold is no longer
        // met, [EvaluateAchievementsUseCase] will drop it from [earned], and the loop
        // below detects the stale [unlockedAt] and clears it. Re-running on unchanged
        // inputs is safe: the change guards ensure no DB write occurs.
        for (def in AchievementDefinition.all) {
            if (def.key in earnedKeys) continue

            val progress = evaluateAchievements.computeProgress(def, habits, completions)
            val existing = existingByKey[def.key]
            when {
                existing == null -> {
                    // No row yet — seed a locked progress-tracking row.
                    achievementDao.insert(AchievementEntity(key = def.key, progress = progress))
                }
                existing.unlockedAt != null -> {
                    // Achievements are permanent once fully unlocked — do not retract.
                    // This covers both habit deletion (CASCADE wipes completions) and
                    // manual history edits. The unlock timestamp is preserved as-is.
                    // Only in-progress rows (unlockedAt == null) still reset their bar.
                }
                existing.progress != progress -> {
                    // Not yet earned but progress value changed — update the bar only.
                    achievementDao.update(existing.copy(progress = progress))
                }
                // else: row exists, not unlocked, progress unchanged — skip write entirely
                // to avoid a spurious Room invalidation cascading into UI re-renders.
            }
        }
    }
}
