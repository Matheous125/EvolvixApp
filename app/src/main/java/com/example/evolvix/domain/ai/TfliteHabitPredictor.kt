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
 * Owns three [Interpreter] instances loaded from `app/src/main/assets/`:
 *  - `habit_success_classifier.tflite` — binary success probability (Model 1).
 *  - `habit_icon_classifier.tflite`    — 17-class icon classifier (Model 2).
 *  - `reminder_template_classifier.tflite` — 15-class reminder template (Model 3).
 *
 * Composition with [mathFallback]:
 *  - All Phase 6.2 / 6.3 statistical methods delegate to [mathFallback] — pure Kotlin
 *    math that the ML models do not replace.
 *  - The four Phase 6.5 ML methods (`predictSuccess`, `findOptimalHours`,
 *    `classifyIcon`, `selectReminderTemplate`) run TFLite inference and degrade
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

    // ── Pre-loaded normalization tables (parallel-indexed with feature vectors) ──

    private val successMean: FloatArray
    private val successScale: FloatArray

    private val reminderMean: FloatArray
    private val reminderScale: FloatArray
    private val reminderLabels: List<String>

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

        val successJson = readJsonAsset(context, "success_scaler.json")
        successMean = successJson?.toFloatArray("mean") ?: FloatArray(SUCCESS_FEATURE_COUNT)
        successScale = successJson?.toFloatArray("scale") ?: FloatArray(SUCCESS_FEATURE_COUNT) { 1f }

        val reminderJson = readJsonAsset(context, "reminder_scaler.json")
        reminderMean = reminderJson?.toFloatArray("mean") ?: FloatArray(REMINDER_FEATURE_COUNT)
        reminderScale = reminderJson?.toFloatArray("scale") ?: FloatArray(REMINDER_FEATURE_COUNT) { 1f }
        reminderLabels = reminderJson?.toStringList("label_names") ?: emptyList()

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

    companion object {
        private const val TAG = "TfliteHabitPredictor"
        private const val SUCCESS_FEATURE_COUNT = 7
        private const val REMINDER_FEATURE_COUNT = 7
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
