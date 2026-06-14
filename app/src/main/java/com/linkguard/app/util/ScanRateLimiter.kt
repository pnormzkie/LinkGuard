package com.linkguard.app.util

/**
 * Sliding-window rate limiter for the AUTOMATIC notification scan path.
 *
 * Phase 3 guardrail: once links are scanned from any app (Phase 1/2), a flood of
 * distinct URLs — a spammy app, or many chatty apps at once — could otherwise blow the
 * reputation providers' daily quotas (e.g. VirusTotal free tier). This caps how many
 * scans the listener service kicks off per time window.
 *
 * Scope: only the notification listener uses this. User-initiated scans (tapped links,
 * the manual scan box) do NOT go through here and are never limited.
 *
 * Repeats of the SAME URL are already coalesced by ScanOrchestrator's 15-minute verdict
 * cache (a cache hit makes no network call), so this limiter deliberately only guards the
 * overall rate, not per-URL duplication.
 *
 * `now` is injected so expiry is testable without real time passing.
 */
class ScanRateLimiter(
    private val maxPerWindow: Int = DEFAULT_MAX_PER_WINDOW,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val timestamps = ArrayDeque<Long>()

    /**
     * @return true if a scan is allowed now (and records it); false if the window is full.
     */
    @Synchronized
    fun tryAcquire(): Boolean {
        val current = now()
        // Drop timestamps that have aged out of the window.
        while (timestamps.isNotEmpty() && current - timestamps.first() >= windowMs) {
            timestamps.removeFirst()
        }
        if (timestamps.size >= maxPerWindow) return false
        timestamps.addLast(current)
        return true
    }

    companion object {
        const val DEFAULT_MAX_PER_WINDOW = 15
        const val DEFAULT_WINDOW_MS = 60_000L
    }
}
