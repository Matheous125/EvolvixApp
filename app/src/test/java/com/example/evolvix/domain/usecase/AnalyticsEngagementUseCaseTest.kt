package com.example.evolvix.domain.usecase

import com.example.evolvix.data.local.AppSessionDao
import com.example.evolvix.data.model.AppSessionEntity
import com.example.evolvix.domain.model.AnalyticsEngagement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Unit tests for [AnalyticsEngagementUseCase] (B3, PLAN-POLISH-PASS).
 *
 * Uses a hand-written [FakeSessionDao] (structural subtyping of [AppSessionDao]) to avoid
 * any Android runtime or Mockito dependency.  [today] is pinned to a fixed date so the
 * 30-day window is always deterministic.
 *
 * ### Algorithm under test (summary)
 *  1. Load up to 200 sessions via [AppSessionDao.getRecent].
 *  2. Discard sessions whose [AppSessionEntity.startedAt] falls **before** (today − 29 days).
 *  3. Partition remaining sessions by `"StatisticsScreen" ∈ screensVisited`.
 *  4. Both buckets must have ≥ [AnalyticsEngagement.MIN_SESSIONS_PER_BUCKET] (5) sessions;
 *     otherwise return [AnalyticsEngagement.insufficient].
 *  5. lift = (viewerActiveDays − nonViewerActiveDays) / 30, clamped to [−1, +1].
 *
 * ### Coverage matrix
 * | # | Scenario                                    | Expected outcome      |
 * |---|---------------------------------------------|-----------------------|
 * | 1 | Viewer bucket < 5                           | insufficient          |
 * | 2 | Non-viewer bucket < 5                       | insufficient          |
 * | 3 | Empty session list                          | insufficient          |
 * | 4 | Viewers: 5 distinct days, non-viewers: 1    | lift > 0, data ok     |
 * | 5 | Viewers: 1 distinct day, non-viewers: 5     | lift < 0, data ok     |
 * | 6 | 4 inside + 2 outside 30-day window          | insufficient (filter) |
 * | 7 | 5 sessions on 2 viewer dates (dedup check)  | viewerActiveDays = 2  |
 */
class AnalyticsEngagementUseCaseTest {

    /** Fixed reference date — all test data is expressed as "days before today". */
    private val today = LocalDate.of(2025, 6, 1)

    // ── Helpers ───────────────────────────────────────────────────────────────────

    /**
     * Creates an [AppSessionEntity] starting at 09:00, [daysAgo] days before [today].
     * [viewedStats] = true inserts `"StatisticsScreen"` into [screensVisited].
     */
    private fun session(daysAgo: Int, viewedStats: Boolean): AppSessionEntity {
        val start = today.minusDays(daysAgo.toLong()).atTime(9, 0)
        return AppSessionEntity(
            startedAt = start,
            endedAt = start.plusMinutes(10),
            screensVisited = if (viewedStats) listOf("StatisticsScreen") else listOf("HabitsScreen")
        )
    }

    /**
     * Minimal fake DAO — only [AppSessionDao.getRecent] is called by the use case.
     * All other methods throw [UnsupportedOperationException] to catch accidental calls.
     *
     * This is a Test Double (Fake) in the xUnit pattern — a lightweight, in-process
     * substitute that avoids any Room or Android dependency.
     */
    private class FakeSessionDao(private val sessions: List<AppSessionEntity>) : AppSessionDao {
        override suspend fun getRecent(limit: Int): List<AppSessionEntity> = sessions.take(limit)
        override suspend fun insert(session: AppSessionEntity): Long = throw UnsupportedOperationException()
        override suspend fun update(session: AppSessionEntity) = throw UnsupportedOperationException()
        override fun getSince(since: LocalDateTime): Flow<List<AppSessionEntity>> = throw UnsupportedOperationException()
        override suspend fun count(): Int = throw UnsupportedOperationException()
    }

    private fun useCaseWith(sessions: List<AppSessionEntity>): AnalyticsEngagementUseCase =
        AnalyticsEngagementUseCase(FakeSessionDao(sessions))

    // ── Test 1: viewer bucket too small ──────────────────────────────────────────

    @Test
    fun `returns insufficient when viewer bucket has fewer than MIN sessions`() = runBlocking {
        // 3 viewers < MIN_SESSIONS_PER_BUCKET (5); 7 non-viewers (sufficient) → guard fires.
        val sessions = (1..3).map { session(it, viewedStats = true) } +
                       (10..16).map { session(it, viewedStats = false) }

        val result = useCaseWith(sessions).execute(today)

        assertFalse(result.hasSufficientData)
        assertEquals(AnalyticsEngagement.insufficient, result)
    }

    // ── Test 2: non-viewer bucket too small ───────────────────────────────────────

    @Test
    fun `returns insufficient when non-viewer bucket has fewer than MIN sessions`() = runBlocking {
        // 7 viewers (sufficient); 2 non-viewers < MIN_SESSIONS_PER_BUCKET (5) → guard fires.
        val sessions = (1..7).map { session(it, viewedStats = true) } +
                       (10..11).map { session(it, viewedStats = false) }

        val result = useCaseWith(sessions).execute(today)

        assertFalse(result.hasSufficientData)
        assertEquals(AnalyticsEngagement.insufficient, result)
    }

    // ── Test 3: empty input ───────────────────────────────────────────────────────

    @Test
    fun `returns insufficient for an empty session list`() = runBlocking {
        val result = useCaseWith(emptyList()).execute(today)

        assertFalse(result.hasSufficientData)
        assertEquals(AnalyticsEngagement.insufficient, result)
    }

    // ── Test 4: positive lift ─────────────────────────────────────────────────────

    @Test
    fun `positive lift when viewers have more distinct active days than non-viewers`() = runBlocking {
        // 5 viewers on 5 separate days  → viewerActiveDays = 5.
        // 5 non-viewers all on day 10   → nonViewerActiveDays = 1 (same calendar date).
        val viewers    = (1..5).map { session(it, viewedStats = true) }
        val nonViewers = (1..5).map { session(10, viewedStats = false) }

        val result = useCaseWith(viewers + nonViewers).execute(today)

        assertTrue(result.hasSufficientData)
        assertEquals(5, result.viewerActiveDays)
        assertEquals(1, result.nonViewerActiveDays)
        assertEquals((5 - 1) / 30f, result.lift, 0.001f)
        assertTrue(result.lift > 0f)
    }

    // ── Test 5: negative lift ─────────────────────────────────────────────────────

    @Test
    fun `negative lift when non-viewers have more distinct active days than viewers`() = runBlocking {
        // 5 viewers all on day 1        → viewerActiveDays = 1.
        // 5 non-viewers on 5 separate days → nonViewerActiveDays = 5.
        val viewers    = (1..5).map { session(1, viewedStats = true) }
        val nonViewers = (2..6).map { session(it, viewedStats = false) }

        val result = useCaseWith(viewers + nonViewers).execute(today)

        assertTrue(result.hasSufficientData)
        assertEquals(1, result.viewerActiveDays)
        assertEquals(5, result.nonViewerActiveDays)
        assertEquals((1 - 5) / 30f, result.lift, 0.001f)
        assertTrue(result.lift < 0f)
    }

    // ── Test 6: window filtering ──────────────────────────────────────────────────

    @Test
    fun `sessions outside the 30-day window are excluded from bucket counts`() = runBlocking {
        // 4 viewers inside the window (days 1-4) + 2 viewers outside (days 31 and 32).
        // Without the window filter, viewer count = 6 (≥ 5) → would pass the guard.
        // With correct filtering, viewer count = 4 (< 5) → guard fires → insufficient.
        val viewersInside  = (1..4).map { session(it, viewedStats = true) }
        val viewersOutside = listOf(session(31, viewedStats = true), session(32, viewedStats = true))
        val nonViewers     = (5..9).map { session(it, viewedStats = false) }

        val result = useCaseWith(viewersInside + viewersOutside + nonViewers).execute(today)

        assertFalse("Window filter must exclude sessions older than 30 days", result.hasSufficientData)
    }

    // ── Test 7: active-day deduplication ─────────────────────────────────────────

    @Test
    fun `viewer active days counts distinct calendar dates not raw session count`() = runBlocking {
        // 5 viewer sessions spread over only 2 distinct days (3 on day 1, 2 on day 2).
        // The use case must deduplicate: viewerActiveDays = 2, not 5.
        val viewers = listOf(
            session(1, viewedStats = true), session(1, viewedStats = true), session(1, viewedStats = true),
            session(2, viewedStats = true), session(2, viewedStats = true)
        )
        // 5 non-viewers on 5 separate days (control bucket).
        val nonViewers = (3..7).map { session(it, viewedStats = false) }

        val result = useCaseWith(viewers + nonViewers).execute(today)

        assertTrue(result.hasSufficientData)
        assertEquals(
            "Multiple sessions on the same date must count as 1 active day",
            2, result.viewerActiveDays
        )
        assertEquals(5, result.nonViewerActiveDays)
    }
}
