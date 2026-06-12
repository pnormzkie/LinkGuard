package com.linkguard.app.domain.model

data class ScanVerdict(
    val verdict: Verdict,
    val finalScore: Int,
    val confidence: Confidence,
    val primaryReason: String,
    val secondaryReasons: List<String>,
    val appliedPolicyRule: String,
    val signals: List<ScanSignal>
)
