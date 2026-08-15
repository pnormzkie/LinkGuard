package com.linkguard.app.domain.scanner

import com.linkguard.app.domain.model.SignalStrength
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyHeuristicEngineTest {
    private val engine = LegacyHeuristicEngine()

    @Test
    fun `generic login keyword remains weak corroborating evidence`() = runBlocking {
        val signals = engine.scan("https://example.com/login", null)
        val keyword = signals.single { it.title.contains("keyword", ignoreCase = true) }
        assertEquals(SignalStrength.WEAK, keyword.strength)
        assertEquals(10, keyword.score)
    }

    @Test
    fun `brand spoof remains strong after keyword calibration`() = runBlocking {
        val signals = engine.scan("https://secure-google.com/login", null)
        assertTrue(signals.any {
            it.title.contains("brand spoofing", ignoreCase = true) &&
                it.strength == SignalStrength.STRONG
        })
    }
}
