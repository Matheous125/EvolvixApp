package com.example.evolvix.domain.model

/**
 * Sealed class hierarchy representing the four behavioral tiers produced by the
 * **Habit Behavioral Clustering** K-Means model (Phase 8.4).
 *
 * This is a **pure domain model** — it intentionally carries no Android imports.
 * The [key] property matches the label strings in `habit_clusters.json` so that
 * [TfliteHabitPredictor.classifyBehavioralCluster] can return a raw string and
 * [BehavioralClusterUseCase] can resolve it to a typed sealed object via [fromKey].
 *
 * The View layer resolves [key] to a localized description via a `when` branch on
 * `stringResource(R.string.cluster_*)` — same pattern as [MotivationMessage.messageKey].
 *
 * K-Means priors (documented in `ml-training/generate_clustering_data.py`):
 *  - [EffortlessRoutine] — rate30d ≥ 0.85, low timing stddev, mature habit.
 *  - [ConsistentEffort]  — rate30d 0.55–0.85, moderate timing variability.
 *  - [Struggling]        — rate30d 0.15–0.55, high timing chaos, younger habit.
 *  - [Dormant]           — rate30d < 0.20, near-absent; bimodal age (new give-up or old abandoned).
 */
sealed class BehavioralCluster(

    /**
     * Machine-readable identifier matching `habit_clusters.json` → `labels` entries.
     * Used by [BehavioralClusterUseCase] and the View's `stringResource` resolver.
     */
    val key: String
) {
    /** Habit is completed automatically — a deeply ingrained, automatic routine. */
    object EffortlessRoutine : BehavioralCluster("effortless_routine")

    /** Habit is maintained with active effort — solid engagement, room to grow. */
    object ConsistentEffort  : BehavioralCluster("consistent_effort")

    /** Habit is struggling — irregular completions, high timing variability. */
    object Struggling        : BehavioralCluster("struggling")

    /** Habit is effectively dormant — very low completion rate, little engagement. */
    object Dormant           : BehavioralCluster("dormant")

    companion object {
        /**
         * Resolves a raw [key] string (from `habit_clusters.json`) to a typed
         * [BehavioralCluster] instance. Unrecognized keys map to [Dormant] as a
         * safe default (conservative: treats unknown ≈ disengaged).
         */
        fun fromKey(key: String): BehavioralCluster = when (key) {
            "effortless_routine" -> EffortlessRoutine
            "consistent_effort"  -> ConsistentEffort
            "struggling"         -> Struggling
            else                 -> Dormant
        }
    }
}

/**
 * Output of [com.example.evolvix.domain.usecase.BehavioralClusterUseCase] (Phase 8.4).
 *
 * Wraps the raw cluster label from [HabitPredictor.classifyBehavioralCluster] into a
 * typed [BehavioralCluster] and a data-sufficiency flag, so the View never has to
 * parse a raw string or apply sufficiency guards directly.
 *
 * @property habitId           Room primary key of the assessed habit.
 * @property cluster           Resolved behavioral tier; always set (defaults to [BehavioralCluster.Dormant]
 *                             when [hasSufficientData] is false — treat as a placeholder, not a diagnosis).
 * @property hasSufficientData False when the habit has fewer than 10 completions or fewer than
 *                             14 days of history — not enough signal for a meaningful cluster assignment.
 *                             The View shows a "not enough data" placeholder in this case.
 */
data class HabitCluster(
    val habitId: Int,
    val cluster: BehavioralCluster,
    val hasSufficientData: Boolean
)
