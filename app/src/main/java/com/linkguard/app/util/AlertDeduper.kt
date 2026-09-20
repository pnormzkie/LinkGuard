package com.linkguard.app.util

/**
 * Suppresses repeat threat alerts for the same link on the AUTOMATIC notification path.
 *
 * Messaging apps and Gmail re-post a notification on every update, and the orchestrator's
 * verdict cache is skipped whenever a provider fails, so the same link could otherwise
 * stack a new alarm (and re-launch the full-screen alert) each time. Scans still run; this
 * only limits how often the user is alerted about one key within [cooldownMs].
 *
 * `now` is injected so expiry is testable without real time passing.
 */
class AlertDeduper(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val now: () -> Long = System::currentTimeMillis,
) {
    // Insertion-ordered, so the oldest alert is evicted first when the map is full.
    private val lastAlertAt = LinkedHashMap<String, Long>()

    /** @return true if the user should be alerted for [key] now (and records it). */
    @Synchronized
    fun shouldAlert(key: String): Boolean {
        val current = now()
        val last = lastAlertAt[key]
        if (last != null && current - last < cooldownMs) return false

        lastAlertAt.remove(key)
        lastAlertAt[key] = current
        while (lastAlertAt.size > maxEntries) {
            lastAlertAt.remove(lastAlertAt.keys.first())
        }
        return true
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 5 * 60_000L
        const val DEFAULT_MAX_ENTRIES = 200
    }
}
