package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.domain.ai.ClusterFeatures
import com.example.evolvix.domain.ai.HabitPredictor
import com.example.evolvix.domain.model.BehavioralCluster
import com.example.evolvix.domain.model.HabitCluster
import com.example.evolvix.domain.model.HabitData
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Use Case / Interactor that classifies a habit into one of four K-Means behavioral tiers
 * (Phase 8.4): Effortless Routine, Consistent Effort, Struggling, or Dormant.
 *
 * Responsibility: extract the five [ClusterFeatures] from raw Room data, apply null-
 * substitution for optional analytics using [HabitPredictor.clusterTrainingMedians],
 * check data sufficiency, delegate inference to the injected [HabitPredictor] (Strategy
 * + Dependency Inversion pattern), and return a typed [HabitCluster].
 *
 * Null-substitution contract (mirrors the Python training pipeline):
 *  - routinePrecisionStddev: substitute `trainingMedians[1]` when fewer than
 *    [MIN_PRECISION_COMPLETIONS] completions exist (< 5 completions → stddev undefined).
 *  - procrastinationSkew: substitute 0.0 when null (no significant delay detected).
 *  - resilienceAvgGap: substitute `trainingMedians[4]` when no recovery events are present.
 *
 * Both [TfliteHabitPredictor] and [MathHabitPredictor] always receive a fully-populated
 * [ClusterFeatures] vector — no null checks are needed inside the predictor implementations.
 *
 * @param predictor Strategy implementation of [HabitPredictor]; injectable so
 *                  [MathHabitPredictor] and [TfliteHabitPredictor] are interchangeable.
 */
class BehavioralClusterUseCase(
    private val predictor: HabitPredictor
) {
    companion object {
        /** Minimum completion records before a cluster prediction is shown to the user. */
        private const val MIN_COMPLETIONS = 10

        /** Minimum days of history (two full weeks) required for clustering to be meaningful. */
        private const val MIN_HISTORY_DAYS = 14

        /** Minimum completions needed for routinePrecisionStddev to be defined. */
        private const val MIN_PRECISION_COMPLETIONS = 5

        /** Window in days for the rate30d computation. */
        private const val RATE_WINDOW_DAYS = 30L

        /** Feature-vector index for routinePrecisionStddev in the training_medians array. */
        private const val IDX_ROUTINE_PRECISION = 1

        /** Feature-vector index for resilienceAvgGap in the training_medians array. */
        private const val IDX_RESILIENCE_GAP = 4
    }

    /**
     * Classifies [habit] into a [BehavioralCluster] tier.
     *
     * Algorithm:
     * 1. Guard: return [HabitCluster] with [BehavioralCluster.Dormant] and
     *    `hasSufficientData = false` when completions < [MIN_COMPLETIONS] or
     *    history age < [MIN_HISTORY_DAYS].
     * 2. Compute rate30d: fraction of the last 30 calendar days where a reached
     *    completion was recorded.
     * 3. Extract routinePrecisionStddev, procrastinationSkew, and resilienceAvgGap
     *    from the predictor's analytics methods, substituting medians where null.
     * 4. Delegate to [HabitPredictor.classifyBehavioralCluster].
     * 5. Resolve the returned key to a typed [BehavioralCluster] via [BehavioralCluster.fromKey].
     *
     * @param habit       Domain model of the habit to classify.
     * @param completions All historical completion records for this habit.
     * @param today       Reference date (defaults to system clock; injectable for testing).
     * @return [HabitCluster] with the resolved tier, habitId, and data-sufficiency flag.
     */
    operator fun invoke(
        habit: HabitData,
        completions: List<HabitCompletionEntity>,
        today: LocalDate = LocalDate.now()
    ): HabitCluster {
        // Guard: not enough completions for a meaningful cluster assignment.
        if (completions.size < MIN_COMPLETIONS) {
            return HabitCluster(habit.id, BehavioralCluster.Dormant, hasSufficientData = false)
        }

        // Derive habit age from the earliest completion (conservative underestimate).
        val firstDate = completions.minOf { it.progressUpdate.toLocalDate() }
        val habitAge = ChronoUnit.DAYS.between(firstDate, today).toInt().coerceAtLeast(1)

        if (habitAge < MIN_HISTORY_DAYS) {
            return HabitCluster(habit.id, BehavioralCluster.Dormant, hasSufficientData = false)
        }

        val medians = predictor.clusterTrainingMedians

        // Feature 0 — rate30d: fraction of last-30-days windows where target was reached.
        val cutoff30d = today.minusDays(RATE_WINDOW_DAYS)
        val reached30d = completions.count { c ->
            c.isTargetReached && !c.progressUpdate.toLocalDate().isBefore(cutoff30d)
        }
        val rate30d = (reached30d.toFloat() / RATE_WINDOW_DAYS.toFloat()).coerceIn(0f, 1f)

        // Feature 1 — routinePrecisionStddev: stddev of completion-time deviations.
        // Null when sample is too small; substitute training median in that case.
        val rawPrecision = if (completions.size >= MIN_PRECISION_COMPLETIONS)
            predictor.computeRoutinePrecision(completions)
        else null
        val routinePrecisionStddev = rawPrecision?.toFloat()
            ?: medians.getOrElse(IDX_ROUTINE_PRECISION) { 79.59f }

        // Feature 2 — procrastinationSkew: delay-distribution skewness.
        // 0.0 is a neutral substitute (no measured skew).
        val procrastinationSkew = predictor.computeProcrastination(habit, completions)
            ?.toFloat() ?: 0f

        // Feature 3 — habitAge: calendar days since first recorded completion.
        val habitAgeFeature = habitAge

        // Feature 4 — resilienceAvgGap: average gap (days) between a missed period and
        // the next reached completion. Null when no recovery events exist; substitute median.
        val rawResilience = predictor.computeResilience(habit, completions)
        val resilienceAvgGap = rawResilience?.toFloat()
            ?: medians.getOrElse(IDX_RESILIENCE_GAP) { 4.36f }

        val features = ClusterFeatures(
            rate30d                = rate30d,
            routinePrecisionStddev = routinePrecisionStddev,
            procrastinationSkew    = procrastinationSkew,
            habitAge               = habitAgeFeature,
            resilienceAvgGap       = resilienceAvgGap
        )

        val rawKey = predictor.classifyBehavioralCluster(features)
        val cluster = BehavioralCluster.fromKey(rawKey)
        return HabitCluster(habit.id, cluster, hasSufficientData = true)
    }
}
