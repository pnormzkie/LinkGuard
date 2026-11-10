package com.linkguard.app.util

import java.net.URI

object DomainExtractor {
    private val AUTHORITY_TERMINATORS = setOf('/', '\\', '?', '#')

    /**
     * Safely extracts the host/domain from a URL string.
     * Handles missing schemes, subdomains, ports, and paths.
     */
    fun extract(url: String?): String? {
        if (url == null || url.isBlank()) return null
        
        return try {
            val normalizedUrl = if (!url.contains("://")) {
                "https://$url"
            } else {
                url
            }

            val uri = URI(normalizedUrl)
            // java.net.URI leaves host null for valid Unicode/IDN authorities. Preserve the raw
            // hostname so the heuristic layer can inspect mixed scripts before HTTP clients
            // canonicalize it to punycode.
            val host = uri.host ?: authorityHost(uri.rawAuthority) ?: return null
            host.lowercase().removePrefix("www.")
        } catch (e: Exception) {
            // Mirror how a browser finds the host: the authority ends at the first '/', '\',
            // '?' or '#' (browsers treat '\' as '/' in http(s) URLs, and URI() rejects it, so
            // "evil.xyz\@paypal.com" lands here). Then take the part after the LAST '@'
            // (stripping user:pass@ userinfo) and before the ':' port, and lowercase it.
            url.substringAfter("://")
                .takeWhile { it !in AUTHORITY_TERMINATORS }
                .substringAfterLast("@")
                .substringBefore(":")
                .removePrefix("www.")
                .lowercase()
        }
    }

    /**
     * The site a host belongs to: its last two labels, or three under a two-part suffix such
     * as "com.ph" ("mail.bdo.com.ph" -> "bdo.com.ph"). A leading "www." is ignored. This is a
     * short suffix list, not the full public-suffix list.
     */
    fun site(host: String): String {
        val parts = host.lowercase().removePrefix("www.").split(".").filter { it.isNotEmpty() }
        if (parts.size <= 2) return parts.joinToString(".")
        val lastTwo = parts.takeLast(2).joinToString(".")
        return if (lastTwo in TWO_PART_SUFFIXES) parts.takeLast(3).joinToString(".") else lastTwo
    }

    private val TWO_PART_SUFFIXES = setOf(
        "com.ph", "net.ph", "org.ph", "gov.ph",
        "co.uk", "org.uk", "gov.uk",
        "com.au", "com.sg", "com.my"
    )

    private fun authorityHost(authority: String?): String? {
        val value = authority?.substringAfterLast('@')?.takeIf { it.isNotBlank() } ?: return null
        return if (value.startsWith('[')) {
            value.substringAfter('[').substringBefore(']').takeIf { it.isNotBlank() }
        } else {
            value.substringBefore(':').takeIf { it.isNotBlank() }
        }
    }
}
