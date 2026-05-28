package com.example.evolvix.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.evolvix.data.local.AppDatabase
import com.example.evolvix.domain.auth.AuthRepository
import com.example.evolvix.domain.sync.SyncController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * @property syncController Bidirectional sync mediator; called after login to pull the user's
 *                          Firestore data into the local Room database.
 * @property database       Local Room database instance; [clearAllTables] is called on logout
 *                          to ensure the next user starts with an empty local store.
 * @property settingsViewModel Shared settings VM; [SettingsViewModel.reloadDisplayName] is
 *                             called after login/logout so the profile header reflects the
 *                             correct account's display name.
 */
class AuthViewModel(
    private val repository: AuthRepository,
    private val syncController: SyncController,
    private val database: AppDatabase,
    private val settingsViewModel: SettingsViewModel
) : ViewModel() {

    // Seed the state with whatever account (if any) the repository currently considers
    // signed in. Phase 9's FakeAuthRepository starts with no session, but exposing
    // currentEmail() up-front lets Phase 10's FirebaseAuthRepository hydrate the UI
    // from a persistent session without an extra round-trip.
    private val _uiState = MutableStateFlow(
        AuthUiState(
            currentEmail    = repository.currentEmail(),
            // Hydrate from a cached Firebase session so the app goes straight to Habits
            // on cold-start instead of always showing Login. For FirebaseAuthRepository,
            // currentEmail() is non-null iff auth.currentUser is non-null (valid session).
            isAuthenticated = repository.currentEmail() != null
        )
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
                    // Wipe any stale local data from a previous session (or from "offline"
                    // usage while no user was signed in) BEFORE pulling this user's Firestore
                    // data. Without this, leftover Room rows from a prior session can be
                    // pushed to the new user's Firestore by SyncController.syncAchievements
                    // (its push step reads ALL local unlocked achievements and writes them
                    // under the current uid — contaminating a fresh or different account).
                    withContext(Dispatchers.IO) { database.clearAllTables() }
                    // Now pull this user's habits, completions, and achievements from
                    // Firestore. isLoading stays true during the network call.
                    runCatching { syncController.sync() }
                    settingsViewModel.reloadDisplayName()
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
     *
     * [displayName] is persisted both on the Firebase user profile (so it survives a
     * reinstall / sign-in on another device) and locally via [SettingsViewModel.setDisplayName]
     * so the Settings screen renders the correct name on the very next recomposition,
     * without waiting for an extra Firebase round-trip.
     */
    fun register(email: String, password: String, displayName: String = "") {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.register(email, password, displayName)
                .onSuccess {
                    // Clear any offline/stale Room data accumulated before registration.
                    // A new account has no Firestore data to pull, but without this step
                    // AchievementsViewModel would immediately evaluate the leftover habits
                    // and push spurious achievement rows to the brand-new user's Firestore.
                    withContext(Dispatchers.IO) { database.clearAllTables() }
                    // Persist the entered name under the UID-scoped key BEFORE we reload,
                    // so the StateFlow picks it up in the same step.
                    val trimmed = displayName.trim()
                    if (trimmed.isNotEmpty()) settingsViewModel.setDisplayName(trimmed)
                    else settingsViewModel.reloadDisplayName()
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
        // 1. Best-effort flush of any habits / completions that were created during
        //    this session but never made it to Firestore (the periodic SyncWorker
        //    fires only every hour). Running while still authenticated guarantees
        //    SyncController has a valid uid() to write under. We swallow failures
        //    so a network hiccup never blocks the user from logging out.
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { syncController.sync() } }

            // 2. Firebase signOut — from this point currentUser is null. Subsequent
            //    Firebase / Firestore calls in flight will see the signed-out state.
            repository.logout()

            // 3. Clear local Room tables so the next user starts with an empty store
            //    and only sees their own Firestore data. Done BEFORE flipping the
            //    auth state so the AchievementsViewModel observer never sees a
            //    transient pair of (signed-out + stale habits) which would otherwise
            //    cause spurious banner emissions and cross-user achievement leakage
            //    (the just-re-emitted rows would be pushed to the next user's Firestore
            //    on the next login sync).
            withContext(Dispatchers.IO) { database.clearAllTables() }

            // 4. Refresh the display-name StateFlow (now reads the logged-out default)
            //    and flip auth state so the nav-graph guard redirects to Login.
            settingsViewModel.reloadDisplayName()
            _uiState.value = AuthUiState(currentEmail = null, isAuthenticated = false)
        }
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
