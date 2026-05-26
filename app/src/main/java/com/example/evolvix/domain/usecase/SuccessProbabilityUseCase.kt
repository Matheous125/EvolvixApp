package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.domain.ai.HabitFeatures
import com.example.evolvix.domain.ai.HabitPredictor
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.model.SuccessPrediction
import com.example.evolvix.domain.model.SpilloverPair
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Use Case / Interactor that estimates the probability of a habit being completed
 * successfully on the current day and hour.
 *
 * Responsibility: extract the nine explicit feature values from raw domain objects and
 * delegate probability computation to [HabitPredictor] (Strategy + Dependency Inversion
 * pattern). This separation means the ViewModel never reaches into prediction math directly.
 *
 * Input features computed here (order matches [HabitFeatures] and Python FEATURE_COLUMNS):
 * - **dayOfWeek**                — ISO day-of-week of [now] (1 = Monday, 7 = Sunday).
 * - **hourOfDay**                — Hour of [now] in 24-hour format (0–23).
 * - **currentStreak**            — Unbroken streak via [CalculateStreakUseCase].
 * - **completionRateLast7Days**  — Fraction of last 7 calendar days target was reached.
 * - **habitAge**                 — Days since first ever recorded completion.
 * - **hoursSinceLastCompletion** — Hours since most recent completion, capped at 336 h.
 * - **targetCount**              — Habit's daily target value.
 * - **recentAvgDifficulty**      — Avg perceivedDifficulty over last 14 completions (R6).
 * - **spilloverLiftAggregate**   — Sum of BOOST lift deltas from [SpilloverUseCase] where this
 *                                   habit is the target (R7); 0f when use case is not injected.
 *
 * @param predictor          Strategy implementation of [HabitPredictor]; injectable so
 *                           [MathHabitPredictor] and [TfliteHabitPredictor] are interchangeable.
 * @param calculateStreak    Shared streak use case — reused to avoid duplicate logic.
 * @param spilloverUseCase   Optional [SpilloverUseCase] for R7 aggregate; null disables the
 *                           feature (defaults to 0f) for backward-compatible call sites.
 */
class SuccessProbabilityUseCase(
    private val predictor: HabitPredictor,
    private val calculateStreak: CalculateStreakUseCase = CalculateStreakUseCase(),
    private val spilloverUseCase: SpilloverUseCase? = null
) {

    /** R7: caches [SpilloverUseCase] results per day so multiple [invoke] calls within
     *  one ViewModel update cycle do not recompute spillover for the same date. */
    private val spilloverCache: MutableMap<LocalDate, List<SpilloverPair>> = mutableMapOf()

    /**
     * Computes a [SuccessPrediction] for [habit] given its [completions] history.
     *
     * @param habit          Domain model of the habit to evaluate.
     * @param completions    All historical completion records for this habit.
     * @param now            Reference timestamp (defaults to system clock; injectable for testing).
     * @param allHabits      All active habits — needed by [spilloverUseCase] to find pairs (R7).
     *                       Defaults to empty list (disables R7 aggregate).
     * @param allCompletions All completions across every habit — fed to [spilloverUseCase] (R7).
     *                       Defaults to empty list (disables R7 aggregate).
     * @return [SuccessPrediction] containing the probability and all feature values used.
     */
    operator fun invoke(
        habit: HabitData,
        completions: List<HabitCompletionEntity>,
        now: LocalDateTime = LocalDateTime.now(),
        allHabits: List<HabitData> = emptyList(),
        allCompletions: List<HabitCompletionEntity> = emptyList()
    ): SuccessPrediction {
        val today = now.toLocalDate()

        // Feature 1: day of week (1 = Mon, 7 = Sun) — ISO standard used throughout the project.
        val dayOfWeek = now.dayOfWeek.value

        // Feature 2: hour of day (0–23) — determines morning/evening bias in predictor.
        val hourOfDay = now.hour

        // Feature 3: current streak — delegate to the canonical streak use case so there
        // is a single source of truth for streak arithmetic across the whole codebase.
        val currentStreak = calculateStreak(completions, habit.frequency, today).current

        // Feature 4: recent-week completion rate — fraction of the last 7 calendar days
        // on which the habit reached its target at least once.
        val sevenDaysAgo = today.minusDays(7)
        val recentCompletedDays = completions
            .filter { it.isTargetReached && it.progressUpdate.toLocalDate() >= sevenDaysAgo }
            .map { it.progressUpdate.toLocalDate() }
            .toSet()
            .size
        val recentWeekRate = recentCompletedDays.toFloat() / 7f

        // Feature 5: habit age — days since first ever completion; 0 when there is no history.
        // Older habits tend to be more stable, which the predictor factors in.
        val habitAgeInDays = completions
            .minOfOrNull { it.progressUpdate.toLocalDate() }
            ?.let { ChronoUnit.DAYS.between(it, today) }
            ?: 0L

        // Feature 6: hours since last completion — captures momentum loss for at-risk habits.
        // Capped at 336 h (14 days) to match the training distribution upper bound.
        val hoursSinceLastCompletion = completions
            .maxOfOrNull { it.progressUpdate }
            ?.let { ChronoUnit.HOURS.between(it, now).coerceAtMost(336L) }
            ?: 0L

        // Feature 7 (R6): rolling avg of perceivedDifficulty over the last 14 completions.
        // Null ratings are skipped; defaults to 3.0 (neutral midpoint) when none are rated.
        val recentAvgDifficulty: Float = completions
            .sortedByDescending { it.progressUpdate }
            .take(14)
            .mapNotNull { it.perceivedDifficulty }
            .map { it.toFloat() }
            .let { rated -> if (rated.isEmpty()) 3.0f else rated.average().toFloat() }

        // Feature 9 (R7): spillover lift — sum of BOOST liftDelta values where this habit
        // is the target (habitBName == habit.name). Results cached by date so multiple
        // invoke() calls in one ViewModel cycle do not re-run SpilloverUseCase.
        val spilloverLiftAggregate: Float = if (spilloverUseCase != null && allHabits.size >= 2) {
            val pairs = spilloverCache.getOrPut(today) {
                spilloverUseCase.invoke(allHabits, allCompletions, today)
            }
            pairs
                .filter { it.habitBName == habit.name && it.direction == SpilloverPair.Direction.BOOST }
                .sumOf { it.liftDelta.toDouble() }
                .toFloat()
                .coerceIn(-0.5f, 0.5f)
        } else 0f

        // Build the feature vector — field order must match HabitFeatures.toFloatArray()
        // and the Python FEATURE_COLUMNS list in generate_success_data.py.
        val features = HabitFeatures(
            dayOfWeek = dayOfWeek,
            hourOfDay = hourOfDay,
            currentStreak = currentStreak,
            completionRateLast7Days = recentWeekRate,
            habitAge = habitAgeInDays.toInt(),
            hoursSinceLastCompletion = hoursSinceLastCompletion.toInt(),
            targetCount = habit.target,
            recentAvgDifficulty = recentAvgDifficulty,
            spilloverLiftAggregate = spilloverLiftAggregate  // R7
        )

        // Delegate to predictor via predictSuccess(HabitFeatures) — routes through
        // TfliteHabitPredictor → actual TFLite model inference (Strategy pattern).
        val probability = predictor.predictSuccess(features)

        return SuccessPrediction(
            probability = probability,
            dayOfWeek = dayOfWeek,
            hourOfDay = hourOfDay,
            currentStreak = currentStreak,
            recentWeekRate = recentWeekRate,
            habitAgeInDays = habitAgeInDays
        )
    }
}
