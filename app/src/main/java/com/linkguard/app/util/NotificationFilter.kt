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
}
