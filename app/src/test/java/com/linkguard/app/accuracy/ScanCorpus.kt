package com.linkguard.app.accuracy

/**
 * The accuracy corpus: a fixed set of URLs with the verdict class each one must land in.
 *
 * This exists because nothing else measures false positives. Every other test in this module
 * feeds the scanner synthetic signals and asserts the plumbing; none of them ask "does a real
 * legitimate URL come back clean?". The notion.com false positive that motivated this file was
 * found by a user looking at a screenshot, which is not a detection mechanism.
 *
 * ## What is real here and what is not
 *
 * The URLs in [LEGITIMATE] are real sites. The point of the list is that most of them are NOT
 * in `KnownDomains.TRUSTED_DOMAINS` — an un-calibrated legitimate host is exactly where a false
 * positive hides, so a corpus made only of trusted majors would prove nothing.
 *
 * The URLs in [PHISHING_SHAPES] are NOT claims about real malicious sites. They are synthetic
 * URL shapes that encode patterns the scanner is supposed to catch (brand look-alike hosts,
 * credential-harvest paths, punycode homographs, raw IPs). Treat a failure here as "the
 * heuristic stopped recognising this shape", not as "this site went clean".
 *
 * ## Why there are almost no recorded provider payloads
 *
 * Inventing a plausible-looking VirusTotal or sandbox response and asserting against it would
 * prove only that the fixture matches the assertion. The only external payload in this file is
 * one that was actually observed on-device (see [NOTION_HYBRID_ANALYSIS_BODY]). Everything else
 * runs with the external layer reporting nothing — which is the genuine result for a URL no
 * vendor has a report on, and is what each provider's own test already pins down for a 404 or
 * an empty match. Consequently these cases measure OUR heuristics and OUR scoring, which is
 * where both defects found on 2026-09-22 actually lived.
 *
 * To extend the corpus with real external evidence, capture it rather than write it: run the
 * URL on a device with `adb logcat`, read the provider response, and add it here with a note
 * saying when it was captured.
 */
internal object ScanCorpus {

    enum class Expected {
        /** Must come back SAFE. A non-SAFE verdict here is a false positive. */
        SAFE,

        /** Must come back SUSPICIOUS or DANGER. A SAFE verdict here is a false negative. */
        FLAGGED
    }

    data class Case(
        val url: String,
        val expected: Expected,
        val note: String,
        /** Recorded Hybrid Analysis response body; null means no vendor has a report. */
        val hybridAnalysisBody: String? = null
    )

    /**
     * Observed on a Pixel_7 emulator on 2026-09-22 against the live Falcon Sandbox API:
     * `HA Best Match (https://www.notion.com/): verdict=Suspicious score=29`. This is the exact
     * response that rendered as "14% RISK" next to a "SUSPICIOUS LINK" badge before the
     * strength derivation was fixed, so it is the regression case with the most history.
     */
    const val NOTION_HYBRID_ANALYSIS_BODY: String =
        """{"count":1,"result":[{"verdict":"suspicious","threat_score":29}]}"""

    /** Real, legitimate destinations. Most are deliberately NOT on the trusted list. */
    val LEGITIMATE: List<Case> = listOf(
        Case(
            "https://www.notion.com/", Expected.SAFE,
            "Off the trusted list, with a real low-score sandbox report. The original defect.",
            hybridAnalysisBody = NOTION_HYBRID_ANALYSIS_BODY
        ),
        Case("https://www.figma.com/", Expected.SAFE, "Off-list SaaS"),
        Case("https://slack.com/", Expected.SAFE, "Off-list SaaS"),
        Case("https://www.canva.com/", Expected.SAFE, "Off-list SaaS"),
        Case("https://stackoverflow.com/", Expected.SAFE, "Off-list, user-generated content"),
        Case("https://www.wikipedia.org/", Expected.SAFE, "Off-list"),
        Case("https://www.bpi.com.ph/", Expected.SAFE, "PH bank, on the trusted list"),
        Case("https://www.gcash.com/", Expected.SAFE, "PH wallet, on the trusted list"),
        Case(
            "https://github.com/pnormzkie/LinkGuard/releases", Expected.SAFE,
            "Trusted host serving user-authored content — path rules stay on, must still pass"
        ),
        Case(
            "https://mail.google.com/mail/u/0/", Expected.SAFE,
            "Subdomain of a trusted root, with a path"
        ),
        Case(
            "https://www.lazada.com.ph/catalog/?q=phone", Expected.SAFE,
            "Trusted host with a query string"
        ),
        Case(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ", Expected.SAFE,
            "Trusted host, opaque query value"
        ),
        Case(
            "https://ph.indeed.com/jobs?q=qa+engineer", Expected.SAFE,
            "Off-list regional subdomain with a query string"
        ),
        Case(
            "https://accounts.google.com/signin/v2/identifier", Expected.SAFE,
            "A REAL sign-in page on a trusted host: credential-flavoured path that must not flag"
        ),
        Case(
            "https://www.paypal.com/ph/signin", Expected.SAFE,
            "Another real sign-in page — the brand name here is the site's own"
        )
    )

    /** Synthetic URL shapes the scanner must keep catching. Not real sites. */
    val PHISHING_SHAPES: List<Case> = listOf(
        Case(
            "https://secure-google.com/login", Expected.FLAGGED,
            "Brand name glued into an unrelated host, credential path"
        ),
        Case(
            "https://evil-site.xyz?action=verify&do=login", Expected.FLAGGED,
            "High-abuse TLD plus credential-harvest query parameters"
        ),
        Case(
            "http://gcash-verify.ph/account/confirm", Expected.FLAGGED,
            "PH wallet brand in a look-alike host, cleartext, verify path"
        ),
        Case(
            "https://bpi-online.tk/secure/login.php", Expected.FLAGGED,
            "PH bank brand in a look-alike host on a free TLD"
        ),
        Case(
            "https://paypa1.com/signin", Expected.FLAGGED,
            "Typosquat: digit substituted for a letter"
        ),
        Case(
            "http://192.0.2.1/login", Expected.FLAGGED,
            "Raw IP host serving a login path"
        )
    )

    val ALL: List<Case> = LEGITIMATE + PHISHING_SHAPES
}
