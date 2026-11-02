package com.linkguard.app.util

/**
 * Hosts written as numbers. Browsers accept an IPv4 address in several notations besides the
 * dotted quad — one decimal number, hex, octal, or fewer than four parts — and open the address
 * it encodes. No registrable domain is all-numeric (top-level domains are never numbers), so a
 * host made only of number labels is always an IP address.
 */
object NumericHost {

    private const val PART = "(?:0x[0-9a-f]*|\\d+)"
    private val IPV4_FORM = Regex("^$PART(?:\\.$PART){0,3}\\.?$", RegexOption.IGNORE_CASE)
    private val DOTTED_QUAD_PART = Regex("^(?:0|[1-9]\\d{0,2})$")

    /** True for any IPv4 notation a browser opens, including the plain dotted quad. */
    fun isIpv4Form(host: String): Boolean = IPV4_FORM.matches(host)

    /**
     * True for an IPv4 notation other than the plain dotted quad. An HTTP client resolves such a
     * host as a hostname (a slow DNS failure) while the browser opens the address it encodes.
     */
    fun isObfuscatedIpv4(host: String): Boolean {
        if (!isIpv4Form(host)) return false
        val parts = host.trimEnd('.').split('.')
        return !(parts.size == 4 && parts.all { DOTTED_QUAD_PART.matches(it) && it.toInt() <= 255 })
    }
}
