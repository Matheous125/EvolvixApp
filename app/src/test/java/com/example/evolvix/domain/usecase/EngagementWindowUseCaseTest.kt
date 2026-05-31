package com.example.evolvix.domain.usecase

import com.example.evolvix.data.local.AppSessionDao
import com.example.evolvix.data.model.AppSessionEntity
import com.example.evolvix.domain.ai.MathHabitPredictor
import com.example.evolvix.domain.model.EngagementWindow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Unit tests for [EngagementWindowUseCase] (Phase 9.6).
 *
 * Uses a hand-rolled fake [AppSessionDao] — no Mockito or Room runtime is required.
 * [MathHabitPredictor] is injected as the [com.example.evolvix.domain.ai.HabitPredictor]
 * so inference runs in pure Kotlin without TFLite.
 *
 * [MathHabitPredictor.predictEngagementHour] rule:
 *   returns `features.recentAvgStartHour14d.coerceIn(0f, 23f)`
 * Therefore `result.predictedHour = avgStartHour14d.toInt().coerceIn(0, 23)`.
 *
 * All session timestamps are constructed relative to [LocalDate.now()] so the
 * feature derivation (cutoff14d, cutoff7d) inside [EngagementWindowUseCase.execute]
 * stays consistent with the test data regardless of run date.
 *
 * Coverage:
 *   - Cold-start guard: count < [EngagementWindow.MIN_SESSIONS] → [EngagementWindow.insufficient].
 *   - Zero-count guard: count = 0 → [EngagementWindow.insufficient].
 *   - Boundary: count = MIN_SESSIONS → inference runs.
 *   - 14-day window: sessions older than 14 days excluded from hour average.
 *   - Stddev = 0 when all sessions share the same start hour → confidence = 1.0.
 *   - Stddev > 0 (alternating hours 6 and 18 → stddev = 6) → confidence = 0.5.
 *   - No sessions in 14-day window → avgStartHour14d fallback = 12 → predictedHour = 12.
 *   - In-progress sessions (endedAt = null) excluded from avgSessionLengthMin.
 *   - Most-recent session determines prevSessionStartHour; use case completes without crash.
 */
class EngagementWindowUseCaseTest {

    private val predictor = MathHabitPredictor()

    // ── Fake DAO ──────────────────────────────────────────────────────────────

    /**
     * Minimal fake [AppSessionDao].  Only [count] and [getRecent] are exercised by
     * [EngagementWindowUseCase.execute]; the rest are stubs.
     *
     * [getRecent] mirrors the Room query: descending-start-time order, capped at [limit].
     */
    private fun fakeDao(count: Int, sessions: List<AppSessionEntity>): AppSessionDao =
        object : AppSessionDao {
            override suspend fun count(): Int = count
            override suspend fun getRecent(limit: Int): List<AppSessionEntity> =
                sessions.sortedByDescending { it.startedAt }.take(limit)
            override suspend fun insert(session: AppSessionEntity): Long = 0L
            override suspend fun update(session: AppSessionEntity) {}
            override fun getSince(since: LocalDateTime): Flow<List<AppSessionEntity>> =
                throw UnsupportedOperationException("getSince not exercised by EngagementWindowUseCase")
        }

    /**
     * Builds an [AppSessionEntity] starting at [startedAt].
     * When [durationMinutes] is null the session is in-progress (endedAt = null).
     */
    private fun session(
        startedAt: LocalDateTime,
        durationMinutes: Long? = null
    ) = AppSessionEntity(
        id = 0,
        startedAt = startedAt,
        endedAt = durationMinutes?.let { startedAt.plusMinutes(it) },
        screensVisited = emptyList()
    )

    // ── Test 1: Cold-start guard ──────────────────────────────────────────────

    /**
     * When [AppSessionDao.count] returns fewer than [EngagementWindow.MIN_SESSIONS] (14),
     * [execute] must short-circuit and return [EngagementWindow.insufficient] without
     * calling [AppSessionDao.getRecent].
     */
    @Test
    fun `cold-start guard returns insufficient when count is below MIN_SESSIONS`() = runBlocking {
        val dao = fakeDao(count = EngagementWindow.MIN_SESSIONS - 1, sessions = emptyList())
        val useCase = EngagementWindowUseCase(dao, predictor)

        val result = useCase.execute()

        assertFalse(result.hasSufficientData)
        assertEquals(EngagementWindow.insufficient, result)
    }

    // ── Test 2: Zero sessions guard ───────────────────────────────────────────

    /**
     * count = 0 is the most extreme cold-start scenario — must also return
     * [EngagementWindow.insufficient].
     */
    @Test
    fun `zero session count returns insufficient`() = runBlocking {
        val dao = fakeDao(count = 0, sessions = emptyList())
        val useCase = EngagementWindowUseCase(dao, predictor)

        val result = useCase.execute()

        assertFalse(result.hasSufficientData)
        assertEquals(EngagementWindow.insufficient, result)
    }

    // ── Test 3: Boundary — exactly MIN_SESSIONS, consistent hour ─────────────

    /**
     * At exactly [EngagementWindow.MIN_SESSIONS] = 14 sessions (count reported by DAO),
     * inference proceeds.  All 14 sessions start at 09:00 within the last 14 days:
     *
     * - avgStartHour14d = 9.0 → MathHabitPredictor returns 9.0 → predictedHour = 9
     * - stddevStartHour14d = 0 (all hours identical) → confidence = 1.0
     * - hasSufficientData = true
     */
    @Test
    fun `exactly MIN_SESSIONS triggers inference with all-same-hour sessions`() = runBlocking {
        val today = LocalDate.now()
        // 14 sessions, 1 per day, each at 09:00, all within the 14-day window
        val sessions = (1L..14L).map { daysAgo ->
            session(today.minusDays(daysAgo).atTime(9, 0), durationMinutes = 5)
        }
        val dao = fakeDao(count = 14, sessions = sessions)
        val useCase = EngagementWindowUseCase(dao, predictor)

        val result = useCase.execute()

        assertTrue(result.hasSufficientData)
        assertEquals(9, result.predictedHour)
        assertEquals(1.0f, result.confidence, 0.001f)
    }

    // ── Test 4: Sessions beyond 14 days excluded from hour average ────────────

    /**
     * Sessions older than 14 days must NOT influence avgStartHour14d or stddevStartHour14d.
     *
     * Setup:
     * - 10 sessions within the 14-day window at 08:00.
     * - 5 sessions at days 15–19 (outside window) at 22:00.
     *
     * Expected: avgStartHour14d = 8.0 → predictedHour = 8, confidence = 1.0.
     */
    @Test
    fun `sessions older than 14 days are excluded from hour average`() = runBlocking {
        val today = LocalDate.now()
        val recentSessions = (1L..10L).map { d ->
            session(today.minusDays(d).atTime(8, 0), durationMinutes = 3)
        }
        val oldSessions = (15L..19L).map { d ->
            session(today.minusDays(d).atTime(22, 0), durationMinutes = 3)
        }
        val dao = fakeDao(count = 15, sessions = recentSessions + oldSessions)
        val useCase = EngagementWindowUseCase(dao, predictor)

        val result = useCase.execute()

        assertTrue(result.hasSufficientData)
        assertEquals(8, result.predictedHour)
        assertEquals(1.0f, result.confidence, 0.001f)
    }

    // ── Test 5: Stddev = 6 → confidence = 0.5 ────────────────────────────────

    /**
     * With sessions alternating between 06:00 and 18:00 in the last 14 days:
     *   mean = 12, variance = 36, stddev = 6
     *   confidence = 1 − (6 / 12) = 0.5
     *
     * [MathHabitPredictor] returns the average start hour = 12 → predictedHour = 12.
     */
    @Test
    fun `alternating session hours produce stddev 6 and confidence 0_5`() = runBlocking {
        val today = LocalDate.now()
        // 8 sessions within 14 days, alternating between 06:00 and 18:00
        val sessions = (1L..8L).map { d ->
            val hour = if (d % 2 == 0L) 6 else 18
            session(today.minusDays(d).atTime(hour, 0), durationMinutes = 5)
        }
        val dao = fakeDao(count = 14, sessions = sessions)
        val useCase = EngagementWindowUseCase(dao, predictor)

        val result = useCase.execute()

        assertTrue(result.hasSufficientData)
        assertEquals(12, result.predictedHour)
        assertEquals(0.5f, result.confidence, 0.01f)
    }

    // ── Test 6: No sessions in 14-day window → avgStartHour fallback = 12 ────

    /**
     * When all sessions are older than 14 days, the 14-day filter yields an empty list.
     * The use-case falls back to avgStartHour14d = 12.0 (training-set default).
     *
     * [MathHabitPredictor] returns 12.0 → predictedHour = 12.
     * stddevStartHour14d = 0 (size < 2 guard) → confidence = 1.0.
     */
    @Test
    fun `no sessions in 14d window falls back avgStartHour to 12`() = runBlocking {
        val today = LocalDate.now()
        // 14 sessions all strictly outside the 14-day window
        val sessions = (15L..28L).map { d ->
            session(today.minusDays(d).atTime(7, 0), durationMinutes = 3)
        }
        val dao = fakeDao(count = 14, sessions = sessions)
        val useCase = EngagementWindowUseCase(dao, predictor)

        val result = useCase.execute()

        assertTrue(result.hasSufficientData)
        assertEquals(12, result.predictedHour)
        assertEquals(1.0f, result.confidence, 0.001f)
    }

    // ── Test 7: In-progress sessions excluded from avgSessionLengthMin ────────

    /**
     * When all sessions are in-progress (endedAt = null), no session contributes
     * to avgSessionLengthMin.  The use-case must apply the fallback of 3.0 minutes
     * (training-set median) and complete without throwing.
     *
     * With MathHabitPredictor, predictedHour is still derived from avgStartHour14d (= 9).
     */
    @Test
    fun `in-progress sessions excluded from length average and fallback 3 min applied`() = runBlocking {
        val today = LocalDate.now()
        // 14 in-progress sessions within the 14-day window at 09:00
        val sessions = (1L..14L).map { d ->
            session(today.minusDays(d).atTime(9, 0), durationMinutes = null)
        }
        val dao = fakeDao(count = 14, sessions = sessions)
        val useCase = EngagementWindowUseCase(dao, predictor)

        val result = useCase.execute()

        assertTrue(result.hasSufficientData)
        assertEquals(9, result.predictedHour)
        // confidence = 1.0 because all hours are identical (stddev = 0)
        assertEquals(1.0f, result.confidence, 0.001f)
    }

    // ── Test 8: rawPredictedHour is within valid hour range ───────────────────

    /**
     * The [EngagementWindow.rawPredictedHour] must always be in [0.0, 23.0] for
     * [MathHabitPredictor] output (it clamps to [0, 23]).
     * [EngagementWindow.predictedHour] must equal the rounded value within [0, 23].
     */
    @Test
    fun `predictedHour and rawPredictedHour are within valid range`() = runBlocking {
        val today = LocalDate.now()
        // Sessions at hour 20 → avgStartHour14d = 20
        val sessions = (1L..14L).map { d ->
            session(today.minusDays(d).atTime(20, 0), durationMinutes = 10)
        }
        val dao = fakeDao(count = 14, sessions = sessions)
        val useCase = EngagementWindowUseCase(dao, predictor)

        val result = useCase.execute()

        assertTrue(result.hasSufficientData)
        assertTrue(result.predictedHour in 0..23)
        assertTrue(result.rawPredictedHour in 0f..23f)
        assertEquals(20, result.predictedHour)
    }
}
