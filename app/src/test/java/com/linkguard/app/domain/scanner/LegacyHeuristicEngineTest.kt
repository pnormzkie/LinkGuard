package com.linkguard.app.domain.scanner

import com.linkguard.app.domain.model.SignalStrength
import com.linkguard.app.domain.model.Verdict
import com.linkguard.app.domain.scoring.ScoringEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `excessive subdomains remain weak instead of becoming a threat by wording`() = runBlocking {
        val signals = engine.scan("https://a.b.c.d.example.com", null)
        val subdomains = signals.single { it.ruleId == "LOCAL_EXCESSIVE_SUBDOMAINS" }

        assertEquals(SignalStrength.WEAK, subdomains.strength)
        assertEquals(15, subdomains.score)
        assertEquals(Verdict.SAFE, ScoringEngine().evaluate(signals).verdict)
    }

    @Test
    fun `elevated risk tld produces one capped signal`() = runBlocking {
        val signals = engine.scan("https://conference.vip", null)
        val tldSignals = signals.filter { it.ruleId == "LOCAL_ELEVATED_RISK_TLD" }

        assertEquals(1, tldSignals.size)
        assertEquals(SignalStrength.WEAK, tldSignals.single().strength)
        assertEquals(15, tldSignals.single().score)
        assertEquals(Verdict.SAFE, ScoringEngine().evaluate(signals).verdict)
    }

    @Test
    fun `mainstream io domain has no tld signal`() = runBlocking {
        val signals = engine.scan("https://portfolio.io", null)
        assertFalse(signals.any { it.ruleId == "LOCAL_ELEVATED_RISK_TLD" })
        assertEquals(Verdict.SAFE, ScoringEngine().evaluate(signals).verdict)
    }

    @Test
    fun `trusted github apk is weak download context only`() = runBlocking {
        val signals = engine.scan(
            "https://github.com/pnormzkie/LinkGuard/releases/download/v1.28/LinkGuard-v1.28.apk",
            null
        )
        val file = signals.single { it.ruleId == "LOCAL_DANGEROUS_FILE_TYPE_APK" }

        assertEquals(SignalStrength.WEAK, file.strength)
        assertEquals(10, file.score)
        assertEquals(Verdict.SAFE, ScoringEngine().evaluate(signals).verdict)
    }

    @Test
    fun `untrusted apk download remains suspicious`() = runBlocking {
        val signals = engine.scan("https://files.example.com/setup.apk", null)
        val file = signals.single { it.ruleId == "LOCAL_DANGEROUS_FILE_TYPE_APK" }

        assertEquals(SignalStrength.MEDIUM, file.strength)
        assertEquals(25, file.score)
        assertEquals(Verdict.SUSPICIOUS, ScoringEngine().evaluate(signals).verdict)
    }
}
