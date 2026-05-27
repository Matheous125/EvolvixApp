package com.example.evolvix.domain.auth

/**
 * Repository contract for all authentication operations.
 *
 * This interface follows the **Repository + Dependency Inversion** pattern:
 * callers (AuthViewModel, NavGraph) depend only on this abstraction, never on a
 * concrete implementation. Phase 9 wires [FakeAuthRepository]; Phase 10 swaps in
 * `FirebaseAuthRepository` without touching any ViewModel code (Liskov substitution).
 *
 * All suspend functions return [Result] so the ViewModel can map success/failure to
 * [AuthUiState] without catching exceptions itself.
 */
interface AuthRepository {

    /**
     * Authenticates an existing user with [email] and [password].
     *
     * @return [Result.success] on success; [Result.failure] with a descriptive
     * exception on invalid credentials or network errors.
     */
    suspend fun login(email: String, password: String): Result<Unit>

    /**
     * Creates a new account with [email] and [password].
     *
     * @return [Result.success] on success; [Result.failure] if the email is already
     * in use or the password does not meet strength requirements.
     */
    suspend fun register(email: String, password: String): Result<Unit>

    /**
     * Sends a password-reset e-mail to [email].
     *
     * @return [Result.success] once the request is dispatched; [Result.failure] if
     * the e-mail address is not registered.
     */
    suspend fun resetPassword(email: String): Result<Unit>

    /**
     * Updates the currently authenticated user's password to [newPassword].
     *
     * Requires the user to already be logged in and to supply their [oldPassword]
     * for verification. In Firebase (Phase 10) this triggers
     * `reauthenticateWithCredential` before `updatePassword`.
     *
     * @return [Result.success] on success; [Result.failure] with a descriptive
     * exception if [oldPassword] is wrong or the user is not logged in.
     */
    suspend fun changePassword(oldPassword: String, newPassword: String): Result<Unit>

    /**
     * Updates the currently authenticated user's e-mail address to [newEmail] after
     * verifying [currentPassword].
     *
     * In Firebase (Phase 10) the implementation must:
     *  1. `reauthenticateWithCredential(EmailAuthProvider.getCredential(currentEmail, currentPassword))`
     *  2. `verifyBeforeUpdateEmail(newEmail)` — the modern (post-2023) API; it dispatches
     *     a verification link to the new address and only flips the auth record once
     *     the link is followed. The legacy `updateEmail()` is deprecated and should not
     *     be used.
     *
     * The Phase-9 [FakeAuthRepository] performs the password check and swaps the
     * address synchronously (no verification round-trip), which is enough to exercise
     * every UI state during development.
     *
     * @return [Result.success] on success; [Result.failure] if no user is logged in,
     * the password is wrong, the new address is malformed, or the address is already
     * registered to another account.
     */
    suspend fun changeEmail(currentPassword: String, newEmail: String): Result<Unit>

    /**
     * Returns the e-mail address of the currently authenticated user, or `null` when
     * logged out. Provided as a synchronous read because the Settings screen needs it
     * for the "Change e-mail" subtitle on every recomposition without launching a
     * coroutine.
     */
    fun currentEmail(): String?

    /**
     * Signs out the currently authenticated user and clears any cached credentials.
     *
     * This is a fire-and-forget operation — it cannot fail from the caller's perspective.
     */
    fun logout()
}
