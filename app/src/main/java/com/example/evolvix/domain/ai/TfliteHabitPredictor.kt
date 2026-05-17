package com.example.evolvix.domain.ai

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.domain.model.HabitData

/**
 * Stub implementation of [HabitPredictor] reserved for the TFLite ML stage (Phase 6.5).
 *
 * At this stage every method is delegated to [mathFallback] (a [MathHabitPredictor]),
 * so the app behaviour is identical to using [MathHabitPredictor] directly.
 * The stub exists solely to prove the **Strategy + Dependency Inversion** wiring:
 * callers injected with this class will automatically gain real ML predictions in
 * Phase 6.5 when the three TFLite interpreters are loaded in [init] — without any
 * ViewModel or use-case changes.
 *
 * Phase 6.5 expansion checklist (do NOT touch before then):
 * - Add `context: Context` to the constructor.
 * - Load `habit_success_classifier.tflite`, `habit_icon_classifier.tflite`,
 *   and `reminder_template_classifier.tflite` from assets in `init`.
 * - Override `predictSuccess`, `findOptimalHours`, `classifyIcon`,
 *   and `selectReminderTemplate` with TFLite inference.
 * - All Phase 6.3 analytics methods (`computeRoutinePrecision`, `computeResilience`,
 *   `detectClashes`, `computeProcrastination`) keep delegating to [mathFallback].
 */
class TfliteHabitPredictor(
    private val mathFallback: MathHabitPredictor
) : HabitPredictor {

    // ── Phase 6.2 — Delegated to math fallback (TFLite override in Phase 6.5) ─

    override fun successProbability(
        habit: HabitData,
        completions: List<HabitCompletionEntity>,
        dayOfWeek: Int,
        hourOfDay: Int
    ): Float = mathFallback.successProbability(habit, completions, dayOfWeek, hourOfDay)

    override fun optimalHours(
        habit: HabitData,
        completions: List<HabitCompletionEntity>,
        topN: Int
    ): List<Int> = mathFallback.optimalHours(habit, completions, topN)

    override fun relatedHabits(
        habit: HabitData,
        allHabits: List<HabitData>,
        allCompletions: List<HabitCompletionEntity>
    ): List<String> = mathFallback.relatedHabits(habit, allHabits, allCompletions)

    override fun isStreakAtRisk(
        habit: HabitData,
        completions: List<HabitCompletionEntity>
    ): Boolean = mathFallback.isStreakAtRisk(habit, completions)

    override fun suggestTargetDelta(
        habit: HabitData,
        completions: List<HabitCompletionEntity>
    ): Int = mathFallback.suggestTargetDelta(habit, completions)

    override fun motivationMessageKey(
        habit: HabitData,
        completions: List<HabitCompletionEntity>,
        currentStreak: Int,
        dayOfWeek: Int
    ): String = mathFallback.motivationMessageKey(habit, completions, currentStreak, dayOfWeek)

    // ── Phase 6.3 — Permanently delegated to math fallback (per Phase 6.5 architecture note) ─

    override fun computeRoutinePrecision(
        completions: List<HabitCompletionEntity>
    ): Double? = mathFallback.computeRoutinePrecision(completions)

    override fun computeResilience(
        habit: HabitData,
        completions: List<HabitCompletionEntity>
    ): Double? = mathFallback.computeResilience(habit, completions)

    override fun detectClashes(
        allHabits: List<HabitData>,
        allCompletions: List<HabitCompletionEntity>,
        threshold: Double
    ): List<Pair<String, String>> = mathFallback.detectClashes(allHabits, allCompletions, threshold)

    override fun computeProcrastination(
        habit: HabitData,
        completions: List<HabitCompletionEntity>
    ): Double? = mathFallback.computeProcrastination(habit, completions)
}
