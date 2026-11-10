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
        "google.com", "youtube.com", "facebook.com", "microsoft.com", "microsoftonline.com",
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
        "googlesyndication.com", "googleadservices.com", "doubleclick.net",
        // Brand-owned sites whose names otherwise read as spoofs. Each github.io entry was
        // checked on 2026-09-27 to be the brand's own GitHub Organization (api.github.com/users):
        // amazon.github.io is NOT, it is a personal account, so it stays out.
        "blog.google", "microsoft.github.io", "google.github.io", "facebook.github.io",
        "apple.github.io", "paypal.github.io", "netflix.github.io", "twitter.github.io"
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

    /** Public link shorteners: anyone can make a link on them, and it forwards every visitor alike. */
    val URL_SHORTENERS: List<String> = listOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly",
        "short.link", "rb.gy", "cutt.ly", "is.gd", "buff.ly",
        "tiny.cc", "shorte.st", "adf.ly", "bc.vc", "s.id",
        "v.gd", "clck.ru", "qr.ae", "to.ly", "x.co",
        "lnkd.in", "fb.me", "wa.me", "go2l.ink", "shorturl.at"
    )

    /**
     * Domains a brand owns only to forward to its own site: short links and alternate
     * spellings. Several read as look-alikes of the brand ("youtu.be", "amzn.to", "apple.net"),
     * which is what they are, by the owner. Being listed here grants no trust to the
     * destination, which is scanned as always; it only says the forwarding itself is the
     * brand's. Checked on 2026-11-10 with a plain client and a mobile-browser one: the bare
     * domain forwards to the brand's official site for every entry except g.co, forms.gle,
     * share.google, m.me, fb.watch, aka.ms and paypal.me (they answer only for a full link and
     * are listed on the brands' own published use of them), shp.ee (its country subdomains
     * ph./br./th./my.shp.ee forward to the Shopee site of that country) and smrt.ph (name
     * servers at smart.com.ph, certificate issued to Smart Communications, Inc.).
     * Left out because ownership could not be confirmed: gcsh.app, glo.be.
     */
    val BRAND_FORWARDING_DOMAINS: Set<String> = setOf(
        // Google
        "youtu.be", "g.co", "forms.gle", "share.google", "google.co", "google.me", "google.net",
        "youtube.co", "youtube.ph", "youtube.com.ph", "youtube.me",
        // Meta
        "m.me", "ig.me", "fb.watch", "instagr.am", "instagram.co", "facebook.co",
        // Amazon, Apple, Microsoft, Netflix, PayPal, Discord, Roblox
        "amzn.to", "a.co", "amazn.com", "amazon.org", "amazon.ph",
        "apple.co", "apple.me", "apple.net", "aple.com", "icloud.org",
        "microsoft.org", "aka.ms",
        "netflix.co", "netflix.net", "netflix.org", "netflix.ph",
        "paypal.me", "discord.gg",
        "roblox.app", "roblox.ph", "roblox.com.ph", "roblox.me",
        // Philippines
        "shp.ee", "smrt.ph", "lbcexpress.ph", "lbcexpress.net", "pldt.com.ph"
    )

    /**
     * App-install link services (AppsFlyer, Adjust, Branch, Singular). A brand's link on them
     * is a subdomain or path carrying the brand's name ("grab.onelink.me"), which reads as a
     * look-alike of the brand. They do send phones and computers to different places, so a
     * link made on one is judged by its destination alone, exactly as before this list existed.
     */
    val APP_LINK_SERVICES: Set<String> = setOf(
        "onelink.me", "app.appsflyer.com", "adj.st", "app.adjust.com", "app.link", "bnc.lt", "sng.link"
    )

    /**
     * True when the forwarding done by [domain] is a known service's, not something the
     * author of a link scripted: a trusted site, a public shortener, a brand's own forwarding
     * domain, an app-install link service. Any other host that redirects decides for itself
     * where each visitor goes, so it may show a security check one destination and a browser
     * another.
     */
    fun isKnownForwarder(domain: String?): Boolean =
        domain != null && (isTrusted(domain) ||
            (URL_SHORTENERS + BRAND_FORWARDING_DOMAINS + APP_LINK_SERVICES)
                .any { domain == it || domain.endsWith(".$it") })

    /** True if [domain] is the trusted domain itself or one of its subdomains. */
    fun isTrusted(domain: String?): Boolean =
        domain != null && TRUSTED_DOMAINS.any { domain == it || domain.endsWith(".$it") }

    /** True if [domain] is a known tracker domain or one of its subdomains. */
    fun isTracker(domain: String?): Boolean =
        domain != null && TRACKER_DOMAINS.any { domain == it || domain.endsWith(".$it") }
}
