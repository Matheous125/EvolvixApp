package com.example.evolvix.domain.model

/**
 * Output of [com.example.evolvix.domain.usecase.MotivationMessageUseCase].
 *
 * Intentionally does NOT contain resolved user-visible text. The [messageKey] is
 * looked up in `res/values/strings.xml` (and `values-pl/`) at the View layer so that
 * Android's `<plurals>` mechanism can handle Polish/English inflection correctly
 * (e.g. "1 dzień" / "2 dni" / "5 dni").
 *
 * Usage in a Composable:
 * ```kotlin
 * val text = pluralStringResource(
 *     id    = resolveStringRes(message.messageKey),
 *     count = message.streak,
 *     message.streak
 * )
 * ```
 *
 * @property messageKey  String resource key selecting one of the 9 motivation templates
 *                       defined in `strings.xml` (e.g. "motivation_streak_milestone").
 * @property streak      Current unbroken streak count in periods; passed as the quantity
 *                       argument to `<plurals>` resolution so the View can inflect the
 *                       count correctly without re-computing it.
 * @property dayOfWeek   ISO day-of-week (1 = Mon, 7 = Sun) that the message was
 *                       selected for — retained for logging and testing assertions.
 */
data class MotivationMessage(
    val messageKey: String,
    val streak: Int,
    val dayOfWeek: Int
)
