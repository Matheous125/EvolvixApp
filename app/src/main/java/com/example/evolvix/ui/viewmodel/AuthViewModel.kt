package com.example.evolvix.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.evolvix.domain.auth.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Holds the complete UI state for all authentication screens.
 *
 * This is a **State Holder** (Unidirectional Data Flow): the View reads from this class
 * and sends events up to [AuthViewModel] functions. The ViewModel is the single source
 * of truth for auth state.
 *
 * @property isLoading       True while a suspend auth operation is in progress.
 * @property isAuthenticated True once login or register succeeds; drives nav-graph guard.
 * @property error           Non-null when the last operation failed; cleared by [AuthViewModel.clearError].
 * @property resetEmailSent  True after [AuthViewModel.resetPassword] succeeds — drives
 *                           a one-time confirmation message on ResetPasswordScreen.
 * @property currentEmail    The e-mail of the currently signed-in user (null when logged out).
 *                           Surfaced as state so SettingsScreen recomposes after a successful
 *                           change-e-mail flow without having to re-read the repository.
 * @property emailChanged    True after [AuthViewModel.changeEmail] succeeds — drives a
 *                           one-time confirmation toast and back-nav on ChangeEmailScreen.
 */
data class AuthUiState(
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val error: String? = null,
    val resetEmailSent: Boolean = false,
    val currentEmail: String? = null,
    val emailChanged: Boolean = false
)

/**
 * ViewModel backing all four authentication screens (Login, Register,
 * ResetPassword, SetNewPassword).
 *
 * Delegates every operation to [AuthRepository] so the ViewModel is completely
 * decoupled from the concrete implementation (Pattern: **Repository + Dependency
 * Inversion**). Phase 10 swaps [FakeAuthRepository] for `FirebaseAuthRepository`
 * here without changing any ViewModel logic.
 *
 * (Pattern: **MVVM + Observer via StateFlow** — the View collects [uiState] and
 *  calls the functions below as user events. State flows down; events flow up.)
 *
 * @property repository The auth data-source abstraction injected via [AuthViewModelFactory].
 */
class AuthViewModel(private val repository: AuthRepository) : ViewModel() {

    // Seed the state with whatever account (if any) the repository currently considers
    // signed in. Phase 9's FakeAuthRepository starts with no session, but exposing
    // currentEmail() up-front lets Phase 10's FirebaseAuthRepository hydrate the UI
    // from a persistent session without an extra round-trip.
    private val _uiState = MutableStateFlow(
        AuthUiState(currentEmail = repository.currentEmail())
    )

    /** Observed by all auth screens to render loading indicators, errors, and navigation. */
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    // ── Auth operations ───────────────────────────────────────────────────────

    /**
     * Attempts to sign in with [email] and [password].
     * On success sets [AuthUiState.isAuthenticated] to true — the nav graph guard
     * picks this up and navigates to the main app graph.
     */
    fun login(email: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.login(email, password)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isAuthenticated = true,
                            currentEmail = repository.currentEmail()
                        )
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
        }
    }

    /**
     * Creates a new account with [email] and [password].
     * On success the user is considered logged in — same nav behaviour as [login].
     */
    fun register(email: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.register(email, password)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isAuthenticated = true,
                            currentEmail = repository.currentEmail()
                        )
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
        }
    }

    /**
     * Sends a password-reset e-mail to [email].
     * On success sets [AuthUiState.resetEmailSent] so the View can display a
     * one-time confirmation message without navigating away.
     */
    fun resetPassword(email: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, resetEmailSent = false) }
            repository.resetPassword(email)
                .onSuccess { _uiState.update { it.copy(isLoading = false, resetEmailSent = true) } }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
        }
    }

    /**
     * Updates the current user's password from [oldPassword] to [newPassword].
     * Verifies [oldPassword] against the stored credentials before applying the change.
     * On success sets [AuthUiState.resetEmailSent] as a reusable "done" signal
     * that the SetNewPasswordScreen uses to show a success toast.
     */
    fun changePassword(oldPassword: String, newPassword: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.changePassword(oldPassword, newPassword)
                .onSuccess { _uiState.update { it.copy(isLoading = false, resetEmailSent = true) } }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
        }
    }

    /**
     * Signs out the current user and resets state to the unauthenticated baseline.
     * The nav-graph guard observes [AuthUiState.isAuthenticated] and redirects to Login.
     */
    /**
     * Updates the current user's e-mail address from [newEmail] after verifying
     * [currentPassword] against the stored credentials.
     *
     * On success: sets [AuthUiState.currentEmail] to the new address and
     * [AuthUiState.emailChanged] = true so the View can show a confirmation toast and
     * pop back to Settings. The Settings screen reads [AuthUiState.currentEmail]
     * as the subtitle of its "Change e-mail" row, so the new address is visible
     * immediately on return.
     */
    fun changeEmail(currentPassword: String, newEmail: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, emailChanged = false) }
            repository.changeEmail(currentPassword, newEmail)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            emailChanged = true,
                            currentEmail = repository.currentEmail()
                        )
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
        }
    }

    fun logout() {
        repository.logout()
        _uiState.value = AuthUiState(currentEmail = null)
    }

    // ── State helpers ─────────────────────────────────────────────────────────

    /**
     * Dismisses the current [AuthUiState.error] after the View has displayed it
     * (e.g. via a Snackbar). Prevents the same error from re-appearing on recomposition.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Resets [AuthUiState.resetEmailSent] after the View has consumed the confirmation signal.
     */
    fun clearResetSent() {
        _uiState.update { it.copy(resetEmailSent = false) }
    }

    /**
     * Resets [AuthUiState.emailChanged] after the View has consumed the confirmation signal.
     */
    fun clearEmailChanged() {
        _uiState.update { it.copy(emailChanged = false) }
    }
}
