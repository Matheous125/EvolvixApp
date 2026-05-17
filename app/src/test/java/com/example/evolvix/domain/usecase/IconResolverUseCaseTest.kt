package com.example.evolvix.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [IconResolverUseCase].
 *
 * Each test verifies one concern in isolation; no Android SDK, no Room — pure JVM.
 *
 * Coverage targets:
 * - Tier 1: one representative keyword from each of the 16 named categories
 * - Case-insensitivity (upper/mixed case input)
 * - Substring matching (keyword embedded inside a longer word)
 * - Priority ordering: mindfulness wins over health for "meditate"
 * - Priority ordering: sleep wins over health for "sleep"
 * - Tier 2 fallback for an unrecognised name returns the default "⭐" emoji
 * - Empty string input returns the fallback (does not crash)
 * - Whitespace-only input returns the fallback (does not crash)
 */
class IconResolverUseCaseTest {

    private lateinit var useCase: IconResolverUseCase

    @Before
    fun setUp() {
        useCase = IconResolverUseCase()
    }

    // ── Tier 1 — one representative per category ──────────────────────────────

    @Test
    fun `fitness keyword returns weight-lifting emoji`() {
        assertEquals("💪", useCase("morning run"))
    }

    @Test
    fun `gym keyword resolves to fitness`() {
        assertEquals("💪", useCase("Go to gym"))
    }

    @Test
    fun `mindfulness keyword returns meditation emoji`() {
        assertEquals("🧘", useCase("meditate 10 min"))
    }

    @Test
    fun `sleep keyword returns sleep emoji`() {
        assertEquals("😴", useCase("sleep 8 hours"))
    }

    @Test
    fun `health keyword returns heart emoji`() {
        assertEquals("❤️", useCase("drink water"))
    }

    @Test
    fun `reading keyword returns book emoji`() {
        assertEquals("📖", useCase("read a book"))
    }

    @Test
    fun `writing keyword returns pencil emoji`() {
        assertEquals("✍️", useCase("write in journal"))
    }

    @Test
    fun `learning keyword returns books emoji`() {
        assertEquals("📚", useCase("study for exam"))
    }

    @Test
    fun `music keyword returns note emoji`() {
        assertEquals("🎵", useCase("practice guitar"))
    }

    @Test
    fun `creative keyword returns palette emoji`() {
        assertEquals("🎨", useCase("sketch daily"))
    }

    @Test
    fun `social keyword returns people emoji`() {
        assertEquals("👥", useCase("call a friend"))
    }

    @Test
    fun `productivity keyword returns check emoji`() {
        assertEquals("✅", useCase("plan the day"))
    }

    @Test
    fun `finance keyword returns money emoji`() {
        assertEquals("💰", useCase("track spending"))
    }

    @Test
    fun `food keyword returns apple emoji`() {
        assertEquals("🍎", useCase("cook dinner"))
    }

    @Test
    fun `cleaning keyword returns broom emoji`() {
        assertEquals("🧹", useCase("clean the kitchen"))
    }

    @Test
    fun `nature keyword returns plant emoji`() {
        assertEquals("🌿", useCase("go for a hike"))
    }

    @Test
    fun `pet keyword returns paw emoji`() {
        assertEquals("🐾", useCase("feed the cat"))
    }

    // ── Case-insensitivity ────────────────────────────────────────────────────

    @Test
    fun `uppercase input resolves correctly`() {
        assertEquals("💪", useCase("WORKOUT"))
    }

    @Test
    fun `mixed case input resolves correctly`() {
        assertEquals("🧘", useCase("Morning Meditation"))
    }

    // ── Substring matching ────────────────────────────────────────────────────

    @Test
    fun `keyword embedded in longer word still matches`() {
        // "running" contains "run"
        assertEquals("💪", useCase("go running at 6am"))
    }

    @Test
    fun `partial keyword meditat matches meditate and meditating`() {
        assertEquals("🧘", useCase("meditating before bed"))
    }

    // ── Priority ordering ─────────────────────────────────────────────────────

    @Test
    fun `meditate wins over health even if health keywords also present`() {
        // "meditate and drink water" has both mindfulness and health keywords.
        // Mindfulness is higher in KEYWORD_MAP so it must win.
        assertEquals("🧘", useCase("meditate and drink water"))
    }

    @Test
    fun `sleep wins over health for bedtime habit`() {
        // "bedtime" is in sleep category; health is lower priority.
        assertEquals("😴", useCase("bedtime routine"))
    }

    @Test
    fun `reading beats writing when name contains both`() {
        // "read" appears before "writ" in the map, so reading emoji wins.
        assertEquals("📖", useCase("read and write every day"))
    }

    // ── Tier 2 fallback ───────────────────────────────────────────────────────

    @Test
    fun `unrecognised habit name returns star fallback`() {
        assertEquals("⭐", useCase("xyzzy habit"))
    }

    @Test
    fun `empty string returns star fallback`() {
        assertEquals("⭐", useCase(""))
    }

    @Test
    fun `whitespace only string returns star fallback`() {
        assertEquals("⭐", useCase("   "))
    }

    // ── Sanity — distinct categories produce different emojis ─────────────────

    @Test
    fun `fitness and sleep produce different emojis`() {
        assertNotEquals(useCase("workout"), useCase("sleep"))
    }

    @Test
    fun `reading and writing produce different emojis`() {
        assertNotEquals(useCase("read a book"), useCase("write a blog post"))
    }
}
