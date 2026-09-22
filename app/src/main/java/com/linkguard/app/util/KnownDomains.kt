package com.linkguard.app.util

/**
 * Single source of truth for the trusted and tracker domain lists used across the
 * signal providers. Previously each provider kept its own drifting copy, so a domain
 * could be trusted by one check and looked up by another. These are the unions of
 * the former per-provider lists.
 */
object KnownDomains {

    /**
     * Major services whose reputation can calibrate noisy domain-level signals. Trust does not
     * bypass URL-specific checks because legitimate services can host user-controlled content or
     * redirect to an unsafe destination. A match also covers subdomains.
     */
    val TRUSTED_DOMAINS: Set<String> = setOf(
        "google.com", "youtube.com", "facebook.com", "microsoft.com",
        "apple.com", "amazon.com", "netflix.com", "google.com.ph",
        "gcash.com", "globe.com.ph", "maya.ph", "paymaya.com", "bpi.com.ph",
        "bpiexpressonline.com", "bdo.com.ph", "metrobank.com.ph",
        "landbank.com", "unionbankph.com", "shopee.ph", "lazada.com.ph", "rcbc.com",
        "grab.com", "sss.gov.ph", "pagibig.gov.ph", "philhealth.gov.ph", "bir.gov.ph",
        "paypal.com", "instagram.com", "twitter.com", "x.com", "linkedin.com", "github.com",
        "chatgpt.com", "openai.com", "secure.indeed.com",
        "fb.com", "shopee.com", "lazada.com", "hdmf.gov.ph",
        "visa.com", "mastercard.com", "dhl.com", "dhl.com.ph", "fedex.com",
        "battle.net", "blizzard.com", "steampowered.com", "steamcommunity.com",
        "discord.com", "epicgames.com", "riotgames.com",
        "googlesyndication.com", "googleadservices.com", "doubleclick.net"
    )

    /**
     * Domains known primarily for ads, tracking, or telemetry.
     *
     * `doubleclick.net`, `googlesyndication.com` and `googleadservices.com` are deliberately
     * in [TRUSTED_DOMAINS] as well: they are Google-operated, so they are not suspicious
     * hosts, but they are also not destinations a user meant to open. A domain can therefore
     * be both, and any caller that treats the two differently has to pick a winner — the
     * providers check trusted first, so the stricter calibration applies. KnownDomainsTest
     * locks that overlap so it isn't "tidied up" into a behavior change.
     */
    val TRACKER_DOMAINS: Set<String> = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "app-measurement.com", "crashlytics.com", "adjust.com", "appsflyer.com",
        "facebook.net", "fbcdn.net", "scorecardresearch.com"
    )

    /** True if [domain] is the trusted domain itself or one of its subdomains. */
    fun isTrusted(domain: String?): Boolean =
        domain != null && TRUSTED_DOMAINS.any { domain == it || domain.endsWith(".$it") }

    /** True if [domain] is a known tracker domain or one of its subdomains. */
    fun isTracker(domain: String?): Boolean =
        domain != null && TRACKER_DOMAINS.any { domain == it || domain.endsWith(".$it") }
}
