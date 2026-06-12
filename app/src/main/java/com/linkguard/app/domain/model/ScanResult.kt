package com.linkguard.app.domain.model

import java.util.UUID

/**
 * Domain-level representation of a scan outcome.
 */
data class ScanResult(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val normalizedUrl: String,
    val verdict: ScanVerdict,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap()
)
