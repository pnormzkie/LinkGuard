package com.linkguard.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyScanCounterTest {

    private val dayMs = 24L * 60 * 60 * 1000

    /** In-memory store so the rollover logic is exercised without Android SharedPreferences. */
    private class MemStore(var day: Long = -1L, var count: Int = 0) : DailyScanCounter.Store {
        override fun read(): Pair<Long, Int> = day to count
        override fun write(dayEpoch: Long, count: Int) {
            this.day = dayEpoch; this.count = count
        }
    }

    @Test
    fun `allows up to the daily cap then blocks`() {
        val counter = DailyScanCounter(MemStore(), maxPerDay = 3, now = { dayMs * 5 })
        assertTrue(counter.tryAcquire())
        assertTrue(counter.tryAcquire())
        assertTrue(counter.tryAcquire())
        assertFalse(counter.tryAcquire())
        assertFalse(counter.tryAcquire())
    }

    @Test
    fun `cap resets when the day rolls over`() {
        val clock = longArrayOf(dayMs * 5)
        val counter = DailyScanCounter(MemStore(), maxPerDay = 2, now = { clock[0] })
        assertTrue(counter.tryAcquire())
        assertTrue(counter.tryAcquire())
        assertFalse(counter.tryAcquire()) // day 5 full

        clock[0] = dayMs * 6 // next day
        assertTrue(counter.tryAcquire())
        assertTrue(counter.tryAcquire())
        assertFalse(counter.tryAcquire()) // day 6 full
    }

    @Test
    fun `count persists across counter instances sharing a store`() {
        val store = MemStore()
        DailyScanCounter(store, maxPerDay = 2, now = { dayMs * 5 }).let {
            assertTrue(it.tryAcquire())
        }
        // A fresh instance (e.g. after a process restart) reads the same persisted store.
        val restarted = DailyScanCounter(store, maxPerDay = 2, now = { dayMs * 5 })
        assertTrue(restarted.tryAcquire())
        assertFalse(restarted.tryAcquire())
    }
}
