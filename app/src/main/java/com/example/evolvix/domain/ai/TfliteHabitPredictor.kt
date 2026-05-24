package com.example.evolvix.domain.ai

import android.content.Context
import android.util.Log
import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.domain.model.HabitData
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * TFLite-backed implementation of [HabitPredictor] (Phase 6.5).
 *
 * Owns four [Interpreter] instances loaded from `app/src/main/assets/`:
 *  - `habit_success_classifier.tflite`     — binary success probability (Model 1).
 *  - `habit_icon_classifier.tflite`        — 17-class icon classifier (Model 2).
 *  - `reminder_template_classifier.tflite` — 15-class reminder template (Model 3).
 *  - `habit_abandonment_classifier.tflite` — binary abandonment probability (Phase 8.1).
 *
 * Composition with [mathFallback]:
 *  - All Phase 6.2 / 6.3 statistical methods delegate to [mathFallback] — pure Kotlin
 *    math that the ML models do not replace.
 *  - The ML methods (`predictSuccess`, `findOptimalHours`, `classifyIcon`,
 *    `selectReminderTemplate`, `predictAbandonment`) run TFLite inference and degrade
 *    gracefully to [mathFallback] if a model fails to load.
 *
 * (Pattern: **Strategy + Composition over Inheritance + Liskov substitution** —
 *  this class is fully interchangeable with [MathHabitPredictor] at any call site.)
 */
class TfliteHabitPredictor(
    context: Context,
    private val mathFallback: MathHabitPredictor
) : HabitPredictor {

    // ── Interpreters (null when the asset could not be loaded — fallback engages) ─

    private val successInterpreter: Interpreter?
    private val iconInterpreter: Interpreter?
    private val reminderInterpreter: Interpreter?
    private val abandonmentInterpreter: Interpreter?
    private val streakBreakInterpreter: Interpreter?
    private val weeklyForecastInterpreter: Interpreter?
    private val spilloverInterpreter: Interpreter?
    private val reminderLiftInterpreter: Interpreter?
    private val snoozeDisengagementInterpreter: Interpreter?
    // Phase 9.3 — TargetAdjustmentRegressor
    private val targetChangeInterpreter: Interpreter?

    // ── Pre-loaded normalization tables (parallel-indexed with feature vectors) ──

    private val successMean: FloatArray
    private val successScale: FloatArray

    private val reminderMean: FloatArray
    private val reminderScale: FloatArray
    private val reminderLabels: List<String>

    private val abandonmentMean: FloatArray
    private val abandonmentScale: FloatArray

    private val streakBreakMean: FloatArray
    private val streakBreakScale: FloatArray

    private val weeklyForecastMean: FloatArray
    private val weeklyForecastScale: FloatArray

    private val spilloverMean: FloatArray
    private val spilloverScale: FloatArray

    private val reminderLiftMean: FloatArray
    private val reminderLiftScale: FloatArray

    private val snoozeDisengagementMean: FloatArray
    private val snoozeDisengagementScale: FloatArray

    // Phase 9.3
    private val targetChangeMean: FloatArray
    private val targetChangeScale: FloatArray

    // ── Phase 8.4 — K-Means cluster tables (no Interpreter — JSON + Kotlin math) ──
    private val clusterMean: FloatArray
    private val clusterScale: FloatArray
    private val clusterCentroids: Array<FloatArray>   // 4 × 5, standardized space
    private val clusterLabels: List<String>            // indexed by centroid row
    /** Per-feature training medians; read by [BehavioralClusterUseCase] for null-substitution. */
    override val clusterTrainingMedians: FloatArray

    private val iconVocabIndex: Map<String, Int>
    private val iconIdfWeights: FloatArray
    private val iconLabels: List<String>
    private val iconNgramSizes: IntArray

    init {
        // Load every asset defensively — any single failure must not crash the app;
        // affected ML methods fall back to [mathFallback] and the rest still works.
        successInterpreter = tryLoadModel(context, "habit_success_classifier.tflite")
        iconInterpreter = tryLoadModel(context, "habit_icon_classifier.tflite")
        reminderInterpreter = tryLoadModel(context, "reminder_template_classifier.tflite")
        abandonmentInterpreter = tryLoadModel(context, "habit_abandonment_classifier.tflite")
        streakBreakInterpreter = tryLoadModel(context, "streak_break_classifier.tflite")
        weeklyForecastInterpreter = tryLoadModel(context, "weekly_forecast_regressor.tflite")
        spilloverInterpreter = tryLoadModel(context, "spillover_regressor.tflite")
        reminderLiftInterpreter = tryLoadModel(context, "reminder_lift_classifier.tflite")
        snoozeDisengagementInterpreter = tryLoadModel(context, "snooze_disengagement_classifier.tflite")
        targetChangeInterpreter = tryLoadModel(context, "target_change_regressor.tflite")

        val successJson = readJsonAsset(context, "success_scaler.json")
        successMean = successJson?.toFloatArray("mean") ?: FloatArray(SUCCESS_FEATURE_COUNT)
        successScale = successJson?.toFloatArray("scale") ?: FloatArray(SUCCESS_FEATURE_COUNT) { 1f }

        val reminderJson = readJsonAsset(context, "reminder_scaler.json")
        reminderMean = reminderJson?.toFloatArray("mean") ?: FloatArray(REMINDER_FEATURE_COUNT)
        reminderScale = reminderJson?.toFloatArray("scale") ?: FloatArray(REMINDER_FEATURE_COUNT) { 1f }
        reminderLabels = reminderJson?.toStringList("label_names") ?: emptyList()

        val abandonmentJson = readJsonAsset(context, "abandonment_scaler.json")
        abandonmentMean = abandonmentJson?.toFloatArray("mean") ?: FloatArray(ABANDONMENT_FEATURE_COUNT)
        abandonmentScale = abandonmentJson?.toFloatArray("scale") ?: FloatArray(ABANDONMENT_FEATURE_COUNT) { 1f }

        val streakBreakJson = readJsonAsset(context, "streak_break_scaler.json")
        streakBreakMean = streakBreakJson?.toFloatArray("mean") ?: FloatArray(STREAK_BREAK_FEATURE_COUNT)
        streakBreakScale = streakBreakJson?.toFloatArray("scale") ?: FloatArray(STREAK_BREAK_FEATURE_COUNT) { 1f }

        val weeklyForecastJson = readJsonAsset(context, "weekly_forecast_scaler.json")
        weeklyForecastMean = weeklyForecastJson?.toFloatArray("mean") ?: FloatArray(WEEKLY_FORECAST_FEATURE_COUNT)
        weeklyForecastScale = weeklyForecastJson?.toFloatArray("scale") ?: FloatArray(WEEKLY_FORECAST_FEATURE_COUNT) { 1f }

        val spilloverJson = readJsonAsset(context, "spillover_scaler.json")
        spilloverMean = spilloverJson?.toFloatArray("mean") ?: FloatArray(SPILLOVER_FEATURE_COUNT)
        spilloverScale = spilloverJson?.toFloatArray("scale") ?: FloatArray(SPILLOVER_FEATURE_COUNT) { 1f }

        val reminderLiftJson = readJsonAsset(context, "reminder_lift_scaler.json")
        reminderLiftMean = reminderLiftJson?.toFloatArray("mean") ?: FloatArray(REMINDER_LIFT_FEATURE_COUNT)
        reminderLiftScale = reminderLiftJson?.toFloatArray("scale") ?: FloatArray(REMINDER_LIFT_FEATURE_COUNT) { 1f }

        val snoozeDisengagementJson = readJsonAsset(context, "snooze_disengagement_scaler.json")
        snoozeDisengagementMean = snoozeDisengagementJson?.toFloatArray("mean") ?: FloatArray(SNOOZE_DISENGAGEMENT_FEATURE_COUNT)
        snoozeDisengagementScale = snoozeDisengagementJson?.toFloatArray("scale") ?: FloatArray(SNOOZE_DISENGAGEMENT_FEATURE_COUNT) { 1f }

        // Phase 9.3 — TargetAdjustmentRegressor scaler
        val targetChangeJson = readJsonAsset(context, "target_change_scaler.json")
        targetChangeMean = targetChangeJson?.toFloatArray("mean") ?: FloatArray(TARGET_CHANGE_FEATURE_COUNT)
        targetChangeScale = targetChangeJson?.toFloatArray("scale") ?: FloatArray(TARGET_CHANGE_FEATURE_COUNT) { 1f }

        // habit_clusters.json carries everything needed for K-Means inference —
        // no Interpreter is loaded because nearest-centroid math is done in Kotlin.
        val clusterJson = readJsonAsset(context, "habit_clusters.json")
        clusterMean           = clusterJson?.toFloatArray("feature_means")    ?: FloatArray(CLUSTER_FEATURE_COUNT)
        clusterScale          = clusterJson?.toFloatArray("feature_scales")   ?: FloatArray(CLUSTER_FEATURE_COUNT) { 1f }
        clusterCentroids      = clusterJson?.toFloatMatrix("centroids")        ?: emptyArray()
        clusterLabels         = clusterJson?.toStringList("labels")            ?: emptyList()
        clusterTrainingMedians = clusterJson?.toFloatArray("training_medians") ?: FloatArray(CLUSTER_FEATURE_COUNT)

        val iconJson = readJsonAsset(context, "icon_vocab.json")
        val vocab = iconJson?.toStringList("vocabulary") ?: emptyList()
        iconVocabIndex = vocab.withIndex().associate { (i, token) -> token to i }
        iconIdfWeights = iconJson?.toFloatArray("idf_weights") ?: FloatArray(vocab.size) { 1f }
        iconLabels = iconJson?.toStringList("labels") ?: emptyList()
        iconNgramSizes = iconJson?.toIntArray("ngram_sizes") ?: intArrayOf(2, 3)
    }

    // ── Phase 6.5 — TFLite ML methods ────────────────────────────────────────

    /**
     * Runs Model 1: standard-scale the 7 features, feed a (1, 7) float32 tensor to
     * the interpreter, and return the sigmoid output. Falls back to
     * [MathHabitPredictor.predictSuccess] when the model is missing or inference fails.
     */
    override fun predictSuccess(features: HabitFeatures): Float {
        val interp = successInterpreter ?: return mathFallback.predictSuccess(features)
        return try {
            val raw = features.toFloatArray()
            val scaled = standardScale(raw, successMean, successScale)
            val input = Array(1) { scaled }
            val output = Array(1) { FloatArray(1) }
            interp.run(input, output)
            output[0][0].coerceIn(0f, 1f)
        } catch (t: Throwable) {
            Log.w(TAG, "predictSuccess inference failed; using math fallback", t)
            mathFallback.predictSuccess(features)
        }
    }

    /**
     * Calls [predictSuccess] for every hour 0..23 (copying [features] with the updated
     * `hourOfDay`) and returns the 3 hours with the highest probability. This avoids
     * training a separate per-hour ranking model — Model 1 already encodes the
     * hour-of-day signal in its feature set.
     */
    override fun findOptimalHours(features: HabitFeatures): List<Int> =
        (0..23)
            .map { h -> h to predictSuccess(features.copy(hourOfDay = h)) }
            .sortedByDescending { it.second }
            .take(3)
            .map { it.first }

    /**
     * Runs Model 2: tokenize [habitName] into character n-grams (sizes 2 & 3, matching
     * the Python training pipeline), build a TF-IDF vector against the persisted vocab,
     * feed it to the interpreter, and return the label with the highest softmax score.
     * Falls back to [MathHabitPredictor.classifyIcon] when the model or vocab is missing.
     */
    override fun classifyIcon(habitName: String): String {
        val interp = iconInterpreter
        if (interp == null || iconLabels.isEmpty() || iconVocabIndex.isEmpty()) {
            return mathFallback.classifyIcon(habitName)
        }
        return try {
            val tfidf = buildIconTfidfVector(habitName)
            val input = Array(1) { tfidf }
            val output = Array(1) { FloatArray(iconLabels.size) }
            interp.run(input, output)
            val row = output[0]
            var bestIdx = 0
            for (i in row.indices) if (row[i] > row[bestIdx]) bestIdx = i
            iconLabels[bestIdx]
        } catch (t: Throwable) {
            Log.w(TAG, "classifyIcon inference failed; using math fallback", t)
            mathFallback.classifyIcon(habitName)
        }
    }

    /**
     * Runs Model 3: standard-scale the 7 reminder features, feed a (1, 7) float32 tensor,
     * and return the label corresponding to the argmax of the 15-way softmax output.
     * Falls back to [MathHabitPredictor.selectReminderTemplate] on missing model / failure.
     */
    override fun selectReminderTemplate(features: ReminderContext): String {
        val interp = reminderInterpreter
        if (interp == null || reminderLabels.isEmpty()) {
            return mathFallback.selectReminderTemplate(features)
        }
        return try {
            val raw = features.toFloatArray()
            val scaled = standardScale(raw, reminderMean, reminderScale)
            val input = Array(1) { scaled }
            val output = Array(1) { FloatArray(reminderLabels.size) }
            interp.run(input, output)
            val row = output[0]
            var bestIdx = 0
            for (i in row.indices) if (row[i] > row[bestIdx]) bestIdx = i
            reminderLabels[bestIdx]
        } catch (t: Throwable) {
            Log.w(TAG, "selectReminderTemplate inference failed; using math fallback", t)
            mathFallback.selectReminderTemplate(features)
        }
    }

    // ── Phase 6.2 — Delegated to math fallback ───────────────────────────────

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

    // ── Phase 6.3 — Delegated to math fallback (architecturally permanent) ───

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
    ): List<Pair<String, String>> =
        mathFallback.detectClashes(allHabits, allCompletions, threshold)

    override fun computeProcrastination(
        habit: HabitData,
        completions: List<HabitCompletionEntity>
    ): Double? = mathFallback.computeProcrastination(habit, completions)

    // ── Phase 8.1 — Habit Abandonment Predictor ───────────────────────────────

    /**
     * Runs the abandonment classifier: standard-scale the 7 [AbandonmentFeatures],
     * feed a (1, 7) float32 tensor, and return the sigmoid output as abandonment
     * probability. Falls back to [MathHabitPredictor.predictAbandonment] when the
     * model asset is missing or inference fails.
     */
    override fun predictAbandonment(features: AbandonmentFeatures): Float {
        val interp = abandonmentInterpreter ?: return mathFallback.predictAbandonment(features)
        return try {
            val raw = features.toFloatArray()
            val scaled = standardScale(raw, abandonmentMean, abandonmentScale)
            val input = Array(1) { scaled }
            val output = Array(1) { FloatArray(1) }
            interp.run(input, output)
            output[0][0].coerceIn(0f, 1f)
        } catch (t: Throwable) {
            Log.w(TAG, "predictAbandonment inference failed; using math fallback", t)
            mathFallback.predictAbandonment(features)
        }
    }

    // ── Phase 8.2 — Streak Break Predictor ───────────────────────────────────

    /**
     * Runs the streak-break classifier: standard-scale the 7 [StreakBreakFeatures],
     * feed a (1, 7) float32 tensor, and return the sigmoid output as break probability.
     * Falls back to [MathHabitPredictor.predictStreakBreak] when the model asset is
     * missing or inference fails.
     */
    override fun predictStreakBreak(features: StreakBreakFeatures): Float {
        val interp = streakBreakInterpreter ?: return mathFallback.predictStreakBreak(features)
        return try {
            val raw = features.toFloatArray()
            val scaled = standardScale(raw, streakBreakMean, streakBreakScale)
            val input = Array(1) { scaled }
            val output = Array(1) { FloatArray(1) }
            interp.run(input, output)
            output[0][0].coerceIn(0f, 1f)
        } catch (t: Throwable) {
            Log.w(TAG, "predictStreakBreak inference failed; using math fallback", t)
            mathFallback.predictStreakBreak(features)
        }
    }

    // ── Phase 8.3 — Weekly Performance Forecaster ─────────────────────────────

    /**
     * Runs the weekly forecast regressor: standard-scale the 12 [WeeklyForecastFeatures],
     * feed a (1, 12) float32 tensor, and return the sigmoid output as the predicted
     * next-week completion rate. Falls back to [MathHabitPredictor.predictWeeklyRate]
     * when the model asset is missing or inference fails.
     */
    override fun predictWeeklyRate(features: WeeklyForecastFeatures): Float {
        val interp = weeklyForecastInterpreter ?: return mathFallback.predictWeeklyRate(features)
        return try {
            val raw = features.toFloatArray()
            val scaled = standardScale(raw, weeklyForecastMean, weeklyForecastScale)
            val input = Array(1) { scaled }
            val output = Array(1) { FloatArray(1) }
            interp.run(input, output)
            output[0][0].coerceIn(0f, 1f)
        } catch (t: Throwable) {
            Log.w(TAG, "predictWeeklyRate inference failed; using math fallback", t)
            mathFallback.predictWeeklyRate(features)
        }
    }

    // ── Phase 8.4 — Behavioral Clustering (nearest-centroid, no Interpreter) ──

    /**
     * Classifies [features] into one of four K-Means behavioral tiers by finding the
     * centroid (stored in `habit_clusters.json`) with the smallest squared Euclidean
     * distance in standardized feature space.
     *
     * Steps:
     *  1. Standardize [features] using [clusterMean] / [clusterScale] (same scaler
     *     fitted during training — mirrors `sklearn.StandardScaler.transform`).
     *  2. Compute squared Euclidean distance from the standardized point to each of
     *     the 4 centroids (4 × 5 matrix loaded from `habit_clusters.json`).
     *  3. Return the label string at `clusterLabels[argmin(distances)]`.
     *
     * Falls back to [MathHabitPredictor.classifyBehavioralCluster] when the JSON
     * was not loaded or any unexpected error occurs.
     */
    override fun classifyBehavioralCluster(features: ClusterFeatures): String {
        if (clusterCentroids.isEmpty() || clusterLabels.isEmpty()) {
            return mathFallback.classifyBehavioralCluster(features)
        }
        return try {
            val scaled = standardScale(features.toFloatArray(), clusterMean, clusterScale)
            var bestIdx = 0
            var bestDist = Float.MAX_VALUE
            for (i in clusterCentroids.indices) {
                val d = sqDistance(scaled, clusterCentroids[i])
                if (d < bestDist) { bestDist = d; bestIdx = i }
            }
            clusterLabels.getOrElse(bestIdx) { "dormant" }
        } catch (t: Throwable) {
            Log.w(TAG, "classifyBehavioralCluster failed; using math fallback", t)
            mathFallback.classifyBehavioralCluster(features)
        }
    }

    // ── Phase 8.5 — Cross-Habit Spillover Regressor ──────────────────────────

    /**
     * Runs the spillover regressor: standard-scale the 5 [SpilloverFeatures], feed
     * a (1, 5) float32 tensor, and return the tanh × 0.5 output as the lift delta
     * ∈ [-0.5, +0.5]. Falls back to [MathHabitPredictor.predictSpillover] when the
     * model asset is missing or inference fails.
     */
    override fun predictSpillover(features: SpilloverFeatures): Float {
        val interp = spilloverInterpreter ?: return mathFallback.predictSpillover(features)
        return try {
            val raw = features.toFloatArray()
            val scaled = standardScale(raw, spilloverMean, spilloverScale)
            val input = Array(1) { scaled }
            val output = Array(1) { FloatArray(1) }
            interp.run(input, output)
            // Model output is already bounded to [-0.5, +0.5] via the tanh × 0.5 layer.
            output[0][0].coerceIn(-0.5f, 0.5f)
        } catch (t: Throwable) {
            Log.w(TAG, "predictSpillover inference failed; using math fallback", t)
            mathFallback.predictSpillover(features)
        }
    }

    // ── Phase 9.1 — Reminder Effectiveness (Lift) Model ──────────────────────

    /**
     * Runs the ReminderLiftClassifier: standard-scales the 8 features, feeds a (1, 8)
     * float32 tensor to the interpreter, and returns the sigmoid output — the predicted
     * completion probability given the current [ReminderLiftFeatures.reminderSent] value.
     *
     * Falls back to [MathHabitPredictor.predictReminderCompletion] when the model is
     * missing or inference fails (Strategy + Dependency Inversion).
     */
    override fun predictReminderCompletion(features: ReminderLiftFeatures): Float {
        val interp = reminderLiftInterpreter ?: return mathFallback.predictReminderCompletion(features)
        return try {
            val raw = features.toFloatArray()
            val scaled = standardScale(raw, reminderLiftMean, reminderLiftScale)
            val input = Array(1) { scaled }
            val output = Array(1) { FloatArray(1) }
            interp.run(input, output)
            output[0][0].coerceIn(0f, 1f)
        } catch (t: Throwable) {
            Log.w(TAG, "predictReminderCompletion inference failed; using math fallback", t)
            mathFallback.predictReminderCompletion(features)
        }
    }

    // ── Phase 9.2 — Snooze Disengagement Predictor ─────────────────────────

    /**
     * Runs the SnoozeDisengagementClassifier: standard-scales the 7 features, feeds a
     * (1, 7) float32 tensor to the interpreter, and returns the sigmoid output — the
     * predicted probability that the habit will receive zero completions in the next 7 days.
     *
     * Falls back to [MathHabitPredictor.predictSnoozeDisengagement] when the model is
     * missing or inference fails (Strategy + Dependency Inversion).
     */
    override fun predictSnoozeDisengagement(features: SnoozeDisengagementFeatures): Float {
        val interp = snoozeDisengagementInterpreter
            ?: return mathFallback.predictSnoozeDisengagement(features)
        return try {
            val raw = features.toFloatArray()
            val scaled = standardScale(raw, snoozeDisengagementMean, snoozeDisengagementScale)
            val input = Array(1) { scaled }
            val output = Array(1) { FloatArray(1) }
            interp.run(input, output)
            output[0][0].coerceIn(0f, 1f)
        } catch (t: Throwable) {
            Log.w(TAG, "predictSnoozeDisengagement inference failed; using math fallback", t)
            mathFallback.predictSnoozeDisengagement(features)
        }
    }

    // ── Phase 9.3 — Target Change Effectiveness Regressor ────────────────────

    /**
     * Runs the TargetAdjustmentRegressor: standard-scales the 8 [TargetChangeFeatures],
     * feeds a (1, 8) float32 tensor to the interpreter, and returns the tanh×2.0 output
     * coerced to [-2.0, +2.0].
     *
     * Falls back to [MathHabitPredictor.predictTargetDelta] when the model is missing
     * or inference fails (Strategy + Dependency Inversion).
     */
    override fun predictTargetDelta(features: TargetChangeFeatures): Float {
        val interp = targetChangeInterpreter
            ?: return mathFallback.predictTargetDelta(features)
        return try {
            val raw = features.toFloatArray()
            val scaled = standardScale(raw, targetChangeMean, targetChangeScale)
            val input = Array(1) { scaled }
            val output = Array(1) { FloatArray(1) }
            interp.run(input, output)
            output[0][0].coerceIn(-2f, 2f)
        } catch (t: Throwable) {
            Log.w(TAG, "predictTargetDelta inference failed; using math fallback", t)
            mathFallback.predictTargetDelta(features)
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Applies the (x − mean) / scale transform that `sklearn.StandardScaler` produces
     * during training. Indices in [mean] and [scale] must align with [raw].
     */
    private fun standardScale(raw: FloatArray, mean: FloatArray, scale: FloatArray): FloatArray {
        val out = FloatArray(raw.size)
        for (i in raw.indices) {
            val s = if (i < scale.size && scale[i] != 0f) scale[i] else 1f
            val m = if (i < mean.size) mean[i] else 0f
            out[i] = (raw[i] - m) / s
        }
        return out
    }

    /**
     * Replicates `name_to_ngram_string` + `TextVectorization(output_mode='tf_idf')`
     * from `ml-training/train_icon_model.py`:
     *  1. Lowercase + alphanumeric/whitespace whitelist.
     *  2. Split into words, pad each with "_" sentinels.
     *  3. Emit every n-gram of every configured size as a token.
     *  4. For each token, look up its vocab index and accumulate `idf_weights[index]`
     *     (count × idf, which equals TF-IDF since IDF is constant per vocab slot).
     *
     * Returns a [FloatArray] of length [iconIdfWeights] = vocab size.
     * Out-of-vocab tokens fall into bucket 0 (`[UNK]`) — matches Keras default.
     */
    private fun buildIconTfidfVector(name: String): FloatArray {
        val vec = FloatArray(iconIdfWeights.size)
        val cleaned = buildString {
            for (ch in name.lowercase()) {
                if (ch.isLetterOrDigit() || ch.isWhitespace()) append(ch)
            }
        }
        val words = cleaned.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        for (word in words) {
            val padded = "_${word}_"
            for (n in iconNgramSizes) {
                if (padded.length < n) continue
                for (i in 0..(padded.length - n)) {
                    val token = padded.substring(i, i + n)
                    val idx = iconVocabIndex[token] ?: 0  // 0 == [UNK] bucket
                    if (idx in vec.indices) vec[idx] += iconIdfWeights[idx]
                }
            }
        }
        return vec
    }

    /** Reads an asset file into a memory-mapped [ByteBuffer] suitable for [Interpreter]. */
    private fun tryLoadModel(context: Context, assetFileName: String): Interpreter? = try {
        val fd = context.assets.openFd(assetFileName)
        val input = FileInputStream(fd.fileDescriptor)
        val buffer: ByteBuffer = input.channel.map(
            FileChannel.MapMode.READ_ONLY,
            fd.startOffset,
            fd.declaredLength
        ).order(ByteOrder.nativeOrder())
        Interpreter(buffer)
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to load TFLite asset '$assetFileName' — falling back to math.", t)
        null
    }

    /** Loads a JSON asset and parses it into a [JSONObject]; null on any failure. */
    private fun readJsonAsset(context: Context, assetFileName: String): JSONObject? = try {
        context.assets.open(assetFileName).use { stream ->
            JSONObject(stream.bufferedReader().readText())
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to read JSON asset '$assetFileName'.", t)
        null
    }

    private fun JSONObject.toFloatArray(key: String): FloatArray {
        val arr = optJSONArray(key) ?: return FloatArray(0)
        return FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
    }

    private fun JSONObject.toIntArray(key: String): IntArray {
        val arr = optJSONArray(key) ?: return IntArray(0)
        return IntArray(arr.length()) { i -> arr.getInt(i) }
    }

    private fun JSONObject.toStringList(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        return List(arr.length()) { i -> arr.getString(i) }
    }

    /** Parses a JSON array-of-arrays into an [Array<FloatArray>] (e.g. K-Means centroids). */
    private fun JSONObject.toFloatMatrix(key: String): Array<FloatArray> {
        val outer = optJSONArray(key) ?: return emptyArray()
        return Array(outer.length()) { i ->
            val inner = outer.getJSONArray(i)
            FloatArray(inner.length()) { j -> inner.getDouble(j).toFloat() }
        }
    }

    /** Squared Euclidean distance between two equal-length float vectors. */
    private fun sqDistance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            val diff = a[i] - b[i]
            sum += diff * diff
        }
        return sum
    }

    companion object {
        private const val TAG = "TfliteHabitPredictor"
        private const val SUCCESS_FEATURE_COUNT = 7
        private const val REMINDER_FEATURE_COUNT = 7
        private const val ABANDONMENT_FEATURE_COUNT = 7
        private const val STREAK_BREAK_FEATURE_COUNT = 7
        private const val WEEKLY_FORECAST_FEATURE_COUNT = 12
        private const val CLUSTER_FEATURE_COUNT = 5
        private const val SPILLOVER_FEATURE_COUNT = 5
        private const val REMINDER_LIFT_FEATURE_COUNT = 8
        private const val SNOOZE_DISENGAGEMENT_FEATURE_COUNT = 7
        private const val TARGET_CHANGE_FEATURE_COUNT = 8   // Phase 9.3
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
