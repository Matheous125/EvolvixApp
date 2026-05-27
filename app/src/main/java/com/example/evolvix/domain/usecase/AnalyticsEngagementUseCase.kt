package com.example.evolvix.domain.usecase

import com.example.evolvix.data.local.AppSessionDao
import com.example.evolvix.domain.model.AnalyticsEngagement
import java.time.LocalDate

/**
 * Interactor (B3, PLAN-POLISH-PASS): computes the [AnalyticsEngagement] retention-lift
 * headline rendered at the top of `SummaryGroupCard` on the Statistics screen.
 *
 * ### Responsibility
 * Bridges the raw session log ([AppSessionDao]) and the View layer. The View consumes
 * a single immutable [AnalyticsEngagement] object — it never touches Room.
 *
 * ### Algorithm (pure, no Android imports)
 *  1. Load the 200 most-recent sessions via [AppSessionDao.getRecent]. 200 covers up
 *     to ~7 sessions/day × 30 days, which is generous for the rolling window we care about.
 *  2. Filter to the last [AnalyticsEngagement.Companion.WINDOW_DAYS] calendar days.
 *  3. Partition sessions by whether `"StatisticsScreen"` appears in
 *     [com.example.evolvix.data.model.AppSessionEntity.screensVisited] (analytics viewers).
 *  4. Apply the [AnalyticsEngagement.Companion.MIN_SESSIONS_PER_BUCKET] guard — both
 *     buckets must hit the threshold or we return [AnalyticsEngagement.insufficient].
 *  5. Count distinct active days per bucket (a day is active if it has ≥1 session of
 *     that kind), then compute lift = (viewerActiveDays − nonViewerActiveDays) / WINDOW.
 *
 * ### Pattern
 * Use Case (Clean Architecture). One public entry point, depends only on the DAO
 * interface, fully unit-testable without Android.
 *
 * @param dao Thin Room DAO injected by the ViewModel layer.
 */
class AnalyticsEngagementUseCase(
    private val dao: AppSessionDao
) {

    /**
     * Executes the retention-lift computation.
     *
     * @param today Injected date so unit tests can pin the window deterministically.
     *              Production callers omit this argument.
     */
    suspend fun execute(today: LocalDate = LocalDate.now()): AnalyticsEngagement {
        // Read once; AppSessionDao.getRecent returns rows sorted DESC by startedAt.
        val recent = dao.getRecent(200)

        // Inclusive 30-day window: today−(WINDOW_DAYS−1) .. today.
        val windowStart = today.minusDays((AnalyticsEngagement.WINDOW_DAYS - 1).toLong())

        // Keep only sessions whose calendar start date falls inside the window.
        val inWindow = recent.filter { session ->
            val startDate = session.startedAt.toLocalDate()
            !startDate.isBefore(windowStart) && !startDate.isAfter(today)
        }

        // Partition by analytics-viewer flag (StatisticsScreen visited in this session).
        val (viewers, nonViewers) = inWindow.partition { session ->
            session.screensVisited.contains("StatisticsScreen")
        }

        // Sufficiency guard — both buckets need a minimum count to make the lift meaningful.
        if (viewers.size < AnalyticsEngagement.MIN_SESSIONS_PER_BUCKET ||
            nonViewers.size < AnalyticsEngagement.MIN_SESSIONS_PER_BUCKET
        ) {
            return AnalyticsEngagement.insufficient
        }

        // Distinct calendar dates per bucket → "active days".
        val viewerActiveDays = viewers.map { it.startedAt.toLocalDate() }.toSet().size
        val nonViewerActiveDays = nonViewers.map { it.startedAt.toLocalDate() }.toSet().size

        // Bernoulli-style lift on the 30-day denominator, clamped just in case.
        val window = AnalyticsEngagement.WINDOW_DAYS.toFloat()
        val lift = ((viewerActiveDays - nonViewerActiveDays) / window).coerceIn(-1f, 1f)

        return AnalyticsEngagement(
            lift = lift,
            hasSufficientData = true,
            viewerActiveDays = viewerActiveDays,
            nonViewerActiveDays = nonViewerActiveDays
        )
    }
}
