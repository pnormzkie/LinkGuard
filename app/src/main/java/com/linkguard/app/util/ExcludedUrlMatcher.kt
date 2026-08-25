package com.linkguard.app.util

import java.net.IDN
import java.net.URI

/** Canonicalizes URLs for exact scheme + host + port + path + query matching. */
object ExcludedUrlMatcher {

    fun normalize(input: String): String? {
        val value = input.trim()
        if (value.isEmpty() || value.any(Char::isWhitespace)) return null

        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
            ?.takeIf { it == "http" || it == "https" }
            ?: return null
        if (uri.userInfo != null) return null

        val rawHost = uri.host?.trimEnd('.')?.takeIf(String::isNotEmpty) ?: return null
        val host = if (rawHost.contains(':')) {
            "[$rawHost]"
        } else {
            runCatching { IDN.toASCII(rawHost) }.getOrNull()?.lowercase() ?: return null
        }
        val port = when {
            uri.port == -1 -> ""
            scheme == "http" && uri.port == 80 -> ""
            scheme == "https" && uri.port == 443 -> ""
            else -> ":${uri.port}"
        }
        val path = uri.rawPath?.takeIf(String::isNotEmpty) ?: "/"
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()

        // Fragments are intentionally omitted: browsers do not send them to the server.
        return "$scheme://$host$port$path$query"
    }

    fun isExcluded(url: String, excludedUrls: Set<String>): Boolean {
        val normalized = normalize(url) ?: return false
        return excludedUrls.any { normalize(it) == normalized }
    }
}
