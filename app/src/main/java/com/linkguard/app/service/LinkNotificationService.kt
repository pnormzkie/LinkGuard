package com.linkguard.app.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.linkguard.app.BuildConfig
import com.linkguard.app.ScannerProvider
import com.linkguard.app.data.ScanRepository
import com.linkguard.app.data.ThreatLevel
import com.linkguard.app.domain.mapper.toLegacy
import com.linkguard.app.scanner.UrlExtractor
import com.linkguard.app.util.AppConfig
import com.linkguard.app.util.DailyScanCounter
import com.linkguard.app.util.MonitorPreferences
import com.linkguard.app.util.NotificationFilter
import com.linkguard.app.util.NotificationPayloadExtractor
import com.linkguard.app.util.ScanRateLimiter
import com.linkguard.app.util.ThreatAlertHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LinkNotificationService : NotificationListenerService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val orchestrator by lazy { ScannerProvider.orchestrator }
    private val repository by lazy { ScanRepository(applicationContext) }
    private val monitorPrefs by lazy { MonitorPreferences(applicationContext) }
    // Flood guard for the automatic path: caps scans/window to protect provider quotas.
    private val rateLimiter = ScanRateLimiter()
    // Persistent daily cap (survives restarts) aligned with provider daily quotas.
    private val dailyCounter by lazy { DailyScanCounter.create(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        // Ensure high-priority channels are registered when service starts
        ThreatAlertHelper.createNotificationChannels(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Scan links from any app except self/system surfaces (denylist model).
        val isGroupSummary =
            (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (!NotificationFilter.shouldScan(
                packageName = sbn.packageName,
                ownPackage = packageName,
                isOngoing = sbn.isOngoing,
                isGroupSummary = isGroupSummary,
            )
        ) return
        // Breadth gate: unless the user opted into scanning all apps, only the seed
        // messaging apps are scanned (default = privacy-conservative).
        if (!NotificationFilter.isWithinScope(sbn.packageName, monitorPrefs.scanAllApps)) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val fullMessageText = NotificationPayloadExtractor.collectText(extras)
        val urls = UrlExtractor.extractUrls(fullMessageText)
        if (urls.isEmpty()) return

        val appLabel = getAppLabel(sbn.packageName)
        val sender = title.ifBlank { "Unknown" }

        urls.forEach { url ->
            // Quotas limit only network/API enrichment. Local heuristics always run so flooding
            // cannot push a later malicious URL completely past protection.
            val allowNetworkChecks = rateLimiter.tryAcquire() && dailyCounter.tryAcquire()
            if (!allowNetworkChecks && BuildConfig.DEBUG) {
                Log.w(TAG, "Network scan quota reached; running local-only analysis")
            }
            serviceScope.launch {
                try {
                    val domainResult = orchestrator.scan(
                        url,
                        messageText = fullMessageText,
                        allowNetworkChecks = allowNetworkChecks
                    )
                    val legacyResult = domainResult.toLegacy(sourceApp = appLabel, senderInfo = sender)

                    // Alerting must not depend on history persistence: a DB failure should
                    // degrade the record, never skip the warning the user needs to see.
                    runCatching { repository.saveScan(legacyResult) }
                        .onFailure {
                            if (it is kotlinx.coroutines.CancellationException) throw it
                            Log.e(TAG, "History save failed (${it.javaClass.simpleName}); continuing alert delivery")
                        }

                    if (legacyResult.threatLevel != ThreatLevel.SAFE) {
                        // Use central helper which now handles high-priority heads-up logic
                        ThreatAlertHelper.alert(this@LinkNotificationService, legacyResult)
                    } else if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Safe link from $appLabel: $url")
                    }

                    sendBroadcast(android.content.Intent(AppConfig.SCAN_COMPLETE_ACTION).apply {
                        `package` = packageName
                        putExtra(AppConfig.Extras.URL, url)
                        putExtra(AppConfig.Extras.THREAT_LEVEL, legacyResult.threatLevel.name)
                        putExtra(AppConfig.Extras.SCORE, legacyResult.riskScore)
                    })
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Scan error${if (BuildConfig.DEBUG) " for $url" else ""}: ${e.message}")
                }
            }
        }
    }

    private fun getAppLabel(packageName: String): String = when (packageName) {
        "com.whatsapp"                          -> "WhatsApp"
        "org.telegram.messenger"                -> "Telegram"
        "org.thunderdog.challegram"             -> "Telegram X"
        "com.google.android.apps.messaging",
        "com.android.mms"                       -> "SMS"
        "com.samsung.android.messaging"         -> "Messages"
        "com.facebook.orca"                     -> "Messenger"
        "com.facebook.mlite"                    -> "Messenger Lite"
        "com.instagram.android"                 -> "Instagram"
        "com.viber.voip"                        -> "Viber"
        "com.snapchat.android"                  -> "Snapchat"
        "com.discord"                           -> "Discord"
        "org.thoughtcrime.securesms"            -> "Signal"
        "jp.naver.line.android"                 -> "Line"
        "com.google.android.gm"                 -> "Gmail"
        "com.tencent.mm"                        -> "WeChat"
        "com.kakao.talk"                        -> "KakaoTalk"
        "com.twitter.android"                   -> "X"
        "com.zhiliaoapp.musically"              -> "TikTok"
        else                                    -> packageName.substringAfterLast(".")
    }

    companion object {
        private const val TAG = "LinkNotifService"
    }
}
