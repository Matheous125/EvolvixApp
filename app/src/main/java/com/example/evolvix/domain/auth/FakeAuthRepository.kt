package com.example.evolvix.domain.auth

/**
 * In-memory fake implementation of [AuthRepository] used during development (Phase 9).
 *
 * This acts as a **Null Object / Stub** — all operations succeed instantly with no network
 * calls. Phase 10 replaces this with `FirebaseAuthRepository` via Liskov substitution;
 * no ViewModel code changes are required.
 *
 * Behavioural rules (enough to exercise the auth UI):
 * - [login]: succeeds for any registered account; fails with a clear exception otherwise.
 * - [register]: succeeds for any new e-mail; fails if the address is already registered.
 * - [resetPassword]: always succeeds (simulates e-mail dispatch).
 * - [changePassword]: succeeds when a user is logged in; fails otherwise.
 * - [logout]: clears the in-memory session.
 */
class FakeAuthRepository : AuthRepository {

    // ── In-memory state ───────────────────────────────────────────────────────

    /** Registered accounts: email → password. */
    private val accounts = mutableMapOf<String, String>()

    /** The e-mail of the currently authenticated user, or null when logged out. */
    private var loggedInEmail: String? = null

    // ── AuthRepository implementation ─────────────────────────────────────────

    override suspend fun login(email: String, password: String): Result<Unit> {
        val storedPassword = accounts[email.lowercase()]
        return when {
            storedPassword == null ->
                Result.failure(IllegalArgumentException("No account found for $email."))
            storedPassword != password ->
                Result.failure(IllegalArgumentException("Incorrect password."))
            else -> {
                loggedInEmail = email.lowercase()
                Result.success(Unit)
            }
        }
    }

    override suspend fun register(email: String, password: String): Result<Unit> {
        val key = email.lowercase()
        return if (accounts.containsKey(key)) {
            Result.failure(IllegalStateException("An account with this e-mail already exists."))
        } else {
            accounts[key] = password
            loggedInEmail = key
            Result.success(Unit)
        }
    }

    override suspend fun resetPassword(email: String): Result<Unit> {
        // Always succeeds — simulates the e-mail being dispatched regardless of
        // whether the address exists (matches Firebase's behaviour to avoid user enumeration).
        return Result.success(Unit)
    }

    override suspend fun changePassword(oldPassword: String, newPassword: String): Result<Unit> {
        val email = loggedInEmail
            ?: return Result.failure(IllegalStateException("No user is currently logged in."))
        val storedPassword = accounts[email]
        if (storedPassword != oldPassword) {
            return Result.failure(IllegalArgumentException("Current password is incorrect."))
        }
        accounts[email] = newPassword
        return Result.success(Unit)
    }

    override suspend fun changeEmail(currentPassword: String, newEmail: String): Result<Unit> {
        val email = loggedInEmail
            ?: return Result.failure(IllegalStateException("No user is currently logged in."))
        val storedPassword = accounts[email]
            ?: return Result.failure(IllegalStateException("No user is currently logged in."))
        if (storedPassword != currentPassword) {
            return Result.failure(IllegalArgumentException("Current password is incorrect."))
        }
        val normalised = newEmail.trim().lowercase()
        if (!EMAIL_REGEX.matches(normalised)) {
            return Result.failure(IllegalArgumentException("Enter a valid e-mail address."))
        }
        if (normalised == email) {
            return Result.failure(IllegalArgumentException("New e-mail must differ from the current one."))
        }
        if (accounts.containsKey(normalised)) {
            return Result.failure(IllegalStateException("An account with this e-mail already exists."))
        }
        // Atomic swap — re-key the account map under the new address.
        accounts.remove(email)
        accounts[normalised] = storedPassword
        loggedInEmail = normalised
        return Result.success(Unit)
    }

    override fun currentEmail(): String? = loggedInEmail

    override fun logout() {
        loggedInEmail = null
    }

    private companion object {
        // RFC-5322 is overkill for a dev stub — this matches the same subset the
        // login/register screens validate with so the fake repo and the UI agree.
        private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    }
}
