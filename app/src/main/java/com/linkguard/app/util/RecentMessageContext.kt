package com.linkguard.app.util

/**
 * Remembers which notification text each link arrived in, so tapping that link scans it with
 * the same message context. Without this a gambling or smishing SMS was flagged on the
 * notification path but the tap of the very same link scanned the bare URL and read SAFE.
 *
 * Memory only: message text never reaches disk, and a process restart just falls back to a
 * plain URL scan. Links match by host + path + query, ignoring scheme, case, fragment and a
 * trailing slash ("slot.wintayo.com" in the SMS vs "http://slot.wintayo.com/" when tapped).
 *
 * `now` is injected so expiry is testable without real time passing.
 */
class RecentMessageContext(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val messageText: String, val recordedAt: Long)

    // Insertion-ordered, so the oldest link is evicted first when the map is full.
    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun record(url: String, messageText: String) {
        val key = keyOf(url)
        entries.remove(key)
        entries[key] = Entry(messageText, now())
        while (entries.size > maxEntries) {
            entries.remove(entries.keys.first())
        }
    }

    /** @return the most recent message text [url] arrived in, or null if none within the TTL. */
    @Synchronized
    fun lookup(url: String): String? {
        val key = keyOf(url)
        val entry = entries[key] ?: return null
        if (now() - entry.recordedAt >= ttlMs) {
            entries.remove(key)
            return null
        }
        return entry.messageText
    }

    private fun keyOf(url: String): String =
        url.trim().lowercase()
            .substringBefore('#')
            .replaceFirst(SCHEME, "")
            .removeSuffix("/")

    companion object {
        const val DEFAULT_TTL_MS = 30 * 60_000L
        const val DEFAULT_MAX_ENTRIES = 50
        private val SCHEME = Regex("^https?://")
    }
}
