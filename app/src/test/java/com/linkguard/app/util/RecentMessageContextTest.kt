package com.linkguard.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentMessageContextTest {

    private class Clock(var t: Long = 0L) { fun now(): Long = t }

    @Test
    fun `tapped link finds the message it arrived in`() {
        val context = RecentMessageContext(now = { 0L })
        context.record("https://slot.wintayo.com", "First Deposit Bonus up to 5,000")
        assertEquals("First Deposit Bonus up to 5,000", context.lookup("https://slot.wintayo.com"))
    }

    @Test
    fun `scheme case and trailing slash do not matter`() {
        val context = RecentMessageContext(now = { 0L })
        context.record("https://slot.wintayo.com", "msg")
        assertEquals("msg", context.lookup("http://slot.wintayo.com/"))
        assertEquals("msg", context.lookup("HTTPS://Slot.Wintayo.com"))
        assertEquals("msg", context.lookup("https://slot.wintayo.com#top"))
    }

    @Test
    fun `different host or path is not matched`() {
        val context = RecentMessageContext(now = { 0L })
        context.record("https://slot.wintayo.com/promo", "msg")
        assertNull(context.lookup("https://slot.wintayo.com"))
        assertNull(context.lookup("https://slot.wintayo.com/other"))
        assertNull(context.lookup("https://wintayo.com/promo"))
    }

    @Test
    fun `entry expires after the ttl`() {
        val clock = Clock(0L)
        val context = RecentMessageContext(ttlMs = 1000L, now = clock::now)
        context.record("https://a.example", "msg")
        clock.t = 999L
        assertEquals("msg", context.lookup("https://a.example"))
        clock.t = 1000L
        assertNull(context.lookup("https://a.example"))
    }

    @Test
    fun `newest message for the same link wins`() {
        val context = RecentMessageContext(now = { 0L })
        context.record("https://a.example", "old")
        context.record("https://a.example/", "new")
        assertEquals("new", context.lookup("https://a.example"))
    }

    @Test
    fun `oldest entry is evicted when full`() {
        val context = RecentMessageContext(maxEntries = 2, now = { 0L })
        context.record("https://a.example", "a")
        context.record("https://b.example", "b")
        context.record("https://c.example", "c")
        assertNull(context.lookup("https://a.example"))
        assertEquals("b", context.lookup("https://b.example"))
        assertEquals("c", context.lookup("https://c.example"))
    }

    @Test
    fun `unknown link returns null`() {
        assertNull(RecentMessageContext(now = { 0L }).lookup("https://never.example"))
    }
}
