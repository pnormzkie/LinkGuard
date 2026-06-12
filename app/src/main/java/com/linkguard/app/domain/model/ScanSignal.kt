package com.linkguard.app.domain.model

data class ScanSignal(
    val ruleId: String,
    val title: String,
    val description: String,
    val strength: SignalStrength,
    val source: SignalSource,
    val score: Int,
    val matchedValue: String? = null,
    val metadata: Map<String, String> = emptyMap()
)
