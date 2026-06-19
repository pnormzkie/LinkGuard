package com.linkguard.app.domain.mapper

import com.linkguard.app.data.FlagGroup
import com.linkguard.app.data.ScanResult as LegacyScanResult
import com.linkguard.app.data.ThreatLevel as LegacyThreatLevel
import com.linkguard.app.domain.model.ScanResult
import com.linkguard.app.domain.model.SignalSource
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
        scannedAt = this.timestamp,
        flagGroups = this.verdict.signals.toFlagGroups()
    )
}

/** Human label for a signal's category, used as the collapsible group header. */
private fun SignalSource.groupLabel(): String = when (this) {
    SignalSource.LOCAL_HEURISTIC -> "Heuristic"
    SignalSource.EXTERNAL_REPUTATION, SignalSource.ENRICHMENT -> "Vendors flagged"
    SignalSource.DOMAIN_SIGNAL -> "Domain checks"
}

/** Stable display order for the groups (matches the agreed UX: heuristic first). */
private val GROUP_ORDER = listOf("Heuristic", "Vendors flagged", "Domain checks")

/**
 * Groups the verdict's signals by category for the collapsible flag UI. Each group's items are
 * the signal titles (de-duplicated). Empty when there are no signals (→ UI shows the flat list).
 */
private fun List<com.linkguard.app.domain.model.ScanSignal>.toFlagGroups(): List<FlagGroup> =
    groupBy { it.source.groupLabel() }
        .map { (label, sigs) -> FlagGroup(label, sigs.map { it.title }.distinct()) }
        .sortedBy { GROUP_ORDER.indexOf(it.category).let { i -> if (i < 0) Int.MAX_VALUE else i } }
