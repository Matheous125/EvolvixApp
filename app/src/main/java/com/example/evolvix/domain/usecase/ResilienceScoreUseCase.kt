package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitSkipEntity
import com.example.evolvix.domain.ai.HabitPredictor
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.model.ResilienceScore

/**
 * Use Case / Interactor that measures how quickly a user recovers after missing a habit.
 *
 * Responsibility: delegate the gap-averaging computation to
 * [HabitPredictor.computeResilience], then map the raw `Double?` (average missed
 * periods per recovery event) into the richer [ResilienceScore] domain model.
 * Callers (e.g. [StatisticsViewModel]) never interact with the math directly —
 * this is the Use Case / Interactor pattern.
 *
 * The underlying algorithm (in [MathHabitPredictor]) walks the sorted list of
 * "target reached" period keys and measures gaps between consecutive ones.
 * Each gap > 1 period is a "recovery event"; the average gap length is returned.
 *
 * **Phase 9.5 — involuntary-skip compensation:** Skips whose
 * [com.example.evolvix.data.model.SkipReason.isInvoluntary] flag is true
 * ([com.example.evolvix.data.model.SkipReason.SICK] and
 * [com.example.evolvix.data.model.SkipReason.TRAVELING]) are injected as virtual
 * "target reached" completions before the resilience computation. This prevents
 * illness or travel from inflating the user's average recovery time — a gap caused
 * by an involuntary reason should not penalize the resilience score.
 *
 * Qualification tiers for [ResilienceScore.Rating]:
 * - **EXCELLENT** — avg < 1.5 periods  (near-instant recovery)
 * - **GOOD**      — avg < 3.0 periods  (recovers within a few periods)
 * - **MODERATE**  — avg < 7.0 periods  (about a week for daily habits)
 * - **LOW**       — avg ≥ 7.0 periods  (prolonged gaps before resuming)
 *
 * Returns `null` when [HabitPredictor.computeResilience] reports insufficient data
 * (no observable recovery events), signaling the UI to show a "not enough data" placeholder.
 *
 * @param predictor Strategy implementation of [HabitPredictor]; injectable so
 *                  [MathHabitPredictor] and [TfliteHabitPredictor] are interchangeable.
 */
class ResilienceScoreUseCase(
    private val predictor: HabitPredictor
) {

    companion object {
        private const val THRESHOLD_EXCELLENT = 1.5  // avg missed periods
        private const val THRESHOLD_GOOD = 3.0
        private const val THRESHOLD_MODERATE = 7.0
    }

    /**
     * Computes a [ResilienceScore] for [habit] given its [completions] history.
     *
     * @param habit             Domain model of the habit (provides frequency for period math).
     * @param completions       All historical completion records for this habit.
     * @param involuntarySkips  Skip records classified as [com.example.evolvix.data.model.SkipReason.SICK]
     *                          or [com.example.evolvix.data.model.SkipReason.TRAVELING] from
     *                          [HabitSkipDao]. Each record whose [HabitSkipEntity.reason] has
     *                          [com.example.evolvix.data.model.SkipReason.isInvoluntary] = true is
     *                          injected as a virtual "target reached" completion so the gap-averaging
     *                          algorithm ignores those periods. Defaults to empty (no compensation).
     * @return [ResilienceScore] with avg missed periods, event count, and qualitative rating,
     *         or `null` if there are no recovery events to measure.
     */
    operator fun invoke(
        habit: HabitData,
        completions: List<HabitCompletionEntity>,
        involuntarySkips: List<HabitSkipEntity> = emptyList()
    ): ResilienceScore? {
        // Inject each involuntary skip as a virtual "target reached" completion so the
        // resilience algorithm treats that period as fulfilled rather than a gap.
        val virtualCompletions: List<HabitCompletionEntity> = involuntarySkips
            .filter { it.reason.isInvoluntary }
            .map { skip ->
                HabitCompletionEntity(
                    habitId = skip.habitId,
                    progressUpdate = skip.skippedAt,
                    isTargetReached = true
                )
            }

        val effectiveCompletions = completions + virtualCompletions
        val avgMissed = predictor.computeResilience(habit, effectiveCompletions) ?: return null

        val rating = when {
            avgMissed < THRESHOLD_EXCELLENT -> ResilienceScore.Rating.EXCELLENT
            avgMissed < THRESHOLD_GOOD      -> ResilienceScore.Rating.GOOD
            avgMissed < THRESHOLD_MODERATE  -> ResilienceScore.Rating.MODERATE
            else                            -> ResilienceScore.Rating.LOW
        }

        // Count distinct recovery events: each gap in the reached-period sequence is one event.
        // We approximate this as the number of gaps the predictor found, which is equivalent
        // to (distinct reached periods - 1) only when every consecutive pair had a gap.
        // The safe lower bound is 1 when avgMissed is non-null (at least one gap was observed).
        val recoveryEventCount = if (avgMissed > 0.0) {
            effectiveCompletions
                .filter { it.isTargetReached }
                .map { it.progressUpdate.toLocalDate() }
                .distinct()
                .count()
                .coerceAtLeast(1)
        } else 0

        return ResilienceScore(
            avgMissedPeriods = avgMissed,
            recoveryEventCount = recoveryEventCount,
            rating = rating
        )
    }
}
