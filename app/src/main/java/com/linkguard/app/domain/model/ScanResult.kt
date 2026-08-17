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
    val metadata: Map<String, String> = emptyMap(),
    // The true destination when [url] redirected somewhere else (shortener/wrapper); null when
    // the tapped URL was its own destination. Set by the orchestrator's redirect-resolution step.
    val resolvedUrl: String? = null,
    // Non-null only when redirect resolution reached a complete web destination. Unlike
    // [resolvedUrl], this deliberately excludes timeout/loop/blocked partial destinations and
    // is therefore safe for the intercept UI to use as the browser opening target.
    val verifiedResolvedUrl: String? = null
)
