package com.example.evolvix.domain.usecase

import com.example.evolvix.data.local.AppSessionDao
import com.example.evolvix.domain.ai.EngagementWindowFeatures
import com.example.evolvix.domain.ai.HabitPredictor
import com.example.evolvix.domain.model.EngagementWindow
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.sqrt

/**
 * Computes the [EngagementWindow] prediction for the current user (Phase 9.6).
 *
 * **Responsibility:** Bridge between raw session data ([AppSessionDao]) and the
 * TFLite model ([HabitPredictor.predictEngagementHour]).  Exactly one public entry
 * point — [execute] — keeps the use-case contract minimal and easy to explain in a
 * CS thesis defence.
 *
 * **Algorithm:**
 *  1. Check [AppSessionDao.count] — if fewer than [EngagementWindow.MIN_SESSIONS]
 *     rows exist, return [EngagementWindow.insufficient] immediately (cold-start guard).
 *  2. Fetch the 100 most-recent sessions from the DB.
 *  3. Derive the eight scalar features required by the scaler + TFLite model.
 *  4. Pass the [EngagementWindowFeatures] vector to [HabitPredictor.predictEngagementHour].
 *  5. Wrap the raw float prediction in an [EngagementWindow] domain object.
 *
 * **Thread safety:** [execute] is a `suspend` function — all Room calls are inherently
 * safe on the calling coroutine context (typically [kotlinx.coroutines.Dispatchers.IO]
 * via `viewModelScope.launch`).
 *
 * **Pattern:** Use Case (Clean Architecture) — orchestrates one business operation,
 * depends only on interfaces/DAOs, has no Android platform imports.
 *
 * @param dao       Thin Room DAO for reading [com.example.evolvix.data.model.AppSessionEntity].
 * @param predictor Strategy-pattern predictor — either [com.example.evolvix.domain.ai.TfliteHabitPredictor]
 *                  (ML) or [com.example.evolvix.domain.ai.MathHabitPredictor] (fallback).
 */
class EngagementWindowUseCase(
    private val dao: AppSessionDao,
    private val predictor: HabitPredictor
) {

    /**
     * Executes the engagement-window prediction.
     *
     * Returns [EngagementWindow.insufficient] when there are fewer than
     * [EngagementWindow.MIN_SESSIONS] recorded sessions (cold-start guard).
     * Otherwise derives the feature vector and returns a fully populated
     * [EngagementWindow] with [EngagementWindow.hasSufficientData] = true.
     */
    suspend fun execute(): EngagementWindow {
        // ── Cold-start guard ─────────────────────────────────────────────────────
        if (dao.count() < EngagementWindow.MIN_SESSIONS) {
            return EngagementWindow.insufficient
        }

        val sessions = dao.getRecent(100)
        val today = LocalDate.now()

        // ── Feature 3 & 4: avg / stddev of session-start hour over last 14 days ─
        val cutoff14d = today.minusDays(14)
        val startHours14d = sessions
            .filter { !it.startedAt.toLocalDate().isBefore(cutoff14d) }
            .map { it.startedAt.hour.toFloat() }

        val avgStartHour14d = if (startHours14d.isEmpty()) {
            12f
        } else {
            startHours14d.average().toFloat()
        }

        val stddevStartHour14d = if (startHours14d.size < 2) {
            0f
        } else {
            val mean = startHours14d.average()
            sqrt(startHours14d.map { (it - mean) * (it - mean) }.average()).toFloat()
        }

        // ── Feature 5: session count in last 7 days ───────────────────────────────
        val cutoff7d = today.minusDays(7)
        val sessionCount7d = sessions.count {
            !it.startedAt.toLocalDate().isBefore(cutoff7d)
        }

        // ── Feature 6: average session length in minutes (completed sessions only) ─
        val completedSessions = sessions.filter { it.endedAt != null }
        val avgSessionLengthMin = if (completedSessions.isEmpty()) {
            3f  // training-set median fallback (see generate_engagement_window_data.py)
        } else {
            completedSessions
                .map { ChronoUnit.SECONDS.between(it.startedAt, it.endedAt!!).toFloat() / 60f }
                .average()
                .toFloat()
                .coerceIn(0.5f, 60f)
        }

        // ── Feature 7: days since first recorded session ──────────────────────────
        val firstSessionDate = sessions.minByOrNull { it.startedAt }?.startedAt?.toLocalDate()
        val daysSinceFirst = if (firstSessionDate != null) {
            ChronoUnit.DAYS.between(firstSessionDate, today).toInt().coerceAtLeast(1)
        } else 1

        // ── Feature 8: start hour of the most recent session ─────────────────────
        val prevSessionHour = sessions
            .maxByOrNull { it.startedAt }
            ?.startedAt?.hour?.toFloat()
            ?: 12f  // training-set default when no prior session exists

        // ── Feature 1 & 2: day-of-week and weekend flag (current day) ────────────
        // LocalDate.dayOfWeek.value: 1=Mon … 7=Sun → shift to 0-based (Mon=0 … Sun=6)
        val dayOfWeek = today.dayOfWeek.value - 1
        val isWeekend = if (dayOfWeek >= 5) 1 else 0

        // ── Build feature vector ─────────────────────────────────────────────────
        val features = EngagementWindowFeatures(
            dayOfWeek = dayOfWeek,
            isWeekend = isWeekend,
            recentAvgStartHour14d = avgStartHour14d,
            stddevStartHour14d = stddevStartHour14d,
            sessionCountLast7d = sessionCount7d,
            avgSessionLengthMin = avgSessionLengthMin,
            daysSinceFirstSession = daysSinceFirst,
            prevSessionStartHour = prevSessionHour
        )

        // ── Inference + wrap ─────────────────────────────────────────────────────
        val rawHour = predictor.predictEngagementHour(features)
        val confidence = EngagementWindow.confidenceFrom(stddevStartHour14d)

        return EngagementWindow(
            predictedHour = rawHour.toInt().coerceIn(0, 23),
            rawPredictedHour = rawHour,
            confidence = confidence,
            hasSufficientData = true
        )
    }
}
