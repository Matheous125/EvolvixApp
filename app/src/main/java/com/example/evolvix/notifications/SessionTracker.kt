package com.example.evolvix.notifications

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.evolvix.data.local.AppDatabase
import com.example.evolvix.data.model.AppSessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * Singleton lifecycle observer that records each app foreground session to the
 * [AppSessionEntity] Room table (Phase 9.6).
 *
 * **Responsibility:** track when the user opens and closes the app (`onStart` / `onStop`
 * relative to the entire process), and which Compose navigation routes they visited.
 * This **Observer** is attached to [ProcessLifecycleOwner] — it fires once per app
 * foreground/background transition, not per-Activity or per-Fragment.
 *
 * **Why ProcessLifecycleOwner:** individual Activity lifecycle callbacks fire on every
 * config change (rotation). We only want session boundaries (app enters / leaves
 * foreground), so ProcessLifecycleOwner is the correct scope.
 *
 * **Thread safety:** Room operations run on [Dispatchers.IO] via a dedicated
 * [CoroutineScope]. [currentSessionId] and [screenLog] are mutated only from a single
 * logical thread (main thread for `logScreen`, IO scope for DB writes after snapshot),
 * so a `@Volatile` flag + synchronized copy-on-write is sufficient for our scale.
 *
 * **Screen log cap:** [screenLog] is bounded at [MAX_SCREENS] entries (32) to prevent
 * unbounded memory growth during long sessions.
 */
object SessionTracker : DefaultLifecycleObserver {

    private const val MAX_SCREENS = 32

    /** Background coroutine scope for all Room operations. Acts as a mini-repository. */
    private val ioScope = CoroutineScope(Dispatchers.IO)

    /** Row ID of the currently-open session; null when app is in background. */
    @Volatile
    private var currentSessionId: Long? = null

    /**
     * Ordered set of navigation routes visited this session.
     * [LinkedHashSet] preserves insertion order and deduplicates repeated visits.
     */
    private val screenLog = LinkedHashSet<String>()

    // ── Initialization ──────────────────────────────────────────────────────────

    /**
     * Attaches [SessionTracker] to the [ProcessLifecycleOwner] so it receives
     * `onStart` / `onStop` callbacks for the whole-app lifecycle.
     *
     * Call once from [com.example.evolvix.MainActivity.onCreate].
     *
     * @param context Any [Context]; Application context is extracted internally.
     */
    fun init(context: Context) {
        // Store application context to avoid leaking Activity references.
        appContext = context.applicationContext
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /** Application context stored after [init]; never leaks Activity. */
    private lateinit var appContext: Context

    // ── Lifecycle callbacks ──────────────────────────────────────────────────────

    /**
     * Called when the app enters the foreground (first Activity started).
     * Opens a new [AppSessionEntity] row with [endedAt] = null (in-progress sentinel).
     */
    override fun onStart(owner: LifecycleOwner) {
        screenLog.clear()
        ioScope.launch {
            val dao = AppDatabase.getDatabase(appContext).appSessionDao()
            val entity = AppSessionEntity(
                startedAt = LocalDateTime.now(),
                endedAt = null,
                screensVisited = emptyList()
            )
            currentSessionId = dao.insert(entity)
        }
    }

    /**
     * Called when the app moves to the background (last Activity stopped).
     * Closes the current session row by writing [endedAt] and the accumulated
     * [screenLog] snapshot.
     */
    override fun onStop(owner: LifecycleOwner) {
        val sessionId = currentSessionId ?: return
        // Snapshot the screen list on the calling (main) thread before the launch.
        val screens = synchronized(screenLog) { screenLog.toList() }
        ioScope.launch {
            val dao = AppDatabase.getDatabase(appContext).appSessionDao()
            // Read the in-progress row, close it, and persist.
            val existing = dao.getRecent(1).firstOrNull { it.id == sessionId } ?: return@launch
            dao.update(existing.copy(endedAt = LocalDateTime.now(), screensVisited = screens))
        }
        currentSessionId = null
    }

    // ── Screen logging ───────────────────────────────────────────────────────────

    /**
     * Records a navigation [route] as visited during the current session.
     *
     * Call from the [androidx.navigation.NavController] destination-changed listener
     * in [com.example.evolvix.MainActivity] → `AppContent`.
     *
     * The set is capped at [MAX_SCREENS] entries; once full, new routes are silently
     * dropped to keep memory bounded during very long sessions.
     *
     * @param route The Compose navigation route string (e.g. "statistics", "habits").
     */
    fun logScreen(route: String) {
        synchronized(screenLog) {
            if (screenLog.size < MAX_SCREENS) {
                screenLog.add(route)
            }
        }
    }
}
