package com.linkguard.app.domain.mapper

import com.linkguard.app.data.ScanResult as LegacyScanResult
import com.linkguard.app.data.ThreatLevel as LegacyThreatLevel
import com.linkguard.app.domain.model.ScanResult
import com.linkguard.app.domain.model.Verdict

/**
 * Maps the new domain ScanResult to the legacy Data ScanResult
 * to ensure compatibility with existing Room DB and UI without massive rewrites.
 */
fun ScanResult.toLegacy(sourceApp: String = "Manual", senderInfo: String = "You"): LegacyScanResult {
    return LegacyScanResult(
        url = this.url,
        threatLevel = when (this.verdict.verdict) {
            Verdict.THREAT -> LegacyThreatLevel.DANGER
            Verdict.SUSPICIOUS -> LegacyThreatLevel.SUSPICIOUS
            Verdict.SAFE -> LegacyThreatLevel.SAFE
        },
        riskScore = this.verdict.finalScore,
        category = this.verdict.primaryReason,
        flags = this.verdict.secondaryReasons,
        sourceApp = sourceApp,
        senderInfo = senderInfo,
        scannedAt = this.timestamp
    )
}
