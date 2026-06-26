package com.linkguard.app.util

/**
 * Centralized app configuration.
 *
 * API keys are read from BuildConfig fields, which are injected at build time
 * via local.properties (untracked) or your CI/CD secrets — never hardcoded here.
 */
object AppConfig {
    val VIRUSTOTAL_API_KEY: String get() = com.linkguard.app.BuildConfig.VIRUSTOTAL_API_KEY
    val SAFE_BROWSING_API_KEY: String get() = com.linkguard.app.BuildConfig.SAFE_BROWSING_API_KEY
    val HYBRID_ANALYSIS_API_KEY: String get() = com.linkguard.app.BuildConfig.HYBRID_ANALYSIS_API_KEY
    val URLHAUS_AUTH_KEY: String get() = com.linkguard.app.BuildConfig.URLHAUS_AUTH_KEY

    const val DB_NAME = "linkguard_db"
    const val SCAN_HISTORY_LIMIT = 50
    const val RECENT_SCAN_CACHE_SIZE = 200
    // Per-provider scan timeout — one slow API must not starve the others.
    const val PROVIDER_TIMEOUT_MS = 8_000L
    // Cached scan verdicts older than this are treated as a miss and re-scanned,
    // so a stale "safe" reading can't outlive a domain going bad.
    const val SCAN_CACHE_TTL_MS = 15 * 60 * 1000L

    // Redirect/shortener resolution: follow a tapped link to its true destination before
    // scoring. Bounded so it can't wreck click-time UX or loop forever.
    const val REDIRECT_MAX_HOPS = 5
    const val REDIRECT_TOTAL_BUDGET_MS = 3_000L
    const val REDIRECT_PER_HOP_TIMEOUT_MS = 1_500L

    // Credential-form page inspection: a conditional, read-only GET of a borderline-suspicious
    // destination's HTML to detect a login/password form. Bounded for click-time UX and privacy.
    const val CONTENT_FETCH_TIMEOUT_MS = 3_000L
    const val CONTENT_MAX_BYTES = 256L * 1024 // cap the body we read into memory (256 KB)

    // GitHub repository checked for app updates via the Releases API.
    const val GITHUB_REPO = "pnormzkie/LinkGuard"
    // Minimum gap between automatic update checks (foreground returns re-check after this).
    const val UPDATE_CHECK_INTERVAL_MS = 30 * 60 * 1000L
    const val SCAN_COMPLETE_ACTION = "com.linkguard.SCAN_COMPLETE"
    
    // Updated channel IDs to force High Importance for heads-up alerts
    const val NOTIFICATION_CHANNEL_DANGER = "linkguard_danger_v2"
    const val NOTIFICATION_CHANNEL_WARNING = "linkguard_warning_v2"

    object Extras {
        const val URL = "url"
        const val SCORE = "score"
        const val CATEGORY = "category"
        const val FLAGS = "flags"
        const val SENDER = "sender"
        const val APP = "app"
        const val THREAT_LEVEL = "threat_level"
        const val FLAG_GROUPS = "flag_groups"
        const val RESOLVED_URL = "resolved_url"
    }
}
