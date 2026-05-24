package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.domain.ai.HabitPredictor
import com.example.evolvix.domain.ai.WeeklyForecastFeatures
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.model.WeeklyForecast
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sin

/**
 * Use Case / Interactor that predicts the user's overall habit-completion rate
 * for the next 7 days (Phase 8.3).
 *
 * Responsibility: aggregate cross-habit signals from Room data into the 12-field
 * [WeeklyForecastFeatures] vector, check data sufficiency, delegate inference to the
 * injected [HabitPredictor] (Strategy + Dependency Inversion), and wrap the raw
 * regression output in a [WeeklyForecast] with direction and confidence.
 *
 * This is a **user-level** (not per-habit) predictor — the feature vector and
 * output represent the aggregate of all active habits in a single week window.
 *
 * @param predictor Strategy implementation of [HabitPredictor]; injectable so
 *                  [MathHabitPredictor] and [TfliteHabitPredictor] are interchangeable.
 */
class WeeklyForecastUseCase(
    private val predictor: HabitPredictor
) {
    companion object {
        /** Minimum active habits required for a meaningful prediction. */
        private const val MIN_HABITS = 2

        /** Minimum days of history (one full week) required before forecasting. */
        private const val MIN_HISTORY_DAYS = 7

        /** Window in days used to compute per-weekday rates (4 full weeks). */
        private const val WEEKDAY_WINDOW_DAYS = 28L

        /** Days of history that map to confidence = 1.0 (4 weeks). */
        private const val FULL_CONFIDENCE_DAYS = 28
    }

    /**
     * Computes a [WeeklyForecast] for the current user given all active [habits]
     * and their [completions].
     *
     * Algorithm:
     * 1. Guard: return a zero forecast with [WeeklyForecast.hasSufficientData] = false
     *    when there are fewer than [MIN_HABITS] active habits or less than
     *    [MIN_HISTORY_DAYS] of completion history.
     * 2. Compute [lastWeekRate]: fraction of (habit × day) pairs where the target
     *    was reached in the trailing 7 days.
     * 3. Compute per-weekday rates over the last [WEEKDAY_WINDOW_DAYS] days.
     * 4. Compute [avgCurrentStreak] from [currentStreaks].
     * 5. Encode the current week-of-year as sin/cos (seasonality).
     * 6. Delegate to [HabitPredictor.predictWeeklyRate] and wrap in [WeeklyForecast].
     *
     * @param habits         Domain models of all active (non-paused) habits.
     * @param completions    All historical completion records across all habits.
     * @param currentStreaks Map of habitId → current streak (pre-computed by ViewModel
     *                       via [CalculateStreakUseCase] to avoid recomputing here).
     * @param today          Reference date (defaults to system clock; injectable for testing).
     * @return [WeeklyForecast] with predicted rate, direction, confidence, and
     *         data-sufficiency flag.
     */
    operator fun invoke(
        habits: List<HabitData>,
        completions: List<HabitCompletionEntity>,
        currentStreaks: Map<Int, Int>,
        today: LocalDate = LocalDate.now()
    ): WeeklyForecast {
        val habitCount = habits.size

        // Guard: too few habits to produce a meaningful aggregate signal.
        if (habitCount < MIN_HABITS) {
            return insufficientDataResult()
        }

        // Derive history depth from the earliest completion across all habits.
        if (completions.isEmpty()) return insufficientDataResult()
        val firstDate = completions.minOf { it.progressUpdate.toLocalDate() }
        val daysOfHistory = ChronoUnit.DAYS.between(firstDate, today).toInt()
        if (daysOfHistory < MIN_HISTORY_DAYS) return insufficientDataResult()

        // Build a set of (habitId, date) pairs where the target was reached.
        val reachedSet: Set<Pair<Int, LocalDate>> = completions
            .filter { it.isTargetReached }
            .map { it.habitId to it.progressUpdate.toLocalDate() }
            .toSet()

        val lastWeekRate = computeWindowRate(reachedSet, habits, today, windowDays = 7)

        val weekdayRates = computeWeekdayRates(reachedSet, habits, today)

        // Average current streak — proxy for momentum; capped at 200 by training data range.
        val avgStreak = if (currentStreaks.isEmpty()) 0f else
            currentStreaks.values.map { it.toFloat() }.average().toFloat()

        // Seasonality: encode current week-of-year as sin/cos pair (same formula as Python).
        val weekOfYear = today.get(java.time.temporal.WeekFields.ISO.weekOfYear())
        val weekSin = sin(2.0 * PI * weekOfYear / 52.0).toFloat()
        val weekCos = cos(2.0 * PI * weekOfYear / 52.0).toFloat()

        val features = WeeklyForecastFeatures(
            lastWeekRate = lastWeekRate,
            avgCurrentStreak = avgStreak.coerceIn(0f, 200f),
            habitCount = habitCount.coerceIn(1, 30),
            rateMon = weekdayRates[0],
            rateTue = weekdayRates[1],
            rateWed = weekdayRates[2],
            rateThu = weekdayRates[3],
            rateFri = weekdayRates[4],
            rateSat = weekdayRates[5],
            rateSun = weekdayRates[6],
            weekOfYearSin = weekSin,
            weekOfYearCos = weekCos
        )

        val predicted = predictor.predictWeeklyRate(features)

        // Confidence: ratio of actual history depth to the "full" 4-week horizon.
        val confidence = min(daysOfHistory.toFloat() / FULL_CONFIDENCE_DAYS, 1f)

        return WeeklyForecast(
            predictedRate = predicted,
            lastWeekRate = lastWeekRate,
            direction = WeeklyForecast.directionFor(predicted, lastWeekRate),
            confidence = confidence,
            hasSufficientData = true
        )
    }

    /**
     * Returns the fraction of (habit × day) pairs where the target was reached
     * within the last [windowDays] days ending on (but not including) [today].
     */
    private fun computeWindowRate(
        reachedSet: Set<Pair<Int, LocalDate>>,
        habits: List<HabitData>,
        today: LocalDate,
        windowDays: Int
    ): Float {
        if (habits.isEmpty()) return 0f
        var reached = 0
        val days = (1..windowDays).map { offset -> today.minusDays(offset.toLong()) }
        for (day in days) {
            for (habit in habits) {
                if ((habit.id to day) in reachedSet) reached++
            }
        }
        return reached.toFloat() / (habits.size * windowDays)
    }

    /**
     * Computes per-weekday completion rates (Mon=index 0 … Sun=index 6) over the
     * last [WEEKDAY_WINDOW_DAYS] days.
     *
     * For each weekday w, the rate is:
     *   (sum of habits with target reached on each occurrence of w) /
     *   (number of occurrences of w in the window × habitCount)
     *
     * Falls back to 0f for a weekday if it has not yet occurred in the window.
     */
    private fun computeWeekdayRates(
        reachedSet: Set<Pair<Int, LocalDate>>,
        habits: List<HabitData>,
        today: LocalDate
    ): FloatArray {
        // dayOfWeek value: Mon=1..Sun=7; store as index 0..6.
        val reachedPerWeekday = IntArray(7)
        val occurrencePerWeekday = IntArray(7)

        for (offset in 1..WEEKDAY_WINDOW_DAYS) {
            val day = today.minusDays(offset)
            val wIdx = day.dayOfWeek.value - 1   // 0 = Mon, 6 = Sun
            occurrencePerWeekday[wIdx]++
            for (habit in habits) {
                if ((habit.id to day) in reachedSet) reachedPerWeekday[wIdx]++
            }
        }

        return FloatArray(7) { wIdx ->
            val occ = occurrencePerWeekday[wIdx]
            if (occ == 0 || habits.isEmpty()) 0f
            else reachedPerWeekday[wIdx].toFloat() / (occ * habits.size)
        }
    }

    /** Returns a safe zero-value [WeeklyForecast] used when data is insufficient. */
    private fun insufficientDataResult() = WeeklyForecast(
        predictedRate = 0f,
        lastWeekRate = 0f,
        direction = WeeklyForecast.Direction.FLAT,
        confidence = 0f,
        hasSufficientData = false
    )
}
