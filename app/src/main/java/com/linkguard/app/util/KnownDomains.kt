package com.linkguard.app.util

/**
 * Single source of truth for the trusted and tracker domain lists used across the
 * signal providers. Previously each provider kept its own drifting copy, so a domain
 * could be trusted by one check and looked up by another. These are the unions of
 * the former per-provider lists.
 */
object KnownDomains {

    /**
     * Major services trusted enough to skip external reputation/enrichment lookups.
     * A match also covers subdomains (e.g. accounts.google.com matches google.com).
     */
    val TRUSTED_DOMAINS: Set<String> = setOf(
        "google.com", "youtube.com", "facebook.com", "microsoft.com",
        "apple.com", "amazon.com", "netflix.com", "google.com.ph",
        "gcash.com", "maya.ph", "paymaya.com", "bpi.com.ph", "bdo.com.ph", "metrobank.com.ph",
        "landbank.com", "unionbankph.com", "shopee.ph", "lazada.com.ph", "rcbc.com",
        "grab.com", "sss.gov.ph", "pagibig.gov.ph", "philhealth.gov.ph", "bir.gov.ph",
        "paypal.com", "instagram.com", "twitter.com", "x.com", "linkedin.com", "github.com",
        "chatgpt.com", "openai.com",
        "googlesyndication.com", "googleadservices.com", "doubleclick.net"
    )

    /** Domains known primarily for ads, tracking, or telemetry. */
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
