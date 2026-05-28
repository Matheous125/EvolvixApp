package com.example.evolvix.data.auth

import com.example.evolvix.domain.auth.AuthRepository
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

/**
 * Production implementation of [AuthRepository] backed by Firebase Authentication.
 *
 * Injected in [MainActivity] (Phase 10) in place of [FakeAuthRepository].
 * No ViewModel or screen code changes are required — this is the Liskov-substitution
 * payoff of the Repository + Dependency Inversion pattern established in Phase 9.
 *
 * All suspend functions wrap Firebase Task callbacks via [kotlinx.coroutines.tasks.await]
 * and map exceptions to [Result.failure] so the ViewModel never has to catch anything.
 *
 * Note on [changeEmail]: uses `verifyBeforeUpdateEmail` (the post-2023 API). Firebase
 * sends a verification link to the new address; the auth record is only updated once
 * the user clicks it. The function returns [Result.success] immediately after the
 * verification e-mail is dispatched — the caller should inform the user to check their
 * inbox. Requires "Email Enumeration Protection" to be OFF in the Firebase console
 * (Authentication → Settings → User account privacy) for the request to go through.
 */
class FirebaseAuthRepository : AuthRepository {

    /** Singleton FirebaseAuth instance — re-used across all operations. */
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    /**
     * Signs in with [email] and [password] via Firebase Authentication.
     * Maps any FirebaseAuthException to [Result.failure] with a human-readable message.
     */
    override suspend fun login(email: String, password: String): Result<Unit> = runCatching {
        auth.signInWithEmailAndPassword(email, password).await()
        Unit
    }

    /**
     * Creates a new account with [email] and [password].
     * Firebase enforces minimum password length (≥6 chars) server-side.
     */
    override suspend fun register(email: String, password: String): Result<Unit> = runCatching {
        auth.createUserWithEmailAndPassword(email, password).await()
        Unit
    }

    /**
     * Dispatches a password-reset e-mail to [email].
     * Returns success even if the address is not registered, matching Firebase's
     * default behaviour which avoids user enumeration.
     */
    override suspend fun resetPassword(email: String): Result<Unit> = runCatching {
        auth.sendPasswordResetEmail(email).await()
        Unit
    }

    /**
     * Changes the current user's password.
     *
     * Reauthenticates first with [oldPassword] to satisfy Firebase's requirement that
     * sensitive operations are performed on a recently authenticated session.
     */
    override suspend fun changePassword(oldPassword: String, newPassword: String): Result<Unit> = runCatching {
        val user = auth.currentUser
            ?: throw IllegalStateException("No user is currently signed in.")
        val credential = EmailAuthProvider.getCredential(user.email!!, oldPassword)
        user.reauthenticate(credential).await()
        user.updatePassword(newPassword).await()
        Unit
    }

    /**
     * Requests an e-mail address change via the modern [verifyBeforeUpdateEmail] API.
     *
     * Firebase sends a verification link to [newEmail]; the auth record is only updated
     * after the user clicks it. The caller should show a "check your inbox" message.
     *
     * Reauthenticates first — required for any sensitive account mutation.
     */
    override suspend fun changeEmail(currentPassword: String, newEmail: String): Result<Unit> = runCatching {
        val user = auth.currentUser
            ?: throw IllegalStateException("No user is currently signed in.")
        val credential = EmailAuthProvider.getCredential(user.email!!, currentPassword)
        user.reauthenticate(credential).await()
        user.verifyBeforeUpdateEmail(newEmail).await()
        Unit
    }

    /**
     * Returns the e-mail of the currently signed-in user, or `null` when logged out.
     * Synchronous — safe to call during recomposition.
     */
    override fun currentEmail(): String? = auth.currentUser?.email

    /**
     * Signs out the current user and clears the local Firebase session cache.
     */
    override fun logout() {
        auth.signOut()
    }
}
