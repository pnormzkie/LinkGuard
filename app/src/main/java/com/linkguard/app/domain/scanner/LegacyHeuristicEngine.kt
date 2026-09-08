package com.linkguard.app.domain.scanner

import com.linkguard.app.domain.model.ScanSignal
import com.linkguard.app.domain.model.SignalSource
import com.linkguard.app.domain.model.SignalStrength
import com.linkguard.app.scanner.HeuristicScanner
import com.linkguard.app.scanner.LocalHeuristicStrength

/**
 * A bridge between the new Domain Orchestrator and the legacy HeuristicScanner rules.
 * This preserves existing detection logic while allowing it to run within the new pipeline.
 */
class LegacyHeuristicEngine : HeuristicEngine {

    override suspend fun scan(url: String, messageText: String?): List<ScanSignal> {
        return HeuristicScanner.findingsWithContext(url, messageText).map { finding ->
            ScanSignal(
                ruleId = "LOCAL_${finding.ruleId}",
                title = finding.title,
                description = finding.title,
                strength = when (finding.strength) {
                    LocalHeuristicStrength.WEAK -> SignalStrength.WEAK
                    LocalHeuristicStrength.MEDIUM -> SignalStrength.MEDIUM
                    LocalHeuristicStrength.STRONG -> SignalStrength.STRONG
                    LocalHeuristicStrength.CRITICAL -> SignalStrength.CRITICAL
                },
                source = SignalSource.LOCAL_HEURISTIC,
                score = finding.score
            )
        }
    }
}
