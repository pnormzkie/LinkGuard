package com.linkguard.app.domain.scoring

import com.linkguard.app.domain.model.Confidence
import com.linkguard.app.domain.model.ScanSignal
import com.linkguard.app.domain.model.ScanVerdict
import com.linkguard.app.domain.model.SignalSource
import com.linkguard.app.domain.model.SignalStrength
import com.linkguard.app.domain.model.Verdict

class ScoringEngine {

    /**
     * How much external coverage stood behind a verdict. The UI must tell these apart: a
     * verdict backed by four of six providers is not the same claim as one backed by none,
     * and reporting the first as "local rules only" understates what was actually checked.
     */
    enum class CoverageState { FULL, PARTIAL, LOCAL_ONLY }

    companion object {
        private const val SCORE_SUSPICIOUS_THRESHOLD = 25
        private const val SCORE_THREAT_THRESHOLD = 60 // Threshold adjusted from 70 to 60 for better threat detection

        /** Owned here, not by the orchestrator, because the category wording keys on it. */
        const val REDIRECT_UNRESOLVED_RULE_ID = "REDIRECT_UNRESOLVED"

        const val EXTERNAL_CHECKS_UNAVAILABLE_REASON =
            "External security checks unavailable — verdict based on local analysis only"
        const val EXTERNAL_CHECKS_PARTIAL_REASON =
            "Some external security checks unavailable — verdict based on partial coverage"

        /** The coverage these reasons describe. "Local only" wins if both are somehow present. */
        fun coverageStateOf(reasons: List<String>): CoverageState = when {
            reasons.contains(EXTERNAL_CHECKS_UNAVAILABLE_REASON) -> CoverageState.LOCAL_ONLY
            reasons.contains(EXTERNAL_CHECKS_PARTIAL_REASON) -> CoverageState.PARTIAL
            else -> CoverageState.FULL
        }

        fun isCoverageWarning(reason: String): Boolean =
            coverageStateOf(listOf(reason)) != CoverageState.FULL
    }

    /**
     * @param externalCoverageMissing true when every external provider failed (network error
     * or timeout), meaning the verdict rests on local heuristics alone. The verdict itself
     * is unchanged, but confidence is downgraded so "not vetted" never reads as "clean".
     */
    fun evaluate(
        signals: List<ScanSignal>,
        externalCoverageMissing: Boolean = false,
        externalCoveragePartial: Boolean = false
    ): ScanVerdict {
        // A provider or parser must not inflate risk by emitting the same rule repeatedly.
        val effectiveSignals = signals.distinctBy { Triple(it.ruleId, it.source, it.title) }
        val totalScore = effectiveSignals.sumOf { it.score }.coerceIn(0, 100)
        
        // Determine verdict based on thresholds or critical signals
        val verdict = when {
            effectiveSignals.any { it.strength == SignalStrength.CRITICAL } || totalScore >= SCORE_THREAT_THRESHOLD -> Verdict.THREAT
            effectiveSignals.any { it.strength == SignalStrength.STRONG } || totalScore >= SCORE_SUSPICIOUS_THRESHOLD -> Verdict.SUSPICIOUS
            else -> Verdict.SAFE
        }

        // Simplify category labeling as requested
        val sources = effectiveSignals.map { it.source }.distinct()
        val trackerOnly = effectiveSignals.isNotEmpty() &&
            effectiveSignals.all { it.title.contains("Tracker", ignoreCase = true) }
        val unresolvedRedirectOnly = effectiveSignals.isNotEmpty() &&
            effectiveSignals.all { it.ruleId == REDIRECT_UNRESOLVED_RULE_ID }
        val primaryReason = if (verdict == Verdict.SAFE) {
            "No risks detected"
        } else {
            when {
                // Confirmed malicious reputation must never be hidden by an accompanying tracker hit.
                effectiveSignals.any {
                    it.strength == SignalStrength.CRITICAL &&
                        it.source == SignalSource.EXTERNAL_REPUTATION
                } -> "Known phishing or malicious site"

                // Use the tracker category only when every effective signal is tracker-specific.
                trackerOnly -> "Ad/Tracker Detected"

                // "Could not check" is not "found something". When an unfollowable redirect is
                // the only evidence, "Suspicious link behavior detected" states a finding the
                // scan never made — the destination is unknown, not incriminated. The score is
                // deliberately unchanged: an unseen destination still warrants the warning.
                unresolvedRedirectOnly -> "Destination could not be verified"

                // Case 4: Mixed signals
                sources.size > 1 -> "Multiple security risks detected"
                
                // Case 1: Reputation blocklists. A CRITICAL hit is already handled above, so
                // only a STRONG one earns the "known malicious" wording here — a weak record
                // (e.g. a URLhaus entry whose tracked URLs are all offline) is supporting
                // evidence and must not be stated as a confirmed verdict.
                sources.contains(SignalSource.EXTERNAL_REPUTATION) -> {
                    val strongReputation = effectiveSignals.any {
                        it.source == SignalSource.EXTERNAL_REPUTATION &&
                            it.strength == SignalStrength.STRONG
                    }
                    if (strongReputation) "Known phishing or malicious site"
                    else "Reported on a security blocklist"
                }
                
                // Case 2: Enrichment (VirusTotal, Hybrid Analysis). Count the actual vendors —
                // a single sandbox hit must not read as "multiple".
                sources.contains(SignalSource.ENRICHMENT) -> {
                    val vendors = effectiveSignals
                        .filter { it.source == SignalSource.ENRICHMENT }
                        .vendorCount()
                    if (vendors == 1) "Flagged by 1 security vendor"
                    else "Flagged by $vendors security vendors"
                }
                
                // Case 3: Heuristic (Local)
                sources.contains(SignalSource.LOCAL_HEURISTIC) -> "Suspicious link behavior detected"
                
                // Fallback for others like NextDNS
                sources.contains(SignalSource.DOMAIN_SIGNAL) -> "Suspicious domain behavior detected"
                
                else -> effectiveSignals.firstOrNull()?.title ?: "Suspicious activity detected"
            }
        }

        val baseConfidence = when {
            effectiveSignals.isEmpty() -> Confidence.HIGH
            effectiveSignals.size > 1 || verdict == Verdict.THREAT -> Confidence.HIGH
            else -> Confidence.MEDIUM
        }
        val confidence = when {
            externalCoverageMissing && verdict == Verdict.SAFE -> Confidence.LOW // local-only
            externalCoverageMissing -> minOf(baseConfidence, Confidence.MEDIUM)
            externalCoveragePartial -> minOf(baseConfidence, Confidence.MEDIUM)
            else -> baseConfidence
        }

        val secondaryReasons = buildList {
            addAll(effectiveSignals.map { it.title }.distinct())
            if (externalCoverageMissing) add(EXTERNAL_CHECKS_UNAVAILABLE_REASON)
            else if (externalCoveragePartial) add(EXTERNAL_CHECKS_PARTIAL_REASON)
        }

        return ScanVerdict(
            verdict = verdict,
            finalScore = totalScore,
            confidence = confidence,
            primaryReason = primaryReason,
            secondaryReasons = secondaryReasons,
            appliedPolicyRule = "SIMPLIFIED_V4",
            signals = effectiveSignals
        )
    }

    /**
     * How many distinct vendors these signals represent. VirusTotal names the engines that
     * flagged it (metadata "vendor_names"); every other provider counts as one. Mirrors the
     * grouping in [com.linkguard.app.domain.mapper.toLegacy] so the category line and the
     * "Vendors flagged" count chip never disagree.
     */
    private fun List<ScanSignal>.vendorCount(): Int =
        flatMap { signal ->
            signal.metadata["vendor_names"]
                ?.split("||")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf(signal.title)
        }.distinct().size
}
