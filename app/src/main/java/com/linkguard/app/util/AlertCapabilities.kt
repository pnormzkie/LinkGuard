package com.linkguard.app.util

/**
 * Pure decision logic for which alert-delivery capabilities are missing.
 *
 * Threat alerts reach the user as a high-priority notification with a full-screen intent
 * (see [ThreatAlertHelper]). Two OS gates can silently suppress that on modern Android:
 *  - API 33+ (Tiramisu): POST_NOTIFICATIONS must be granted or NO notification is shown.
 *  - API 34+ (UpsideDownCake): USE_FULL_SCREEN_INTENT is no longer auto-granted, so the
 *    full-screen popup is downgraded to a heads-up notification unless the user allows it.
 *
 * This object is kept framework-free so it is unit-testable on the JVM — the actual reads
 * (NotificationManagerCompat.areNotificationsEnabled, NotificationManager.canUseFullScreenIntent)
 * live in the Activity and are passed in here as plain values.
 */
object AlertCapabilities {

    /** A missing capability that degrades or blocks threat-alert delivery. */
    enum class Gap { NOTIFICATIONS_DISABLED, FULL_SCREEN_INTENT_BLOCKED }

    /** POST_NOTIFICATIONS becomes a runtime permission here. */
    const val SDK_TIRAMISU = 33
    /** canUseFullScreenIntent() gating starts here. */
    const val SDK_UPSIDE_DOWN_CAKE = 34

    /**
     * @param sdkInt Build.VERSION.SDK_INT
     * @param notificationsEnabled NotificationManagerCompat.areNotificationsEnabled() (only gates API 33+)
     * @param canUseFullScreenIntent NotificationManager.canUseFullScreenIntent() (only gates API 34+)
     * @return the gaps that currently apply, in fix-priority order (notifications first).
     */
    fun missing(
        sdkInt: Int,
        notificationsEnabled: Boolean,
        canUseFullScreenIntent: Boolean,
    ): List<Gap> = buildList {
        if (sdkInt >= SDK_TIRAMISU && !notificationsEnabled) add(Gap.NOTIFICATIONS_DISABLED)
        if (sdkInt >= SDK_UPSIDE_DOWN_CAKE && !canUseFullScreenIntent) add(Gap.FULL_SCREEN_INTENT_BLOCKED)
    }
}
