package com.linkguard.app.domain.scanner

import com.linkguard.app.domain.model.ScanSignal
import com.linkguard.app.domain.model.SignalSource
import com.linkguard.app.domain.model.SignalStrength
import com.linkguard.app.scanner.HeuristicScanner

/**
 * A bridge between the new Domain Orchestrator and the legacy HeuristicScanner rules.
 * This preserves existing detection logic while allowing it to run within the new pipeline.
 */
class LegacyHeuristicEngine : HeuristicEngine {

    override suspend fun scan(url: String, messageText: String?): List<ScanSignal> {
        // We use the existing scan logic from the legacy HeuristicScanner
        val legacyResult = HeuristicScanner.scanWithContext(url, messageText)
        
        return legacyResult.flags.map { flag ->
            // Inferred mapping of legacy flag text to structured SignalStrength
            val strength = when {
                flag.contains("spoofing", ignoreCase = true) || 
                flag.contains("Lookalike", ignoreCase = true) ||
                flag.contains("typosquatting", ignoreCase = true) ||
                flag.contains("subdomain", ignoreCase = true) -> SignalStrength.STRONG
                
                flag.contains("keyword", ignoreCase = true) || 
                flag.contains("encoded", ignoreCase = true) ||
                flag.contains("extension", ignoreCase = true) ||
                flag.contains("TLD", ignoreCase = true) ||
                flag.contains("pattern", ignoreCase = true) ||
                flag.contains("IP address", ignoreCase = true) ||
                flag.contains("file type", ignoreCase = true) ||
                flag.contains("shortener", ignoreCase = true) -> SignalStrength.MEDIUM
                
                else -> SignalStrength.WEAK
            }

            ScanSignal(
                ruleId = "LEGACY_${flag.filter { it.isLetterOrDigit() }}",
                title = flag,
                description = flag,
                strength = strength,
                source = SignalSource.LOCAL_HEURISTIC,
                score = when(strength) {
                    SignalStrength.STRONG -> 60
                    SignalStrength.MEDIUM -> 25
                    else -> 15
                }
            )
        }
    }
}
