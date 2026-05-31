package com.example.evolvix.domain.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [FakeAuthRepository.changeEmail] and [FakeAuthRepository.currentEmail].
 *
 * The repository is pure Kotlin (no Android SDK, no Dispatcher) so standard JVM
 * JUnit suffices — no Robolectric or instrumentation runner needed.
 *
 * Coverage:
 * 1.  changeEmail succeeds with valid inputs → email swapped, currentEmail updated.
 * 2.  changeEmail fails when no user is logged in.
 * 3.  changeEmail fails when the current password is wrong.
 * 4.  changeEmail fails when the new email address is malformed.
 * 5.  changeEmail fails when the new email equals the current one.
 * 6.  changeEmail fails when the new email is already registered to another account.
 * 7.  After a successful changeEmail, the old email no longer works for login.
 * 8.  After a successful changeEmail, the new email + same password works for login.
 * 9.  currentEmail returns null when logged out.
 * 10. currentEmail returns the current address when logged in.
 * 11. register succeeds and logs in the new user.
 * 12. register rejects a duplicate email address.
 * 13. register persists the display name.
 * 14. login succeeds with correct credentials.
 * 15. login fails with wrong password.
 * 16. login fails for an unknown email.
 * 17. logout clears the session.
 * 18. logout allows subsequent re-login.
 * 19. changePassword succeeds with the correct old password.
 * 20. changePassword fails when no user is logged in.
 * 21. changePassword fails with an incorrect old password.
 * 22. Old password is invalidated after a successful changePassword.
 * 23. resetPassword succeeds for a registered address.
 * 24. resetPassword succeeds for an unknown address (no user enumeration).
 *
 * (Pattern: **Arrange-Act-Assert** — each test is fully self-contained.)
 */
class FakeAuthRepositoryTest {

    private lateinit var repo: FakeAuthRepository

    @Before
    fun setUp() {
        repo = FakeAuthRepository()
    }

    // ── 1. Happy path ─────────────────────────────────────────────────────────

    @Test
    fun `changeEmail succeeds with valid password and new address`() = runBlocking {
        repo.register("alice@example.com", "pass123")

        val result = repo.changeEmail("pass123", "bob@example.com")

        assertTrue("Expected success", result.isSuccess)
        assertEquals("currentEmail should reflect new address",
            "bob@example.com", repo.currentEmail())
    }

    // ── 2. No logged-in user ──────────────────────────────────────────────────

    @Test
    fun `changeEmail fails when no user is logged in`() = runBlocking {
        val result = repo.changeEmail("pass123", "bob@example.com")

        assertTrue("Expected failure", result.isFailure)
        assertTrue(
            result.exceptionOrNull()!!.message!!.contains("logged in", ignoreCase = true)
        )
    }

    // ── 3. Wrong current password ─────────────────────────────────────────────

    @Test
    fun `changeEmail fails with incorrect current password`() = runBlocking {
        repo.register("alice@example.com", "correct")

        val result = repo.changeEmail("wrong-password", "bob@example.com")

        assertTrue("Expected failure", result.isFailure)
        assertTrue(
            result.exceptionOrNull()!!.message!!.contains("incorrect", ignoreCase = true)
        )
        // Email must not have changed after a failed attempt.
        assertEquals("alice@example.com", repo.currentEmail())
    }

    // ── 4. Malformed new address ──────────────────────────────────────────────

    @Test
    fun `changeEmail rejects malformed email address`() = runBlocking {
        repo.register("alice@example.com", "pass123")

        val result = repo.changeEmail("pass123", "not-an-email")

        assertTrue("Expected failure", result.isFailure)
        assertTrue(
            result.exceptionOrNull()!!.message!!.contains("valid", ignoreCase = true)
        )
        assertEquals("Email must remain unchanged", "alice@example.com", repo.currentEmail())
    }

    // ── 5. New address equals current address ─────────────────────────────────

    @Test
    fun `changeEmail rejects new address equal to current one`() = runBlocking {
        repo.register("alice@example.com", "pass123")

        // The check should be case-insensitive (Alice@Example.COM == alice@example.com).
        val result = repo.changeEmail("pass123", "Alice@Example.COM")

        assertTrue("Expected failure", result.isFailure)
        assertTrue(
            result.exceptionOrNull()!!.message!!.contains("differ", ignoreCase = true)
        )
    }

    // ── 6. New address already in use ─────────────────────────────────────────

    @Test
    fun `changeEmail rejects address already registered to another account`() = runBlocking {
        repo.register("alice@example.com", "pass123")
        // Register a second account, then log back in as alice.
        repo.register("bob@example.com", "otherpass")
        repo.logout()
        repo.login("alice@example.com", "pass123")

        val result = repo.changeEmail("pass123", "bob@example.com")

        assertTrue("Expected failure — bob is already taken", result.isFailure)
        assertTrue(
            result.exceptionOrNull()!!.message!!.contains("already", ignoreCase = true)
        )
        assertEquals("alice@example.com", repo.currentEmail())
    }

    // ── 7. Old email no longer valid after change ─────────────────────────────

    @Test
    fun `old email can no longer log in after successful changeEmail`() = runBlocking {
        repo.register("alice@example.com", "pass123")
        repo.changeEmail("pass123", "bob@example.com")

        val loginWithOld = repo.login("alice@example.com", "pass123")

        assertTrue("Old address should be rejected", loginWithOld.isFailure)
    }

    // ── 8. New email works for login after change ─────────────────────────────

    @Test
    fun `new email and same password can log in after successful changeEmail`() = runBlocking {
        repo.register("alice@example.com", "pass123")
        repo.changeEmail("pass123", "bob@example.com")
        repo.logout()

        val loginWithNew = repo.login("bob@example.com", "pass123")

        assertTrue("New address should log in successfully", loginWithNew.isSuccess)
        assertEquals("bob@example.com", repo.currentEmail())
    }

    // ── 9. currentEmail — logged out ──────────────────────────────────────────

    @Test
    fun `currentEmail returns null when no user is logged in`() {
        assertNull(repo.currentEmail())
    }

    // ── 10. currentEmail — logged in ─────────────────────────────────────────

    @Test
    fun `currentEmail returns normalized lowercase address when logged in`() = runBlocking {
        // register() normalises to lowercase internally.
        repo.register("Alice@Example.COM", "pass123")

        assertEquals("alice@example.com", repo.currentEmail())
    }

    // ═══════════════════════════════════════════════════════════════════════
    // register() scenarios
    // ═══════════════════════════════════════════════════════════════════════

    // ── 11. register — happy path ─────────────────────────────────────────

    @Test
    fun `register succeeds and logs in the new user`() = runBlocking {
        val result = repo.register("carol@example.com", "securePass")

        assertTrue("Expected success", result.isSuccess)
        assertEquals("carol@example.com", repo.currentEmail())
    }

    // ── 12. register — duplicate email ────────────────────────────────────

    @Test
    fun `register fails when email is already in use`() = runBlocking {
        repo.register("carol@example.com", "pass1")

        val duplicate = repo.register("Carol@Example.COM", "pass2")

        assertTrue("Expected failure for duplicate", duplicate.isFailure)
        assertTrue(
            duplicate.exceptionOrNull()!!.message!!.contains("already", ignoreCase = true)
        )
    }

    // ── 13. register — displayName stored ────────────────────────────────

    @Test
    fun `register stores display name and returns it via currentDisplayName`() = runBlocking {
        repo.register("carol@example.com", "pass1", displayName = "Carol")

        assertEquals("Carol", repo.currentDisplayName())
    }

    // ═══════════════════════════════════════════════════════════════════════
    // login() scenarios
    // ═══════════════════════════════════════════════════════════════════════

    // ── 14. login — correct credentials ──────────────────────────────────

    @Test
    fun `login succeeds with correct email and password`() = runBlocking {
        repo.register("dave@example.com", "myPass")
        repo.logout()

        val result = repo.login("dave@example.com", "myPass")

        assertTrue("Expected success", result.isSuccess)
        assertEquals("dave@example.com", repo.currentEmail())
    }

    // ── 15. login — wrong password ────────────────────────────────────────

    @Test
    fun `login fails with wrong password`() = runBlocking {
        repo.register("dave@example.com", "myPass")
        repo.logout()

        val result = repo.login("dave@example.com", "wrongPass")

        assertTrue("Expected failure", result.isFailure)
        assertNull("Session must not be opened", repo.currentEmail())
    }

    // ── 16. login — unknown email ─────────────────────────────────────────

    @Test
    fun `login fails when email is not registered`() = runBlocking {
        val result = repo.login("nobody@example.com", "anyPass")

        assertTrue("Expected failure", result.isFailure)
        assertNull(repo.currentEmail())
    }

    // ═══════════════════════════════════════════════════════════════════════
    // logout() scenarios
    // ═══════════════════════════════════════════════════════════════════════

    // ── 17. logout — clears session ───────────────────────────────────────

    @Test
    fun `logout clears the current session`() = runBlocking {
        repo.register("eve@example.com", "pass123")

        repo.logout()

        assertNull("currentEmail must be null after logout", repo.currentEmail())
    }

    // ── 18. logout — allows re-login ──────────────────────────────────────

    @Test
    fun `logout allows subsequent login with same credentials`() = runBlocking {
        repo.register("eve@example.com", "pass123")
        repo.logout()

        val result = repo.login("eve@example.com", "pass123")

        assertTrue("Re-login after logout should succeed", result.isSuccess)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // changePassword() scenarios
    // ═══════════════════════════════════════════════════════════════════════

    // ── 19. changePassword — happy path ───────────────────────────────────

    @Test
    fun `changePassword succeeds with correct old password`() = runBlocking {
        repo.register("frank@example.com", "oldPass")

        val result = repo.changePassword("oldPass", "newPass")

        assertTrue("Expected success", result.isSuccess)
        // New password must work for login after a re-login.
        repo.logout()
        assertTrue(repo.login("frank@example.com", "newPass").isSuccess)
    }

    // ── 20. changePassword — no logged-in user ────────────────────────────

    @Test
    fun `changePassword fails when no user is logged in`() = runBlocking {
        val result = repo.changePassword("anyOld", "anyNew")

        assertTrue("Expected failure", result.isFailure)
        assertTrue(
            result.exceptionOrNull()!!.message!!.contains("logged in", ignoreCase = true)
        )
    }

    // ── 21. changePassword — wrong old password ───────────────────────────

    @Test
    fun `changePassword fails with incorrect old password`() = runBlocking {
        repo.register("frank@example.com", "realPass")

        val result = repo.changePassword("wrongPass", "newPass")

        assertTrue("Expected failure", result.isFailure)
        assertTrue(
            result.exceptionOrNull()!!.message!!.contains("incorrect", ignoreCase = true)
        )
        // Old password must still work after a rejected attempt.
        repo.logout()
        assertTrue(repo.login("frank@example.com", "realPass").isSuccess)
    }

    // ── 22. changePassword — old password invalidated ─────────────────────

    @Test
    fun `old password no longer works after successful changePassword`() = runBlocking {
        repo.register("frank@example.com", "oldPass")
        repo.changePassword("oldPass", "newPass")
        repo.logout()

        val loginWithOld = repo.login("frank@example.com", "oldPass")

        assertTrue("Old password must be rejected", loginWithOld.isFailure)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // resetPassword() scenarios
    // ═══════════════════════════════════════════════════════════════════════

    // ── 23. resetPassword — registered address ────────────────────────────

    @Test
    fun `resetPassword succeeds for a registered email address`() = runBlocking {
        repo.register("grace@example.com", "pass123")
        repo.logout()

        val result = repo.resetPassword("grace@example.com")

        assertTrue("Expected success", result.isSuccess)
    }

    // ── 24. resetPassword — unregistered address (no user enumeration) ────

    @Test
    fun `resetPassword succeeds even for an unknown address to prevent user enumeration`() = runBlocking {
        // Matches Firebase behaviour: the call always succeeds so attackers
        // cannot probe which addresses are registered.
        val result = repo.resetPassword("unknown@example.com")

        assertTrue("Expected success (no user enumeration)", result.isSuccess)
    }
}
