package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.domain.ai.MathHabitPredictor
import com.example.evolvix.domain.model.HabitData
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Unit tests for [HabitRecommendationUseCase].
 *
 * [MathHabitPredictor.relatedHabits] does not call [LocalDate.now()] — it compares dates
 * extracted from completion timestamps. A fixed reference date is therefore safe and makes
 * all tests fully deterministic.
 *
 * Coverage:
 * - [HabitRecommendation.hasSufficientData] threshold at 5 focal-habit completed dates.
 * - Co-occurring habit (high shared days) appears in recommendations.
 * - Non-overlapping habit is excluded.
 * - Focal habit is never recommended to itself.
 * - Empty completions → empty recommendations.
 */
class HabitRecommendationUseCaseTest {

    private lateinit var useCase: HabitRecommendationUseCase

    private val today = LocalDate.of(2025, 1, 6)

    private val focal = HabitData(
        id = 1, name = "Run", currentCount = 0,
        frequency = HabitFrequency.Daily, target = 1
    )
    private val other = HabitData(
        id = 2, name = "Meditate", currentCount = 0,
        frequency = HabitFrequency.Daily, target = 1
    )
    private val allHabits = listOf(focal, other)

    @Before
    fun setUp() {
        useCase = HabitRecommendationUseCase(predictor = MathHabitPredictor())
    }

    private fun completion(habitId: Int, daysAgo: Long) = HabitCompletionEntity(
        habitId = habitId,
        progressUpdate = today.minusDays(daysAgo).atTime(9, 0),
        isTargetReached = true
    )

    // ── Data sufficiency ──────────────────────────────────────────────────────

    @Test
    fun `hasSufficientData is false when focal habit has fewer than 5 completed dates`() {
        val completions = (1L..4L).map { completion(habitId = 1, daysAgo = it) }
        assertFalse(useCase(focal, allHabits, completions).hasSufficientData)
    }

    @Test
    fun `hasSufficientData is true when focal habit has 5 or more completed dates`() {
        val completions = (1L..5L).map { completion(habitId = 1, daysAgo = it) }
        assertTrue(useCase(focal, allHabits, completions).hasSufficientData)
    }

    // ── Co-occurrence detection ───────────────────────────────────────────────

    @Test
    fun `co-occurring habit is included in recommendations`() {
        // Both habits completed on the same 10 days → strong co-occurrence signal.
        val completions = (1L..10L).flatMap {
            listOf(completion(habitId = 1, daysAgo = it), completion(habitId = 2, daysAgo = it))
        }
        assertTrue(
            "Meditate should be related to Run",
            "Meditate" in useCase(focal, allHabits, completions).relatedHabitNames
        )
    }

    @Test
    fun `habit with no shared completed days is excluded from recommendations`() {
        // Habit 1 on days 1–10, habit 2 on days 20–30 → zero overlap.
        val completions =
            (1L..10L).map { completion(habitId = 1, daysAgo = it) } +
            (20L..30L).map { completion(habitId = 2, daysAgo = it) }
        assertFalse(
            "Meditate should NOT be related when there is no overlap",
            "Meditate" in useCase(focal, allHabits, completions).relatedHabitNames
        )
    }

    @Test
    fun `focal habit is never included in its own recommendations`() {
        val completions = (1L..10L).flatMap {
            listOf(completion(habitId = 1, daysAgo = it), completion(habitId = 2, daysAgo = it))
        }
        assertFalse(
            "Run should not recommend itself",
            "Run" in useCase(focal, allHabits, completions).relatedHabitNames
        )
    }

    @Test
    fun `recommendations are empty when completions list is empty`() {
        assertTrue(useCase(focal, allHabits, emptyList()).relatedHabitNames.isEmpty())
    }
}
