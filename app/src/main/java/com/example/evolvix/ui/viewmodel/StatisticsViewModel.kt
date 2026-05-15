package com.example.evolvix.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.evolvix.data.local.HabitDao
import com.example.evolvix.domain.model.LifeBalanceEntry
import com.example.evolvix.domain.model.PerHabitStats
import com.example.evolvix.domain.model.WeeklyOverview
import com.example.evolvix.domain.usecase.CalculateStreakUseCase
import com.example.evolvix.domain.usecase.LifeBalanceUseCase
import com.example.evolvix.domain.usecase.SparklineUseCase
import com.example.evolvix.domain.usecase.WeeklyOverviewUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

/**
 * ViewModel for the Statistics screen.
 *
 * Combines two Room Flows — [HabitDao.getAllHabits] and [HabitDao.getAllCompletions] — and
 * runs the three Phase-5 use cases on every emission to produce fully reactive statistics.
 * Any DB write (completion logged, habit edited, history changed) automatically propagates
 * to all three [StateFlow] properties without manual refresh or polling.
 *
 * (Pattern: **MVVM + Observer via Flow** — `combine` merges two Room Flows; each mapped
 *  result is a distinct [StateFlow] driven by the same upstream pair)
 *
 * @property dao Room DAO used as the single source of truth for habits and completions.
 */
class StatisticsViewModel(private val dao: HabitDao) : ViewModel() {

    // Pure-function interactors — stateless, safe to reuse across Flow emissions.
    // (Pattern: Use Case / Interactor — each encapsulates exactly one query type)
    private val weeklyOverviewUseCase = WeeklyOverviewUseCase()
    private val lifeBalanceUseCase = LifeBalanceUseCase()
    private val sparklineUseCase = SparklineUseCase()
    private val calculateStreakUseCase = CalculateStreakUseCase()

    /**
     * 7-day rolling overview: daily completion counts, today's completed habits count,
     * and an aggregate week-completion rate.
     * Drives the "Global Overview" card in StatisticsScreen.
     * (Pattern: Observer via StateFlow — recomputed on every habits/completions emission)
     */
    val overview: StateFlow<WeeklyOverview> = combine(
        dao.getAllHabits(),
        dao.getAllCompletions()
    ) { habits, completions ->
        weeklyOverviewUseCase(habits, completions)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = WeeklyOverview(
            dailySummaries = emptyList(),
            totalActiveHabits = 0,
            todayCompletedHabits = 0,
            weekCompletionRate = 0f
        )
    )

    /**
     * Per-category completion rates over the last 30 days, sorted alphabetically.
     * Drives the "Life Balance" card in StatisticsScreen.
     * Habits with no assigned category appear under the "Other" bucket.
     * (Pattern: Observer via StateFlow — recomputed on every habits/completions emission)
     */
    val lifeBalance: StateFlow<List<LifeBalanceEntry>> = combine(
        dao.getAllHabits(),
        dao.getAllCompletions()
    ) { habits, completions ->
        lifeBalanceUseCase(habits, completions)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    /**
     * One [PerHabitStats] entry per habit, bundling streak metrics, a 30-day sparkline,
     * and a 30-day completion rate. Drives the per-habit collapsed/expanded cards in
     * StatisticsScreen (7D/30D/3M/ALL chart tabs are rendered from this sparkline data).
     *
     * The 30-day sparkline is precomputed here because it covers the most common chart tab.
     * Wider ranges (3M, ALL) are derived by the View from the same [PerHabitStats.habit]
     * and [PerHabitStats.streak] data by calling [SparklineUseCase] directly with a wider window.
     *
     * (Pattern: Observer via StateFlow — one `combine` emission recomputes all habits at once)
     */
    val perHabitStats: StateFlow<List<PerHabitStats>> = combine(
        dao.getAllHabits(),
        dao.getAllCompletions()
    ) { habits, completions ->
        val today = LocalDate.now()
        val from30d = today.minusDays(29) // 30 inclusive days: today − 29 .. today

        habits.map { habit ->
            val habitCompletions = completions.filter { it.habitId == habit.id }
            val streak = calculateStreakUseCase(habitCompletions, habit.frequency, today)
            val sparkline = sparklineUseCase(habitCompletions, from30d, today)
            val rate = sparkline.count { it.reached }.toFloat() / 30f
            PerHabitStats(
                habit = habit,
                streak = streak,
                sparkline30d = sparkline,
                completionRate30d = rate.coerceIn(0f, 1f)
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )
}
