package com.linkguard.app.scanner

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Normalizes a URL entered manually or read from a QR code without turning a standalone
 * download filename (for example, "setup.apk") into a fake web host.
 */
object UrlInputNormalizer {

    fun normalize(input: String): String? {
        val raw = input.trim()
        if (raw.isBlank()) return null

        val hasWebScheme =
            raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true)

        // Without an explicit scheme, an email address would otherwise be interpreted as URL
        // user-info ("https://user@example.com") and scanned as if the user entered example.com.
        if (!hasWebScheme && raw.substringBefore('/').contains('@')) return null

        val candidate = if (hasWebScheme) {
            raw
        } else {
            "https://$raw"
        }

        val parsed = candidate.toHttpUrlOrNull() ?: return null
        val host = parsed.host

        // Preserve the existing requirement for a dotted host, while also allowing a valid
        // IPv6 literal (OkHttp exposes it without square brackets).
        if (!host.contains('.') && !host.contains(':')) return null

        // File suffixes such as ".apk" and ".exe" are not web-host suffixes and indicate that
        // a standalone filename was pasted into the URL field. ".zip" is intentionally exempt:
        // it is also a real domain suffix, and the download heuristic separately inspects paths.
        val hostSuffix = host.substringAfterLast('.', missingDelimiterValue = "")
        val looksLikeStandaloneFilename =
            ".$hostSuffix" in DANGEROUS_FILE_EXTENSIONS && hostSuffix != "zip"
        if (looksLikeStandaloneFilename) return null

        // Return the user's spelling so Unicode lookalike analysis still sees the original
        // characters instead of OkHttp's punycode representation.
        return candidate
    }
}
