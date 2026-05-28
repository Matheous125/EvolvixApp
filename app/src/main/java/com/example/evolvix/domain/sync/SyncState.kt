package com.example.evolvix.domain.sync

/**
 * Represents the current state of the Room ↔ Firestore synchronisation.
 *
 * Exposed as a process-wide [kotlinx.coroutines.flow.StateFlow] via [SyncController.syncState]
 * so that both the [com.example.evolvix.notifications.SyncWorker] (which creates its own
 * [SyncController] instance) and the activity-scoped instance created in
 * [com.example.evolvix.MainActivity] share one observable signal.
 *
 * The Main screen's top bar observes this via the **Observer pattern** (StateFlow + collectAsState).
 */
sealed class SyncState {
    /** No sync is running and no recent result is pending display. */
    object Idle : SyncState()

    /** A sync operation is currently in progress. */
    object Syncing : SyncState()

    /**
     * The most recent sync completed successfully.
     * MainScreen resets this back to [Idle] automatically after a short display period.
     */
    object Success : SyncState()

    /** The most recent sync attempt failed with an exception. */
    object Error : SyncState()
}
