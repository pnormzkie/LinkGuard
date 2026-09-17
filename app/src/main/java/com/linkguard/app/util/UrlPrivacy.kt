package com.linkguard.app.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Privacy boundary for third-party reputation lookups (Safe Browsing, VirusTotal,
 * Hybrid Analysis, URLhaus). Scanned URLs are local analysis data; what we send
 * onward must not carry credentials embedded in the URL or browser-local fragments.
 */
object UrlPrivacy {

    /**
     * Returns a copy of [url] without userinfo and fragment. Host, port, path, and
     * query are preserved — reputation services match on the full path/query of the
     * threat URL. Returns null when the input is not a parseable web URL.
     *
     * Query parameters are intentionally NOT stripped here: they are required for
     * accurate lookups. Callers that consider a query sensitive must redact upstream.
     */
    fun sanitizeForExternalLookup(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        return parsed.newBuilder()
            .username("")
            .password("")
            .fragment(null)
            .build()
            .toString()
    }
}
