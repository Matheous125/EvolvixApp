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
}
