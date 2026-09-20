package com.linkguard.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.linkguard.app.data.ScanResult
import com.linkguard.app.data.ThreatLevel
import com.linkguard.app.ui.ScanDetailActivity
import com.linkguard.app.ui.ThreatAlertActivity

/**
 * Helper to show heads-up emergency alerts for DANGEROUS and SUSPICIOUS links.
 */
object ThreatAlertHelper {

    // Channel visibility is persisted by Android; use new IDs for the privacy default.
    private const val CHANNEL_DANGER_ID = "linkguard_danger_v4"
    private const val CHANNEL_WARNING_ID = "linkguard_warning_v4"

    fun alert(context: Context, result: ScanResult) {
        if (result.threatLevel == ThreatLevel.SAFE) return

        createNotificationChannels(context)

        val isCritical = result.threatLevel == ThreatLevel.DANGER
        val channelId = if (isCritical) CHANNEL_DANGER_ID else CHANNEL_WARNING_ID
        
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Stable per link, so a repeat alert replaces the existing one instead of stacking.
        val alertId = result.url.lowercase().hashCode()

        // 1. Prepare Intent for the Popup Activity
        val popupIntent = ThreatAlertActivity.newIntent(context, result)
        popupIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        // 2. Best-effort direct popup. This works pre-Android-14 and when the app is in the
        //    foreground, but a background launch is BAL_BLOCKED on API 34+. The full-screen
        //    intent on the notification below (step 4) is the GUARANTEED delivery path; this
        //    is only a fast path when allowed. POST_NOTIFICATIONS / canUseFullScreenIntent are
        //    requested up front in MainActivity so that path isn't silently suppressed.
        try {
            context.startActivity(popupIntent)
        } catch (e: Exception) {
            android.util.Log.e("ThreatAlertHelper", "Direct activity start failed: ${e.message}")
        }

        // 3. Prepare PendingIntents for the Notification
        val popupPendingIntent = PendingIntent.getActivity(
            context, 
            alertId + 100,
            popupIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val detailIntent = ScanDetailActivity.newIntent(context, result)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val detailPendingIntent = PendingIntent.getActivity(
            context, alertId + 200, detailIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 4. Build the Notification
        val title = if (isCritical) "⚠️ DANGEROUS LINK DETECTED" else "⚠ Suspicious Link Detected"
        val sender = result.senderInfo.ifBlank { "Unknown" }
        val app = result.sourceApp.ifBlank { "System" }
        val shortUrl = if (result.url.length > 50) result.url.take(50) + "…" else result.url

        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText("From $sender via $app")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("URL: $shortUrl\n\nProtect yourself immediately."))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            // PRIVATE keeps sender/URL out of the lockscreen; the full-screen alert still
            // shows everything once the device is unlocked (ThreatAlertActivity gates detail).
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setOngoing(isCritical)
            .setFullScreenIntent(popupPendingIntent, true) 
            .setContentIntent(detailPendingIntent)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .addAction(android.R.drawable.ic_menu_view, "View Report", detailPendingIntent)

        nm.notify(alertId, notificationBuilder.build())
    }

    fun createNotificationChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val alarmSound: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()

        if (nm.getNotificationChannel(CHANNEL_DANGER_ID) == null) {
            val dangerChannel = NotificationChannel(
                CHANNEL_DANGER_ID,
                "Emergency Threat Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical alerts for confirmed malicious links"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 200, 800, 200, 1000)
                setBypassDnd(true)
                setSound(alarmSound, audioAttributes)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
            nm.createNotificationChannel(dangerChannel)
        }

        if (nm.getNotificationChannel(CHANNEL_WARNING_ID) == null) {
            val warningChannel = NotificationChannel(
                CHANNEL_WARNING_ID,
                "Suspicious Link Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts for suspicious or unverified links"
                enableVibration(true)
                setSound(alarmSound, audioAttributes) // Also add sound for suspicious in background
            }
            nm.createNotificationChannel(warningChannel)
        }
    }
}
