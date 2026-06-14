package com.linkguard.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanRateLimiterTest {

    /** Mutable clock so we can advance time deterministically. */
    private class Clock(var t: Long = 0L) { fun now(): Long = t }

    @Test
    fun `allows up to the cap within a window`() {
        val limiter = ScanRateLimiter(maxPerWindow = 3, windowMs = 1000L, now = { 0L })
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
    }

    @Test
    fun `blocks once the cap is reached in the same window`() {
        val limiter = ScanRateLimiter(maxPerWindow = 3, windowMs = 1000L, now = { 0L })
        repeat(3) { assertTrue(limiter.tryAcquire()) }
        assertFalse(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `allows again after the window slides forward`() {
        val clock = Clock(0L)
        val limiter = ScanRateLimiter(maxPerWindow = 2, windowMs = 1000L, now = clock::now)
        assertTrue(limiter.tryAcquire())   // t=0
        assertTrue(limiter.tryAcquire())   // t=0
        assertFalse(limiter.tryAcquire())  // t=0, full

        clock.t = 1000L                    // first two have aged out (>= windowMs)
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `partial slide frees exactly the aged-out slots`() {
        val clock = Clock(0L)
        val limiter = ScanRateLimiter(maxPerWindow = 2, windowMs = 1000L, now = clock::now)
        assertTrue(limiter.tryAcquire())   // t=0
        clock.t = 500L
        assertTrue(limiter.tryAcquire())   // t=500
        assertFalse(limiter.tryAcquire())  // t=500, full (both still in window)

        clock.t = 1000L                    // only the t=0 entry aged out
        assertTrue(limiter.tryAcquire())   // one slot freed
        assertFalse(limiter.tryAcquire())  // t=500 entry still occupies the other slot
    }
}
