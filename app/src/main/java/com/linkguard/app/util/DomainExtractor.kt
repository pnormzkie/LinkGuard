package com.linkguard.app.util

import java.net.URI

object DomainExtractor {
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
            // Mirror URI.host: drop scheme, path, and query, then take the authority's
            // host — the part after the LAST '@' (stripping user:pass@ userinfo) and
            // before the ':' port — and lowercase it.
            url.substringAfter("://")
                .substringBefore("/")
                .substringBefore("?")
                .substringAfterLast("@")
                .substringBefore(":")
                .removePrefix("www.")
                .lowercase()
        }
    }

    private fun authorityHost(authority: String?): String? {
        val value = authority?.substringAfterLast('@')?.takeIf { it.isNotBlank() } ?: return null
        return if (value.startsWith('[')) {
            value.substringAfter('[').substringBefore(']').takeIf { it.isNotBlank() }
        } else {
            value.substringBefore(':').takeIf { it.isNotBlank() }
        }
    }
}
