package com.example.evolvix.domain.model

/**
 * Output of [com.example.evolvix.domain.usecase.AdaptiveDifficultyUseCase].
 *
 * Bundles the directional suggestion (delta) with the concrete numbers the UI needs
 * to display a meaningful recommendation card — current rate, current target, and
 * the suggested new target if the advice is accepted.
 *
 * @property delta           Direction of the suggestion: +1 = increase target,
 *                           -1 = decrease target, 0 = target is well-calibrated.
 * @property rollingRate     Completion rate over the last 14 days (0.0–1.0), computed
 *                           as reached-periods / total-periods in the window.
 * @property currentTarget   The habit's current daily/weekly target count.
 * @property suggestedTarget The recommended new target if [delta] is applied
 *                           ([currentTarget] + [delta], clamped to ≥ 1).
 *                           Equals [currentTarget] when [delta] is 0.
 * @property hasSufficientData False when fewer than the minimum number of periods
 *                             exist in the 14-day window — advice would be premature.
 */
data class DifficultyAdjustment(
    val delta: Int,
    val rollingRate: Float,
    val currentTarget: Int,
    val suggestedTarget: Int,
    val hasSufficientData: Boolean
)
