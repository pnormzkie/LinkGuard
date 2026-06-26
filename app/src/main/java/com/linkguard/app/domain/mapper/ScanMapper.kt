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
        flagGroups = this.verdict.signals.toFlagGroups(),
        resolvedUrl = this.resolvedUrl
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

/** Max detail lines shown in one group before collapsing the rest into a "+N more" line. */
private const val GROUP_ITEM_CAP = 8

/**
 * The detail lines a signal contributes. VirusTotal carries the specific engine names that
 * flagged it (metadata "vendor_names") so the user sees who flagged it instead of a bare count;
 * every other signal contributes its own title.
 */
private fun com.linkguard.app.domain.model.ScanSignal.detailItems(): List<String> {
    val vendors = metadata["vendor_names"]
        ?.split("||")?.map { it.trim() }?.filter { it.isNotEmpty() }
    return if (!vendors.isNullOrEmpty()) vendors else listOf(title)
}

/**
 * Groups the verdict's signals by category for the collapsible flag UI. Items are the per-signal
 * detail lines (de-duplicated); the group's `count` is the true total even when the visible list
 * is capped. Empty when there are no signals (→ UI shows the flat list).
 */
private fun List<com.linkguard.app.domain.model.ScanSignal>.toFlagGroups(): List<FlagGroup> =
    groupBy { it.source.groupLabel() }
        .map { (label, sigs) ->
            val all = sigs.flatMap { it.detailItems() }.distinct()
            val items = if (all.size > GROUP_ITEM_CAP) {
                all.take(GROUP_ITEM_CAP) + "+${all.size - GROUP_ITEM_CAP} more"
            } else all
            FlagGroup(category = label, items = items, count = all.size)
        }
        .sortedBy { GROUP_ORDER.indexOf(it.category).let { i -> if (i < 0) Int.MAX_VALUE else i } }
