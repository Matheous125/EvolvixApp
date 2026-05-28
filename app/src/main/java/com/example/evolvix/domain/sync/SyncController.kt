package com.example.evolvix.domain.sync

import com.example.evolvix.data.local.AchievementDao
import com.example.evolvix.data.local.HabitDao
import com.example.evolvix.data.model.AchievementEntity
import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitEntity
import com.example.evolvix.data.model.HabitFrequency
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Mediator that keeps the local Room database and remote Firestore in sync.
 *
 * **Sync strategy:**
 * - **Habits:** bidirectional upsert. Remote habits missing locally are inserted;
 *   local habits are pushed to Firestore on every sync (last-write-wins per device).
 *   Phase 10.2 checkbox 4 will refine this with `lastModified` timestamps for proper
 *   conflict resolution without overwriting newer remote changes.
 * - **Completions — timestamp union merge (PLAN §10.2 conflict resolution):**
 *   Each completion is uniquely identified by the pair `(habitId, epochSeconds)` where
 *   `epochSeconds` is `progressUpdate.toEpochSecond(UTC)`. Within one habit's history it
 *   is physically impossible to log the same habit at the exact same UTC second on two
 *   different devices, so the epoch second is a reliable natural key.
 *
 *   The merge is computed per habit as a **union of two `Set<Long>`** — one from Room,
 *   one from Firestore:
 *   ```
 *   mergedTimestamps = localTimestamps ∪ remoteTimestamps
 *   ```
 *   Records in `remote \ local` are written to Room; records in `local \ remote` are
 *   written to Firestore. This is strictly additive: no record is ever deleted by the
 *   sync, guaranteeing zero data loss regardless of sync order or network conditions.
 *
 * **Firestore layout:**
 * ```
 * users/{uid}/habits/{habitId}                   → habit fields as a plain map
 * users/{uid}/completions/{habitId}_{epochSec}   → completion fields; key encodes identity
 * ```
 *
 * **Pattern: Mediator** — SyncController is the single coordinator between Room and
 * Firestore. Neither the DAO nor Firestore knows about each other; all coordination
 * logic lives here.
 *
 * @param habitDao       Room DAO for habits and completions.
 * @param achievementDao Room DAO for achievement persistence.
 */
class SyncController(
    private val habitDao: HabitDao,
    private val achievementDao: AchievementDao
) {

    companion object {
        /**
         * Process-wide sync state observable (Pattern: **Observer via StateFlow**).
         *
         * Stored at the class level so both the activity-scoped [SyncController] instance
         * (created in [com.example.evolvix.MainActivity]) and the ephemeral instance inside
         * [com.example.evolvix.notifications.SyncWorker.doWork] write to the same signal.
         * MainScreen collects [syncState] to render the top-bar indicator.
         */
        private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
        val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

        /**
         * Resets the sync state back to [SyncState.Idle].
         * Called by MainScreen after displaying the [SyncState.Success] indicator
         * for a short period so the checkmark does not persist indefinitely.
         */
        fun resetToIdle() {
            _syncState.value = SyncState.Idle
        }
    }

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    // ── Firestore path helpers ────────────────────────────────────────────────

    /**
     * Returns the UID of the currently signed-in user, or throws if the user is
     * not authenticated. [sync] must only be called while the user is logged in.
     */
    private fun uid(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("SyncController.sync() called while no user is signed in.")

    private fun habitsRef() =
        firestore.collection("users").document(uid()).collection("habits")

    private fun completionsRef() =
        firestore.collection("users").document(uid()).collection("completions")

    private fun achievementsRef() =
        firestore.collection("users").document(uid()).collection("achievements")

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Performs a full bidirectional sync of habits and completions.
     *
     * The operation is idempotent — calling it multiple times produces the same
     * end state. Intended to be called from a [kotlinx.coroutines.CoroutineScope]
     * such as a WorkManager [androidx.work.CoroutineWorker] or `viewModelScope`.
     *
     * @throws IllegalStateException if no user is signed in.
     */
    suspend fun sync() {
        _syncState.value = SyncState.Syncing
        try {
            // Achievement sync MUST run before habits/completions.
            // Pre-populating the achievements table with correct unlockedAt timestamps
            // ensures the reactive evaluator in AchievementsViewModel sees
            // existing.unlockedAt != null for already-earned achievements,
            // preventing spurious banner emissions on every post-logout re-login.
            syncAchievements()
            syncHabits()
            syncCompletions()
            _syncState.value = SyncState.Success
        } catch (e: Exception) {
            _syncState.value = SyncState.Error
            throw e  // re-throw so SyncWorker can return Result.failure() and trigger backoff
        }
    }

    /**
     * Pushes a single newly-unlocked achievement to Firestore immediately.
     *
     * Called by [com.example.evolvix.ui.viewmodel.AchievementsViewModel] in real-time
     * whenever an achievement is first earned. This ensures the achievement survives a
     * subsequent `logout → clearAllTables → login` cycle: [syncAchievements] will pull
     * it back into Room before the reactive evaluator fires, so no banner is re-emitted.
     *
     * No-ops silently if the user is not signed in (e.g. called during a race with logout).
     */
    suspend fun pushAchievement(achievement: AchievementEntity) {
        if (auth.currentUser == null) return
        achievementsRef().document(achievement.key).set(
            mapOf(
                "key"        to achievement.key,
                "unlockedAt" to achievement.unlockedAt,
                "progress"   to achievement.progress
            )
        ).await()
    }

    // ── Achievement sync ──────────────────────────────────────────────────────

    /**
     * Bidirectional achievement sync.
     *
     * **Push (local → Firestore):** All locally-unlocked achievements are written to
     * Firestore so they survive a `clearAllTables` on logout.
     *
     * **Pull (Firestore → local):** All Firestore achievements with a non-null
     * `unlockedAt` are upserted into Room using [AchievementDao.upsertForSync].
     * Because this runs **before** [syncHabits] and [syncCompletions], the reactive
     * evaluator in [com.example.evolvix.ui.viewmodel.AchievementsViewModel] will find
     * `existing.unlockedAt != null` for each already-earned achievement, skipping the
     * banner emit path and showing banners only for genuinely new unlocks.
     */
    private suspend fun syncAchievements() {
        // ── Local → Firestore ─────────────────────────────────────────────────
        val localUnlocked = achievementDao.getAllAchievementsOnce()
            .filter { it.unlockedAt != null }
        if (localUnlocked.isNotEmpty()) {
            val batch = firestore.batch()
            for (a in localUnlocked) {
                batch.set(
                    achievementsRef().document(a.key),
                    mapOf(
                        "key"        to a.key,
                        "unlockedAt" to a.unlockedAt,
                        "progress"   to a.progress
                    )
                )
            }
            batch.commit().await()
        }

        // ── Firestore → local ─────────────────────────────────────────────────
        val remoteSnapshot = achievementsRef().get().await()
        for (doc in remoteSnapshot.documents) {
            val key        = doc.getString("key")   ?: continue
            val unlockedAt = doc.getLong("unlockedAt") ?: continue // skip non-unlocked docs
            val progress   = (doc.getLong("progress") ?: 0L).toInt()
            val local      = achievementDao.findByKey(key)
            // Only upsert when the local row is missing or not yet stamped as unlocked.
            // Preserving an existing local unlockedAt avoids overwriting the original
            // timestamp with a value from another device's clock.
            if (local == null || local.unlockedAt == null) {
                achievementDao.upsertForSync(
                    AchievementEntity(
                        id         = local?.id ?: 0,
                        key        = key,
                        unlockedAt = unlockedAt,
                        progress   = progress
                    )
                )
            }
        }
    }

    // ── Habit sync ────────────────────────────────────────────────────────────

    /**
     * Bidirectional habit sync with `lastModified`-based conflict resolution.
     *
     * 1. Pull remote habits → compare `lastModified`:
     *    - Missing locally → insert.
     *    - Exists locally AND `remote.lastModified > local.lastModified` → overwrite
     *      (the remote device made a more recent edit).
     *    - Exists locally AND remote is not newer → keep local copy (no-op).
     * 2. Push habits where `lastModified > syncedAt` (or never synced) to Firestore,
     *    then stamp `syncedAt` on those rows so they are skipped on the next sync.
     *
     * Uses Firestore batch writes for the push step. Batch limit is 500; typical
     * users will have far fewer habits.
     */
    private suspend fun syncHabits() {
        val now = System.currentTimeMillis()

        // ── Remote → local ────────────────────────────────────────────────────
        val remoteSnapshot = habitsRef().get().await()
        val remoteHabits   = remoteSnapshot.documents.mapNotNull { it.toHabitEntity() }
        val localHabitsById = habitDao.getAllHabitsOnce().associateBy { it.id }

        for (remote in remoteHabits) {
            val local = localHabitsById[remote.id]
            when {
                local == null ->
                    // New habit from another device — insert locally.
                    habitDao.insertHabit(remote.copy(syncedAt = now))
                remote.lastModified > local.lastModified ->
                    // Remote is newer — overwrite the local record.
                    habitDao.updateHabit(remote.copy(syncedAt = now))
                // else: local is same age or newer — keep local, it will be pushed below.
            }
        }

        // ── Local → remote ────────────────────────────────────────────────────
        // Only push habits that have been modified since the last successful sync.
        val allLocalHabits = habitDao.getAllHabitsOnce()
        val toPush = allLocalHabits.filter { h ->
            h.syncedAt == null || h.lastModified > h.syncedAt
        }
        if (toPush.isNotEmpty()) {
            val batch = firestore.batch()
            for (habit in toPush) {
                val docRef = habitsRef().document(habit.id.toString())
                batch.set(docRef, habit.toFirestoreMap())
            }
            batch.commit().await()
            // Stamp syncedAt on the pushed rows so next sync skips them.
            habitDao.markHabitsSynced(toPush.map { it.id }, now)
        }
    }

    // ── Completion sync ───────────────────────────────────────────────────────

    /**
     * Implements the **timestamp union merge** conflict resolution strategy.
     *
     * Completions are grouped by [HabitCompletionEntity.habitId]. For each habit a
     * `Set<Long>` of epoch-second timestamps is built from the local side and the
     * remote side. The merged set is the **union** — records present on only one side
     * are copied to the other. Records present on both sides are left untouched.
     *
     * Formally:
     * ```
     *   for each habitId:
     *     merged = localTimestamps(habitId) ∪ remoteTimestamps(habitId)
     *     insert into Room:     merged \ localTimestamps(habitId)
     *     upload to Firestore:  merged \ remoteTimestamps(habitId)
     * ```
     *
     * This is strictly additive: no record is ever deleted by the sync, so data loss
     * is structurally impossible (PLAN §10.2 clarification).
     */
    private suspend fun syncCompletions() {
        val localCompletions  = habitDao.getAllCompletionsOnce()
        val remoteSnapshot    = completionsRef().get().await()
        val remoteCompletions = remoteSnapshot.documents.mapNotNull { it.toCompletionEntity() }

        // Group both sides by habitId so the union operates per-habit.
        val localByHabit:  Map<Int, List<HabitCompletionEntity>> = localCompletions.groupBy  { it.habitId }
        val remoteByHabit: Map<Int, List<HabitCompletionEntity>> = remoteCompletions.groupBy { it.habitId }

        // Collect all habitIds that appear on either side.
        val allHabitIds = localByHabit.keys + remoteByHabit.keys

        val toInsertLocally  = mutableListOf<HabitCompletionEntity>()
        val toUploadRemotely = mutableListOf<HabitCompletionEntity>()

        for (habitId in allHabitIds) {
            // Build timestamp sets (unique Long per completion within one habit).
            val localSet:  Set<Long> = localByHabit[habitId]
                ?.map { it.epochSeconds() }.orEmpty().toSet()
            val remoteSet: Set<Long> = remoteByHabit[habitId]
                ?.map { it.epochSeconds() }.orEmpty().toSet()

            // Union merge — symmetric difference drives the two copy directions.
            val onlyRemote = remoteByHabit[habitId].orEmpty()
                .filter { it.epochSeconds() !in localSet }
            val onlyLocal  = localByHabit[habitId].orEmpty()
                .filter { it.epochSeconds() !in remoteSet }

            toInsertLocally  += onlyRemote
            toUploadRemotely += onlyLocal
        }

        // ── Remote → local ────────────────────────────────────────────────────
        val now = System.currentTimeMillis()
        for (completion in toInsertLocally) {
            // id = 0 so Room auto-generates a new primary key for this row.
            habitDao.insertCompletion(completion.copy(id = 0, syncedAt = now))
        }

        // ── Local → remote (Firestore batch write) ────────────────────────────
        if (toUploadRemotely.isNotEmpty()) {
            val batch = firestore.batch()
            for (completion in toUploadRemotely) {
                val docRef = completionsRef().document(completion.firestoreDocId())
                batch.set(docRef, completion.toFirestoreMap())
            }
            batch.commit().await()
            // Stamp syncedAt so next sync skips these rows.
            habitDao.markCompletionsSynced(toUploadRemotely.map { it.id }, now)
        }
    }

    // ── Conflict resolution helpers ───────────────────────────────────────────

    /**
     * The unique natural key for a completion within its habit's history.
     *
     * `progressUpdate` is converted to UTC epoch seconds — the **unique Long** that
     * identifies this completion for the timestamp union merge. Two completions of the
     * same habit at the same UTC second are physically impossible in normal usage, so
     * this value is a reliable deduplication key (PLAN §10.2).
     */
    private fun HabitCompletionEntity.epochSeconds(): Long =
        progressUpdate.toEpochSecond(ZoneOffset.UTC)

    /**
     * Firestore document ID for this completion.
     * Format: `"{habitId}_{epochSeconds}"` — encodes both parts of the natural key so
     * documents are human-readable and globally unique within the `completions` collection.
     */
    private fun HabitCompletionEntity.firestoreDocId(): String =
        "${habitId}_${epochSeconds()}"

    // ── Serialization helpers ─────────────────────────────────────────────────

    /**
     * Serializes a [HabitEntity] to a plain `Map<String, Any?>` for Firestore storage.
     * [LocalDateTime] fields are stored as epoch seconds (Long) so they survive
     * the Firestore round-trip without relying on Kotlin-specific types.
     */
    private fun HabitEntity.toFirestoreMap(): Map<String, Any?> = mapOf(
        "id"                   to id,
        "name"                 to name,
        "currentCount"         to currentCount,
        "frequency"            to frequency.name,
        "frequencyN"           to frequencyN,
        "target"               to target,
        "targetVersion"        to targetVersion,
        "totalProgressUpdates" to totalProgressUpdates,
        "totalTargetReaches"   to totalTargetReaches,
        "lastResetDate"        to lastResetDate.toEpochSecond(ZoneOffset.UTC),
        "colorHex"             to colorHex,
        "categories"           to categories,
        "iconKey"              to iconKey,
        "reminderEnabled"      to reminderEnabled,
        "reminderTime"         to reminderTime,
        "pausedUntil"          to pausedUntil,
        "sortOrder"            to sortOrder,
        "categoryGroup"        to categoryGroup,
        "manualGroup"          to manualGroup,
        "groupSortOrder"       to groupSortOrder,
        "lastModified"         to lastModified,
        "syncedAt"             to syncedAt
    )

    /**
     * Serializes a [HabitCompletionEntity] to a Firestore map.
     * [progressUpdate] is stored as epoch seconds for cross-platform compatibility.
     */
    private fun HabitCompletionEntity.toFirestoreMap(): Map<String, Any?> = mapOf(
        "habitId"             to habitId,
        "progressUpdate"      to progressUpdate.toEpochSecond(ZoneOffset.UTC),
        "isTargetReached"     to isTargetReached,
        "fromReminder"        to fromReminder,
        "snoozeCount"         to snoozeCount,
        "targetVersion"       to targetVersion,
        "perceivedDifficulty" to perceivedDifficulty,
        "lastModified"        to lastModified,
        "syncedAt"            to syncedAt
    )

    /**
     * Deserializes a Firestore [DocumentSnapshot] into a [HabitEntity].
     * Returns `null` if any required field is missing or malformed — defensive
     * parsing ensures a single corrupt document does not abort the entire sync.
     */
    private fun DocumentSnapshot.toHabitEntity(): HabitEntity? = runCatching {
        @Suppress("UNCHECKED_CAST")
        HabitEntity(
            id                   = (getLong("id") ?: return null).toInt(),
            name                 = getString("name") ?: return null,
            currentCount         = (getLong("currentCount") ?: 0L).toInt(),
            frequency            = HabitFrequency.valueOf(getString("frequency") ?: "Daily"),
            frequencyN           = (getLong("frequencyN") ?: 1L).toInt(),
            target               = (getLong("target") ?: 1L).toInt(),
            targetVersion        = (getLong("targetVersion") ?: 1L).toInt(),
            totalProgressUpdates = (getLong("totalProgressUpdates") ?: 0L).toInt(),
            totalTargetReaches   = (getLong("totalTargetReaches") ?: 0L).toInt(),
            lastResetDate        = LocalDateTime.ofEpochSecond(
                getLong("lastResetDate") ?: 0L, 0, ZoneOffset.UTC
            ),
            colorHex             = getString("colorHex") ?: "#4CAF50",
            categories           = (get("categories") as? List<String>) ?: emptyList(),
            iconKey              = getString("iconKey"),
            reminderEnabled      = getBoolean("reminderEnabled") ?: false,
            reminderTime         = getLong("reminderTime"),
            pausedUntil          = getLong("pausedUntil"),
            sortOrder            = (getLong("sortOrder") ?: 0L).toInt(),
            categoryGroup        = getString("categoryGroup"),
            manualGroup          = getString("manualGroup"),
            groupSortOrder       = (getLong("groupSortOrder") ?: 0L).toInt(),
            lastModified         = getLong("lastModified") ?: System.currentTimeMillis(),
            syncedAt             = getLong("syncedAt")
        )
    }.getOrNull()

    /**
     * Deserializes a Firestore [DocumentSnapshot] into a [HabitCompletionEntity].
     * Returns `null` if any required field is missing — same defensive strategy as
     * [toHabitEntity].
     */
    private fun DocumentSnapshot.toCompletionEntity(): HabitCompletionEntity? = runCatching {
        HabitCompletionEntity(
            id                  = 0, // Room assigns the PK when this row is inserted locally.
            habitId             = (getLong("habitId") ?: return null).toInt(),
            progressUpdate      = LocalDateTime.ofEpochSecond(
                getLong("progressUpdate") ?: return null, 0, ZoneOffset.UTC
            ),
            isTargetReached     = getBoolean("isTargetReached") ?: false,
            fromReminder        = getBoolean("fromReminder") ?: false,
            snoozeCount         = getLong("snoozeCount")?.toInt(),
            targetVersion       = (getLong("targetVersion") ?: 1L).toInt(),
            perceivedDifficulty = getLong("perceivedDifficulty")?.toInt(),
            lastModified        = getLong("lastModified") ?: System.currentTimeMillis(),
            syncedAt            = getLong("syncedAt")
        )
    }.getOrNull()
}
