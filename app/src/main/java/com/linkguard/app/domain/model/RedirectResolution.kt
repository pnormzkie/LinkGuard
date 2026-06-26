package com.linkguard.app.domain.model

/**
 * Outcome of following a tapped link's HTTP redirect chain to its final destination.
 *
 * @property finalUrl       the last URL reached (equals the input when nothing redirected, or
 *                          the best-known URL when the chain was stopped early).
 * @property hops           the ordered chain, including the input and the final URL.
 * @property crossedDomains true when the final registrable domain differs from the input's.
 * @property outcome        why resolution stopped — see [RedirectOutcome].
 */
data class RedirectResolution(
    val finalUrl: String,
    val hops: List<String>,
    val crossedDomains: Boolean,
    val outcome: RedirectOutcome
) {
    /** True if at least one redirect was actually followed. */
    val redirected: Boolean get() = hops.size > 1
}

enum class RedirectOutcome {
    /** First response was not a redirect — the URL is its own destination. */
    NO_REDIRECT,

    /** Followed one or more redirects and reached a final, non-redirecting URL. */
    RESOLVED,

    /** Stopped because the chain exceeded the hop limit. */
    MAX_HOPS,

    /** Stopped because a URL was revisited (redirect loop). */
    LOOP,

    /** A redirect targeted a non-http(s) scheme (javascript:/data:/intent:/…). */
    BLOCKED_SCHEME,

    /** A redirect targeted a private/loopback/link-local host (anti-SSRF). */
    BLOCKED_PRIVATE_HOST,

    /** Stopped because the total time budget was exhausted. */
    TIMEOUT,

    /** A network/transport error occurred while resolving. */
    ERROR
}
