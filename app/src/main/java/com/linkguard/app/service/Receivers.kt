package com.linkguard.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.linkguard.app.BuildConfig
import com.linkguard.app.ScannerProvider
import com.linkguard.app.data.ScanRepository
import com.linkguard.app.data.ThreatLevel
import com.linkguard.app.domain.mapper.toLegacy
import com.linkguard.app.scanner.UrlExtractor
import com.linkguard.app.util.AppConfig
import com.linkguard.app.util.ThreatAlertHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// ─── SMS Receiver ─────────────────────────────────────────────────────────────

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val sender = messages.firstOrNull()?.displayOriginatingAddress ?: "Unknown"
        val fullText = messages.joinToString(" ") { it.messageBody.orEmpty() }

        val urls = UrlExtractor.extractUrls(fullText)
        if (urls.isEmpty()) return

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "SMS from $sender — ${urls.size} URL(s) found")
        } else {
            Log.d(TAG, "SMS received — ${urls.size} URL(s) found")
        }

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                // Using the modern ScanOrchestrator instead of legacy MasterScanner
                val orchestrator = ScannerProvider.orchestrator
                val repository = ScanRepository(context.applicationContext)

                urls.forEach { url ->
                    try {
                        // Pass fullText context to the scanner for smishing pattern detection
                        val domainResult = orchestrator.scan(url, messageText = fullText)
                        val legacyResult = domainResult.toLegacy(sourceApp = "SMS", senderInfo = sender)
                        
                        repository.saveScan(legacyResult)

                        context.sendBroadcast(
                            Intent(AppConfig.SCAN_COMPLETE_ACTION).apply {
                                `package` = context.packageName
                                putExtra(AppConfig.Extras.URL, url)
                                putExtra(AppConfig.Extras.THREAT_LEVEL, legacyResult.threatLevel.name)
                                putExtra(AppConfig.Extras.SCORE, legacyResult.riskScore)
                            }
                        )

                        // Trigger heads-up alert for threats
                        if (legacyResult.threatLevel != ThreatLevel.SAFE) {
                            ThreatAlertHelper.alert(context, legacyResult)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Scan error${if (BuildConfig.DEBUG) " for $url" else ""}: ${e.message}")
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}

// ─── Boot Receiver ────────────────────────────────────────────────────────────

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Device booted — LinkGuard protection active")
        }
    }
}
