package com.linkguard.app.domain.scoring

import com.linkguard.app.domain.model.Confidence
import com.linkguard.app.domain.model.ScanSignal
import com.linkguard.app.domain.model.ScanVerdict
import com.linkguard.app.domain.model.SignalSource
import com.linkguard.app.domain.model.SignalStrength
import com.linkguard.app.domain.model.Verdict

class ScoringEngine {

    companion object {
        private const val SCORE_SUSPICIOUS_THRESHOLD = 25
        private const val SCORE_THREAT_THRESHOLD = 60 // Threshold adjusted from 70 to 60 for better threat detection

        const val EXTERNAL_CHECKS_UNAVAILABLE_REASON =
            "External security checks unavailable — verdict based on local analysis only"
        const val EXTERNAL_CHECKS_PARTIAL_REASON =
            "Some external security checks unavailable — verdict based on partial coverage"

        fun isCoverageWarning(reason: String): Boolean =
            reason == EXTERNAL_CHECKS_UNAVAILABLE_REASON || reason == EXTERNAL_CHECKS_PARTIAL_REASON
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

                // Case 4: Mixed signals
                sources.size > 1 -> "Multiple security risks detected"
                
                // Case 1: Safe Browsing (External Reputation)
                sources.contains(SignalSource.EXTERNAL_REPUTATION) -> "Known phishing or malicious site"
                
                // Case 2: VirusTotal (Enrichment)
                sources.contains(SignalSource.ENRICHMENT) -> "Detected by multiple security vendors"
                
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
}
