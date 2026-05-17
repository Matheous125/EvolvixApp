package com.example.evolvix.domain.ai

import android.content.Context

/**
 * Process-wide provider for the on-device [HabitPredictor].
 *
 * Architecturally analogous to `AppDatabase.getDatabase(context)`:
 *  - One [TfliteHabitPredictor] is constructed lazily on first access.
 *  - The instance is reused for the rest of the process lifetime, so TFLite
 *    interpreters and JSON tables are loaded **once**.
 *  - Every caller (ViewModels, use cases, notifications worker) receives the same
 *    [HabitPredictor] reference — guaranteeing consistent ML output across the app.
 *
 * (Pattern: **Singleton via `object` + lazy initialization**, identical to how
 *  [com.example.evolvix.data.local.AppDatabase] is provided.)
 *
 * This was introduced in Phase 6.5.6 to wire the `TfliteHabitPredictor`
 * implementation behind the existing [HabitPredictor] interface without
 * forcing every ViewModel call-site to know about TFLite or its assets.
 */
object AiContainer {

    @Volatile
    private var instance: HabitPredictor? = null

    /**
     * Returns the singleton [HabitPredictor]. Constructs a [TfliteHabitPredictor]
     * (with a [MathHabitPredictor] fallback) on first call. Uses double-checked
     * locking so multiple ViewModels created in parallel never trigger duplicate
     * interpreter loads.
     */
    fun predictor(context: Context): HabitPredictor {
        return instance ?: synchronized(this) {
            instance ?: TfliteHabitPredictor(
                context = context.applicationContext,
                mathFallback = MathHabitPredictor()
            ).also { instance = it }
        }
    }
}
