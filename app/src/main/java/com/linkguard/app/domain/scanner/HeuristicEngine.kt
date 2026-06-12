package com.linkguard.app.domain.scanner

import com.linkguard.app.domain.model.ScanSignal

/**
 * Interface for heuristic analysis of URLs and domains.
 */
interface HeuristicEngine {
    /**
     * Analyzes a URL for suspicious patterns.
     */
    suspend fun scan(url: String, messageText: String? = null): List<ScanSignal>
}
