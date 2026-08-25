package com.linkguard.app.util

import android.content.Context

/**
 * Stores the user's notification-scan scope choice.
 *
 * Phase 2 of "scan links from any app". Default is the conservative seed set
 * ([NotificationFilter.DEFAULT_MESSAGING_PACKAGES]); the user explicitly opts in to
 * scanning ALL apps. Keeping the broad mode opt-in is easier to justify for
 * notification-listener access (Play policy) and for privacy.
 */
class MonitorPreferences(context: Context) {

    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * When true, scan notifications from any app (still minus the universal
     * [NotificationFilter.shouldScan] exclusions). When false (default), scan only the
     * seed messaging apps.
     */
    var scanAllApps: Boolean
        get() = prefs.getBoolean(KEY_SCAN_ALL_APPS, false)
        set(value) = prefs.edit().putBoolean(KEY_SCAN_ALL_APPS, value).apply()

    /** Exact URLs the user trusts enough to bypass tapped-link interception. */
    var excludedUrls: Set<String>
        get() = prefs.getStringSet(KEY_EXCLUDED_URLS, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit().putStringSet(KEY_EXCLUDED_URLS, value.toSet()).apply()

    fun isTapUrlExcluded(url: String): Boolean =
        ExcludedUrlMatcher.isExcluded(url, excludedUrls)

    companion object {
        private const val PREFS_NAME = "linkguard_monitor_prefs"
        private const val KEY_SCAN_ALL_APPS = "scan_all_apps"
        private const val KEY_EXCLUDED_URLS = "excluded_urls"
    }
}
