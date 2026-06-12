package com.linkguard.app.domain.scanner

import com.linkguard.app.domain.model.ScanSignal

/**
 * Base interface for all external and internal signal sources.
 */
interface SignalProvider {
    suspend fun fetchSignals(input: String): List<ScanSignal>
}
