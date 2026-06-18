package com.linkguard.app.util

import android.content.Context

/**
 * Persistent per-day scan cap for the AUTOMATIC notification scan path.
 *
 * The in-memory [ScanRateLimiter] guards acute bursts but resets on process restart, so it
 * can't hold a 24-hour budget against a provider's daily quota (e.g. VirusTotal's ~500/day
 * free tier). This counter persists the day + count so the cap survives restarts and rolls
 * over at UTC midnight.
 *
 * Scope mirrors [ScanRateLimiter]: only the listener service consults it; tapped/manual scans
 * are never capped. The clock and storage are injected so the day-rollover logic is unit-
 * testable without Android or real time passing.
 */
class DailyScanCounter(
    private val store: Store,
    private val maxPerDay: Int = DEFAULT_MAX_PER_DAY,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** Minimal persistence seam: (dayEpoch, count). */
    interface Store {
        fun read(): Pair<Long, Int>
        fun write(dayEpoch: Long, count: Int)
    }

    /** @return true if a scan is allowed today (and records it); false once the cap is hit. */
    @Synchronized
    fun tryAcquire(): Boolean {
        val today = now() / DAY_MS
        val (storedDay, storedCount) = store.read()
        val count = if (storedDay == today) storedCount else 0 // new day resets the count
        if (count >= maxPerDay) {
            if (storedDay != today) store.write(today, 0) // persist the rollover even when blocked
            return false
        }
        store.write(today, count + 1)
        return true
    }

    companion object {
        // Kept below VirusTotal's ~500/day free tier with headroom for the other providers.
        const val DEFAULT_MAX_PER_DAY = 400
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val PREFS_NAME = "linkguard_quota_prefs"
        private const val KEY_DAY = "scan_day_epoch"
        private const val KEY_COUNT = "scan_count"

        /** Builds a SharedPreferences-backed counter for production use. */
        fun create(context: Context, maxPerDay: Int = DEFAULT_MAX_PER_DAY): DailyScanCounter {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val store = object : Store {
                override fun read(): Pair<Long, Int> =
                    prefs.getLong(KEY_DAY, -1L) to prefs.getInt(KEY_COUNT, 0)

                override fun write(dayEpoch: Long, count: Int) {
                    prefs.edit().putLong(KEY_DAY, dayEpoch).putInt(KEY_COUNT, count).apply()
                }
            }
            return DailyScanCounter(store, maxPerDay)
        }
    }
}
