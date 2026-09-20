package com.linkguard.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertDeduperTest {

    private class Clock(var t: Long = 0L) { fun now(): Long = t }

    @Test
    fun `first alert for a key is allowed`() {
        val deduper = AlertDeduper(cooldownMs = 1000L, now = { 0L })
        assertTrue(deduper.shouldAlert("https://evil.xyz|DANGER"))
    }

    @Test
    fun `repeat within cooldown is suppressed`() {
        val deduper = AlertDeduper(cooldownMs = 1000L, now = { 0L })
        assertTrue(deduper.shouldAlert("k"))
        assertFalse(deduper.shouldAlert("k"))
        assertFalse(deduper.shouldAlert("k"))
    }

    @Test
    fun `repeat after cooldown is allowed again`() {
        val clock = Clock(0L)
        val deduper = AlertDeduper(cooldownMs = 1000L, now = clock::now)
        assertTrue(deduper.shouldAlert("k"))
        clock.t = 1000L
        assertTrue(deduper.shouldAlert("k"))
    }

    @Test
    fun `different keys are independent`() {
        val deduper = AlertDeduper(cooldownMs = 1000L, now = { 0L })
        assertTrue(deduper.shouldAlert("a"))
        assertTrue(deduper.shouldAlert("b"))
        assertFalse(deduper.shouldAlert("a"))
    }

    @Test
    fun `memory stays bounded and oldest entries are evicted`() {
        val clock = Clock(0L)
        val deduper = AlertDeduper(cooldownMs = 1_000_000L, maxEntries = 3, now = clock::now)
        listOf("a", "b", "c", "d").forEach { clock.t += 1; assertTrue(deduper.shouldAlert(it)) }
        // "a" was evicted to make room for "d", so it alerts again; "d" is still remembered.
        assertTrue(deduper.shouldAlert("a"))
        assertFalse(deduper.shouldAlert("d"))
    }
}
