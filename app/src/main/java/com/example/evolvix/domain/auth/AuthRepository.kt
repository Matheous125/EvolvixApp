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
     * Requires the user to already be logged in. In Firebase this triggers
     * re-authentication if the session is stale.
     *
     * @return [Result.success] on success; [Result.failure] on auth or network errors.
     */
    suspend fun changePassword(newPassword: String): Result<Unit>

    /**
     * Signs out the currently authenticated user and clears any cached credentials.
     *
     * This is a fire-and-forget operation — it cannot fail from the caller's perspective.
     */
    fun logout()
}
