package com.linkguard.app.util

/**
 * Decides whether a posted notification is worth scanning for links.
 *
 * Phase 1 of "scan links from any app": replaces the old hardcoded 19-package
 * allowlist with a denylist model — scan notifications from ANY app except a small
 * set of system/self surfaces that can only produce noise or a feedback loop.
 *
 * Kept as a pure function (no Android types) so it is unit-testable under
 * `testOptions.unitTests.returnDefaultValues = true`. The caller ([LinkNotificationService])
 * reads the Android notification flags and passes plain booleans in.
 */
object NotificationFilter {

    /**
     * Packages we never scan. `ownPackage` is excluded separately by the caller because
     * LinkGuard's OWN threat-alert notifications contain the offending URL — scanning them
     * would re-trigger a scan endlessly (self-scan loop). This set covers system surfaces
     * that emit notifications but never carry a user-received message link.
     *
     * Deliberately explicit (no broad `com.android.*` prefix) so real messaging apps that
     * live under that namespace — e.g. `com.android.mms` (SMS) — are still scanned.
     */
    private val EXCLUDED_PACKAGES = setOf(
        "android",                       // core framework notifications
        "com.android.systemui",          // system UI (charging, screenshots, etc.)
        "com.android.providers.downloads", // AOSP Download Manager status/completion notices
        "com.google.android.providers.downloads", // Google Download Manager variant
        "com.android.vending",           // Play Store (download/update notices)
        "com.google.android.gms",        // Play services
    )

    /**
     * @param packageName     the app that posted the notification
     * @param ownPackage      LinkGuard's own applicationId (self — never scanned)
     * @param isOngoing       true for persistent/ongoing notifications (media, downloads…)
     * @param isGroupSummary  true for the summary notification that just restates children
     * @return true if this notification should be inspected for links
     */
    fun shouldScan(
        packageName: String,
        ownPackage: String,
        isOngoing: Boolean,
        isGroupSummary: Boolean,
    ): Boolean {
        if (packageName.isBlank()) return false
        if (packageName == ownPackage) return false       // CRITICAL: avoid self-scan loop
        if (packageName in EXCLUDED_PACKAGES) return false
        if (isOngoing) return false                        // not a fresh user message
        if (isGroupSummary) return false                   // duplicates its child messages
        return true
    }

    /**
     * The messaging apps scanned by default when the user has NOT opted into scanning all
     * apps. Mirrors the label map in LinkNotificationService.getAppLabel. Phase 2 keeps the
     * broad "any app" capability opt-in (privacy / Play notification-access policy).
     */
    val DEFAULT_MESSAGING_PACKAGES = setOf(
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.whatsapp",
        "org.telegram.messenger",
        "org.thunderdog.challegram",        // Telegram X
        "com.facebook.orca",
        "com.facebook.mlite",               // Messenger Lite
        "com.instagram.android",
        "com.viber.voip",
        "com.snapchat.android",
        "com.discord",
        "org.thoughtcrime.securesms",       // Signal
        "jp.naver.line.android",            // Line
        "com.google.android.gm",            // Gmail
        "com.tencent.mm",                   // WeChat
        "com.kakao.talk",                   // KakaoTalk
        "com.twitter.android",              // X (Twitter) DMs
        "com.zhiliaoapp.musically",         // TikTok
    )

    /**
     * Breadth policy applied on TOP of [shouldScan]: when the user has not enabled
     * "scan all apps", restrict scanning to [DEFAULT_MESSAGING_PACKAGES].
     */
    fun isWithinScope(packageName: String, scanAllApps: Boolean): Boolean =
        scanAllApps || packageName in DEFAULT_MESSAGING_PACKAGES
}
