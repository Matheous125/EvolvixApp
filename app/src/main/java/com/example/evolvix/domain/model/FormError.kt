package com.example.evolvix.domain.model

/**
 * Sealed class representing form validation errors surfaced by the ViewModel.
 * The View observes [com.example.evolvix.ui.viewmodel.HabitViewModel.formError]
 * and renders an inline message when the value is non-null.
 *
 * Using a sealed class allows adding new error types (e.g., EmptyName, TooLong)
 * in future phases without changing the observer contract. (Pattern: Sealed Class State)
 */
sealed class FormError {
    /** The entered habit name already exists in the database (case-insensitive match). */
    object DuplicateName : FormError()
}
