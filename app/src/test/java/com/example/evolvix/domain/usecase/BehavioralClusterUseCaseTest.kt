package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.data.model.HabitSkipEntity
import com.example.evolvix.data.model.SkipReason
import com.example.evolvix.domain.ai.ClusterFeatures
import com.example.evolvix.domain.ai.HabitPredictor
import com.example.evolvix.domain.ai.MathHabitPredictor
import com.example.evolvix.domain.model.BehavioralCluster
import com.example.evolvix.domain.model.HabitData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Unit tests for [BehavioralClusterUseCase] (R4 coverage).
 *
 * Uses [MathHabitPredictor] as the injected [com.example.evolvix.domain.ai.HabitPredictor]
 * for cluster-boundary and guard tests, and a [CapturingPredictor] sub-class for the
 * skip-rate feature derivation tests that need to inspect the [ClusterFeatures] vector
 * actually passed to the predictor. No Android SDK or TFLite involved — pure JVM JUnit.
 *
 * [MathHabitPredictor.classifyBehavioralCluster] threshold chain (rate30d, first match wins):
 *   rate30d ≥ 0.85 → "effortless_routine"
 *   rate30d ≥ 0.55 → "consistent_effort"
 *   rate30d ≥ 0.20 → "struggling"
 *   rate30d  < 0.20 → "dormant"
 *
 * Coverage:
 *   Guard: fewer than 10 completions     → Dormant / hasSufficientData=false.
 *   Guard: habit younger than 14 days    → Dormant / hasSufficientData=false.
 *   rate30d = 1.00  → EffortlessRoutine  (boundary ≥ 0.85).
 *   rate30d = 0.60  → ConsistentEffort   (boundary ≥ 0.55).
 *   rate30d = 0.30  → Struggling         (boundary ≥ 0.20).
 *   rate30d = 0.10  → Dormant            (hasSufficientData=true).
 *   Null-substitution: no recovery gaps (resilienceAvgGap=null) does not crash.
 *   [R4] Voluntary skip types counted only in voluntarySkipRate30d.
 *   [R4] Involuntary skip types (SICK/TRAVELING) counted only in involuntarySkipRate30d.
 *   [R4] Skips for a different habitId are excluded.
 *   [R4] Skips older than 30 days are excluded.
 *   [R4] Empty skips list produces zero rates.
 */
class BehavioralClusterUseCaseTest {

    private lateinit var useCase: BehavioralClusterUseCase
    private lateinit var capturingPredictor: CapturingPredictor

    // Fixed reference date — tests are deterministic regardless of when they run.
    private val today = LocalDate.of(2025, 6, 1)

    private val habit = HabitData(
        id = 1, name = "Run", currentCount = 0,
        frequency = HabitFrequency.Daily, target = 1
    )

    @Before
    fun setUp() {
        useCase = BehavioralClusterUseCase(predictor = MathHabitPredictor())
        capturingPredictor = CapturingPredictor()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns a reached [HabitCompletionEntity] for habit 1, [daysAgo] days before [today]. */
    private fun completedAt(daysAgo: Long) = HabitCompletionEntity(
        habitId = 1,
        progressUpdate = today.minusDays(daysAgo).atTime(9, 0),
        isTargetReached = true
    )

    /**
     * Returns [n] consecutive daily completions: today, yesterday, …, [n-1] days ago.
     * Optionally offset by [startDaysAgo] so older history can be generated.
     */
    private fun buildCompletions(n: Int, startDaysAgo: Long = 0): List<HabitCompletionEntity> =
        (0 until n).map { i -> completedAt(startDaysAgo + i) }

    /** Returns a [HabitSkipEntity] for [habitId] at [daysAgo] days before [today]. */
    private fun skippedAt(daysAgo: Long, reason: SkipReason, habitId: Int = 1) =
        HabitSkipEntity(
            habitId = habitId,
            skippedAt = today.minusDays(daysAgo).atTime(9, 0),
            reason = reason
        )

    // ── Sufficiency guards ────────────────────────────────────────────────────

    @Test
    fun `returns Dormant with hasSufficientData false when fewer than 10 completions`() {
        // 5 completions — the MIN_COMPLETIONS = 10 guard fires immediately.
        val completions = (1L..5L).map { completedAt(it) }

        val result = useCase(habit, completions, today = today)

        assertFalse(result.hasSufficientData)
        assertEquals(BehavioralCluster.Dormant, result.cluster)
    }

    @Test
    fun `returns Dormant with hasSufficientData false when habit is younger than 14 days`() {
        // 10 completions but all within the last 13 days → habitAge = 13 < MIN_HISTORY_DAYS (14).
        val completions = (0L..9L).map { completedAt(it) }

        val result = useCase(habit, completions, today = today)

        assertFalse(result.hasSufficientData)
        assertEquals(BehavioralCluster.Dormant, result.cluster)
    }

    // ── rate30d → cluster boundary mapping ───────────────────────────────────

    @Test
    fun `rate30d equals 1 0 maps to EffortlessRoutine`() {
        // 30 consecutive reached completions → all 30 fall within the 30-day window.
        // rate30d = 30/30 = 1.0 ≥ 0.85 → EffortlessRoutine.
        val completions = buildCompletions(n = 30, startDaysAgo = 0)

        val result = useCase(habit, completions, today = today)

        assertEquals(BehavioralCluster.EffortlessRoutine, result.cluster)
        assertTrue(result.hasSufficientData)
    }

    @Test
    fun `rate30d equals 0 60 maps to ConsistentEffort`() {
        // 18 reached in last 30 days → rate30d = 18/30 = 0.60, in [0.55, 0.85).
        val completions = buildCompletions(n = 18, startDaysAgo = 0)

        val result = useCase(habit, completions, today = today)

        assertEquals(BehavioralCluster.ConsistentEffort, result.cluster)
        assertTrue(result.hasSufficientData)
    }

    @Test
    fun `rate30d equals 0 30 maps to Struggling`() {
        // 9 recent reached + 6 older completions (for habitAge ≥ 14).
        // rate30d = 9/30 = 0.30, in [0.20, 0.55) → Struggling.
        val recent = buildCompletions(n = 9, startDaysAgo = 0)
        val older  = buildCompletions(n = 6, startDaysAgo = 30)

        val result = useCase(habit, recent + older, today = today)

        assertEquals(BehavioralCluster.Struggling, result.cluster)
        assertTrue(result.hasSufficientData)
    }

    @Test
    fun `rate30d equals 0 10 maps to Dormant with hasSufficientData true`() {
        // 3 recent reached + 7 older completions (for MIN_COMPLETIONS=10 and habitAge ≥ 14).
        // rate30d = 3/30 = 0.10 < 0.20 → Dormant (data is sufficient; low engagement).
        val recent = buildCompletions(n = 3, startDaysAgo = 0)
        val older  = buildCompletions(n = 7, startDaysAgo = 30)

        val result = useCase(habit, recent + older, today = today)

        assertEquals(BehavioralCluster.Dormant, result.cluster)
        assertTrue(result.hasSufficientData)
    }

    // ── Null-substitution: no crash when analytics return null ────────────────

    @Test
    fun `no recovery gaps resilienceAvgGap null does not cause crash`() {
        // 30 consecutive daily completions → no missed periods → computeResilience returns null.
        // BehavioralClusterUseCase must substitute the training median without throwing.
        val completions = buildCompletions(n = 30, startDaysAgo = 0)

        val result = useCase(habit, completions, today = today)

        // The use case must succeed; EffortlessRoutine is expected for rate30d = 1.0.
        assertNotNull(result)
        assertEquals(BehavioralCluster.EffortlessRoutine, result.cluster)
    }

    // ── Skip-rate feature derivation (R4) ────────────────────────────────────

    /** Returns a [BehavioralClusterUseCase] wired to [capturingPredictor]. */
    private fun useCaseWithCapturing() = BehavioralClusterUseCase(capturingPredictor)

    /** 30 consecutive reached completions — passes all guards, rate30d = 1.0. */
    private fun sufficientCompletions() = buildCompletions(n = 30, startDaysAgo = 0)

    @Test
    fun `voluntary skips are counted only in voluntarySkipRate30d`() {
        // 6 TOO_TIRED skips in the last 6 days — all voluntary (isInvoluntary = false).
        val skips = (1L..6L).map { skippedAt(it, SkipReason.TOO_TIRED) }

        useCaseWithCapturing()(habit, sufficientCompletions(), skips, today)

        val f = capturingPredictor.lastFeatures!!
        assertEquals(6f / 30f, f.voluntarySkipRate30d,   0.001f)
        assertEquals(0f,        f.involuntarySkipRate30d, 0.001f)
    }

    @Test
    fun `involuntary skips SICK and TRAVELING are counted only in involuntarySkipRate30d`() {
        // 2 SICK + 1 TRAVELING = 3 involuntary skips; none voluntary.
        val skips = listOf(
            skippedAt(1, SkipReason.SICK),
            skippedAt(2, SkipReason.TRAVELING),
            skippedAt(3, SkipReason.SICK)
        )

        useCaseWithCapturing()(habit, sufficientCompletions(), skips, today)

        val f = capturingPredictor.lastFeatures!!
        assertEquals(3f / 30f, f.involuntarySkipRate30d, 0.001f)
        assertEquals(0f,        f.voluntarySkipRate30d,  0.001f)
    }

    @Test
    fun `skips for a different habitId are excluded from both rates`() {
        // 10 TOO_BUSY skips belonging to habit 2, not habit 1 — must not be counted.
        val skips = (1L..10L).map { skippedAt(it, SkipReason.TOO_BUSY, habitId = 2) }

        useCaseWithCapturing()(habit, sufficientCompletions(), skips, today)

        val f = capturingPredictor.lastFeatures!!
        assertEquals(0f, f.voluntarySkipRate30d,   0.001f)
        assertEquals(0f, f.involuntarySkipRate30d, 0.001f)
    }

    @Test
    fun `skips older than 30 days are excluded`() {
        // Skips on days 31–40 are before the 30-day cutoff (today-30 at midnight) → excluded.
        val skips = (31L..40L).map { skippedAt(it, SkipReason.TOO_TIRED) }

        useCaseWithCapturing()(habit, sufficientCompletions(), skips, today)

        val f = capturingPredictor.lastFeatures!!
        assertEquals(0f, f.voluntarySkipRate30d,   0.001f)
        assertEquals(0f, f.involuntarySkipRate30d, 0.001f)
    }

    @Test
    fun `empty skips list produces zero rates for both features`() {
        useCaseWithCapturing()(habit, sufficientCompletions(), emptyList(), today)

        val f = capturingPredictor.lastFeatures!!
        assertEquals(0f, f.voluntarySkipRate30d,   0.001f)
        assertEquals(0f, f.involuntarySkipRate30d, 0.001f)
    }

    // ── Test doubles ──────────────────────────────────────────────────────────

    /**
     * Implements [HabitPredictor] via Kotlin `by` delegation to a [MathHabitPredictor]
     * instance, overriding only [classifyBehavioralCluster] to record the last
     * [ClusterFeatures] vector passed to it. All other analytics methods (routinePrecision,
     * resilience, procrastination, etc.) delegate to the [MathHabitPredictor] so the
     * full use-case pipeline runs end-to-end without mocking.
     *
     * This acts as a lightweight Spy in the classical test-double taxonomy.
     * Delegation via `by` is used because [MathHabitPredictor] is final.
     */
    private class CapturingPredictor(
        private val delegate: HabitPredictor = MathHabitPredictor()
    ) : HabitPredictor by delegate {
        var lastFeatures: ClusterFeatures? = null

        override fun classifyBehavioralCluster(features: ClusterFeatures): String {
            lastFeatures = features
            return delegate.classifyBehavioralCluster(features)
        }
    }
}
