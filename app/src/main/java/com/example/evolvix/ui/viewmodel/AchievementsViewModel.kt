package com.example.evolvix.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.evolvix.data.local.AchievementDao
import com.example.evolvix.data.local.HabitDao
import com.example.evolvix.data.model.AchievementEntity
import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitEntity
import com.example.evolvix.domain.model.AchievementDefinition
import com.example.evolvix.domain.usecase.EvaluateAchievementsUseCase
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
 * @property habitDao      Source for the habit list and full completion history.
 * @property achievementDao Persistence layer for [AchievementEntity] rows.
 */
class AchievementsViewModel(
    private val habitDao: HabitDao,
    private val achievementDao: AchievementDao
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
                    achievementDao.insert(
                        AchievementEntity(key = def.key, unlockedAt = now, progress = def.threshold)
                    )
                    _newlyUnlocked.emit(def)
                }
                existing.unlockedAt == null -> {
                    // Previously tracked as in-progress — now earned. Stamp the timestamp.
                    achievementDao.update(existing.copy(unlockedAt = now, progress = def.threshold))
                    _newlyUnlocked.emit(def)
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
                    // Retraction: was unlocked, requirement no longer holds.
                    // Clear unlockedAt and refresh progress in a single update.
                    achievementDao.update(existing.copy(unlockedAt = null, progress = progress))
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
