package com.linkguard.app.scanner

enum class QrType {
    URL,
    PAYMENT_QR,
    UNKNOWN
}

object QrTypeDetector {

    fun detect(raw: String): QrType {
        val text = raw.trim()

        if (isUrl(text)) return QrType.URL
        if (isLikelyPaymentQr(text)) return QrType.PAYMENT_QR
        if (linkIn(text) != null) return QrType.URL

        return QrType.UNKNOWN
    }

    /**
     * The web link to scan for a QR classified as [QrType.URL]: the whole payload when it is a
     * link, otherwise the first link inside it. Scam posters print text around the link ("Scan
     * para sa libreng load: https://…") and contact cards carry one in a URL: field; both used to
     * be dropped as "unsupported" without scanning. App-link and script payloads (intent:,
     * javascript:, file:, …) are never mined for a link, so a package name inside them is not
     * read as a web address.
     */
    fun linkIn(raw: String): String? {
        val text = raw.trim()
        if (isUrl(text)) return text
        if (NON_WEB_SCHEME.containsMatchIn(text)) return null
        return UrlExtractor.extractUrls(text).firstOrNull()
    }

    private fun isUrl(text: String): Boolean {
        val lower = text.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://") ||
            DOMAIN_ONLY_URL.matches(lower)
    }

    /**
     * EMV / QR Ph payloads always open with tag 00 (length 02). The old fallbacks ("6304"
     * anywhere, or any long text without spaces) turned WiFi, app-link and order-number QR codes
     * into "Invalid Payment QR" warnings.
     */
    private fun isLikelyPaymentQr(text: String): Boolean =
        text.length >= 20 && text.startsWith("0002")

    private val NON_WEB_SCHEME =
        Regex("""^(?:intent|javascript|file|data|content|market|vbscript):""", RegexOption.IGNORE_CASE)

    private val DOMAIN_ONLY_URL =
        Regex("""^(?:www\.)?(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}(?::\d{1,5})?(?:[/?#].*)?$""")
}
