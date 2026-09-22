package com.linkguard.app.domain.scoring

import com.linkguard.app.domain.model.Confidence
import com.linkguard.app.domain.model.ScanSignal
import com.linkguard.app.domain.model.SignalSource
import com.linkguard.app.domain.model.SignalStrength
import com.linkguard.app.domain.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringEngineTest {

    private val engine = ScoringEngine()

    private fun signal(
        score: Int,
        strength: SignalStrength = SignalStrength.WEAK,
        source: SignalSource = SignalSource.LOCAL_HEURISTIC,
        title: String = "Test signal $score",
        metadata: Map<String, String> = emptyMap()
    ) = ScanSignal(
        ruleId = "TEST_${title.filter { it.isLetterOrDigit() }}",
        title = title,
        description = "test",
        strength = strength,
        source = source,
        score = score,
        metadata = metadata
    )

    @Test
    fun `no signals means safe with high confidence`() {
        val verdict = engine.evaluate(emptyList())
        assertEquals(Verdict.SAFE, verdict.verdict)
        assertEquals(0, verdict.finalScore)
        assertEquals(Confidence.HIGH, verdict.confidence)
        assertEquals("No risks detected", verdict.primaryReason)
    }

    @Test
    fun `score at threat threshold yields threat`() {
        val verdict = engine.evaluate(listOf(signal(30, title = "A"), signal(30, title = "B")))
        assertEquals(Verdict.THREAT, verdict.verdict)
        assertEquals(60, verdict.finalScore)
    }

    @Test
    fun `critical signal yields threat regardless of score`() {
        val verdict = engine.evaluate(listOf(signal(10, SignalStrength.CRITICAL)))
        assertEquals(Verdict.THREAT, verdict.verdict)
    }

    @Test
    fun `strong signal yields suspicious regardless of score`() {
        val verdict = engine.evaluate(listOf(signal(10, SignalStrength.STRONG)))
        assertEquals(Verdict.SUSPICIOUS, verdict.verdict)
    }

    @Test
    fun `score at suspicious threshold yields suspicious`() {
        val verdict = engine.evaluate(listOf(signal(15, title = "A"), signal(15, title = "B")))
        assertEquals(Verdict.SUSPICIOUS, verdict.verdict)
    }

    @Test
    fun `weak low score yields safe`() {
        val verdict = engine.evaluate(listOf(signal(10)))
        assertEquals(Verdict.SAFE, verdict.verdict)
    }

    @Test
    fun `total score is clamped to 100`() {
        val verdict = engine.evaluate(listOf(signal(60, title = "A"), signal(60, title = "B")))
        assertEquals(100, verdict.finalScore)
    }

    @Test
    fun `multiple signals raise confidence to high`() {
        val verdict = engine.evaluate(listOf(signal(15, title = "A"), signal(15, title = "B")))
        assertEquals(Confidence.HIGH, verdict.confidence)
    }

    @Test
    fun `single non-threat signal has medium confidence`() {
        val verdict = engine.evaluate(listOf(signal(10)))
        assertEquals(Confidence.MEDIUM, verdict.confidence)
    }

    @Test
    fun `duplicate rule cannot inflate score or confidence`() {
        val duplicate = signal(20)
        val verdict = engine.evaluate(listOf(duplicate, duplicate, duplicate))
        assertEquals(20, verdict.finalScore)
        assertEquals(1, verdict.signals.size)
        assertEquals(Confidence.MEDIUM, verdict.confidence)
    }

    @Test
    fun `tracker label does not mask a critical reputation finding`() {
        val verdict = engine.evaluate(listOf(
            signal(10, source = SignalSource.DOMAIN_SIGNAL, title = "Ad/Tracker Filter"),
            signal(100, SignalStrength.CRITICAL, SignalSource.EXTERNAL_REPUTATION, "URLhaus malware")
        ))

        assertEquals(Verdict.THREAT, verdict.verdict)
        assertEquals("Known phishing or malicious site", verdict.primaryReason)
    }

    @Test
    fun `tracker category is reserved for tracker-only suspicious evidence`() {
        val verdict = engine.evaluate(listOf(
            signal(15, source = SignalSource.DOMAIN_SIGNAL, title = "Tracker DNS match"),
            signal(15, source = SignalSource.ENRICHMENT, title = "Tracker sandbox match")
        ))

        assertEquals(Verdict.SUSPICIOUS, verdict.verdict)
        assertEquals("Ad/Tracker Detected", verdict.primaryReason)
    }

    @Test
    fun `a single sandbox hit is not described as multiple vendors`() {
        val verdict = engine.evaluate(listOf(
            signal(20, SignalStrength.STRONG, SignalSource.ENRICHMENT, "Falcon Sandbox: Suspicious")
        ))

        assertEquals(Verdict.SUSPICIOUS, verdict.verdict)
        assertEquals("Flagged by 1 security vendor", verdict.primaryReason)
    }

    @Test
    fun `enrichment category counts the named vendors that flagged it`() {
        val verdict = engine.evaluate(listOf(
            signal(
                20, SignalStrength.STRONG, SignalSource.ENRICHMENT, "VirusTotal detection",
                metadata = mapOf("vendor_names" to "Fortinet||Sophos||Kaspersky")
            )
        ))

        assertEquals("Flagged by 3 security vendors", verdict.primaryReason)
    }

    @Test
    fun `a low-score weak signal cannot contradict its own risk score`() {
        // Regression for the 14%-scored scan that still displayed a SUSPICIOUS badge.
        val verdict = engine.evaluate(listOf(
            signal(14, SignalStrength.WEAK, SignalSource.ENRICHMENT, "Falcon Sandbox: Suspicious")
        ))

        assertEquals(Verdict.SAFE, verdict.verdict)
        assertEquals(14, verdict.finalScore)
        assertEquals("No risks detected", verdict.primaryReason)
    }

    @Test
    fun `a weak reputation record is not stated as a known malicious site`() {
        // e.g. URLHAUS_HISTORICAL_MALWARE, whose own description says every tracked URL is
        // offline. Supporting evidence must not be reported as a confirmed verdict.
        val verdict = engine.evaluate(listOf(
            signal(30, SignalStrength.WEAK, SignalSource.EXTERNAL_REPUTATION, "Historical URLhaus record")
        ))

        assertEquals(Verdict.SUSPICIOUS, verdict.verdict)
        assertEquals("Reported on a security blocklist", verdict.primaryReason)
    }

    @Test
    fun `a strong reputation hit keeps the known malicious wording`() {
        val verdict = engine.evaluate(listOf(
            signal(30, SignalStrength.STRONG, SignalSource.EXTERNAL_REPUTATION, "Blocklist match")
        ))

        assertEquals("Known phishing or malicious site", verdict.primaryReason)
    }

    // ── External coverage missing (all providers failed) ─────────────────────

    @Test
    fun `safe verdict without external coverage has low confidence`() {
        val verdict = engine.evaluate(emptyList(), externalCoverageMissing = true)
        assertEquals(Verdict.SAFE, verdict.verdict)
        assertEquals(Confidence.LOW, verdict.confidence)
        assertTrue(verdict.secondaryReasons.contains(ScoringEngine.EXTERNAL_CHECKS_UNAVAILABLE_REASON))
    }

    @Test
    fun `safe verdict with partial external coverage has medium confidence`() {
        val verdict = engine.evaluate(emptyList(), externalCoveragePartial = true)

        assertEquals(Verdict.SAFE, verdict.verdict)
        assertEquals(Confidence.MEDIUM, verdict.confidence)
        assertTrue(verdict.secondaryReasons.contains(ScoringEngine.EXTERNAL_CHECKS_PARTIAL_REASON))
    }

    @Test
    fun `threat verdict without external coverage is capped at medium confidence`() {
        val verdict = engine.evaluate(
            listOf(signal(50, SignalStrength.CRITICAL, title = "Critical"), signal(50, title = "Other")),
            externalCoverageMissing = true
        )
        assertEquals(Verdict.THREAT, verdict.verdict)
        assertEquals(Confidence.MEDIUM, verdict.confidence)
        assertTrue(verdict.secondaryReasons.contains(ScoringEngine.EXTERNAL_CHECKS_UNAVAILABLE_REASON))
    }

    // ── Coverage state (drives which reason line the safe screen shows) ──────

    @Test
    fun `partial coverage is not reported as local-only`() {
        // Regression: the safe screen said "local rules only" after a scan where four of six
        // providers answered, because both coverage states mapped to the same string.
        val verdict = engine.evaluate(emptyList(), externalCoveragePartial = true)

        assertEquals(
            ScoringEngine.CoverageState.PARTIAL,
            ScoringEngine.coverageStateOf(verdict.secondaryReasons)
        )
    }

    @Test
    fun `no external provider succeeded is reported as local-only`() {
        val verdict = engine.evaluate(emptyList(), externalCoverageMissing = true)

        assertEquals(
            ScoringEngine.CoverageState.LOCAL_ONLY,
            ScoringEngine.coverageStateOf(verdict.secondaryReasons)
        )
    }

    @Test
    fun `full coverage carries no coverage reason`() {
        val verdict = engine.evaluate(emptyList())

        assertEquals(
            ScoringEngine.CoverageState.FULL,
            ScoringEngine.coverageStateOf(verdict.secondaryReasons)
        )
    }

    @Test
    fun `both coverage states present resolves to the more cautious one`() {
        val reasons = listOf(
            ScoringEngine.EXTERNAL_CHECKS_PARTIAL_REASON,
            ScoringEngine.EXTERNAL_CHECKS_UNAVAILABLE_REASON
        )
        assertEquals(ScoringEngine.CoverageState.LOCAL_ONLY, ScoringEngine.coverageStateOf(reasons))
    }

    @Test
    fun `isCoverageWarning still recognises both reason strings`() {
        assertTrue(ScoringEngine.isCoverageWarning(ScoringEngine.EXTERNAL_CHECKS_PARTIAL_REASON))
        assertTrue(ScoringEngine.isCoverageWarning(ScoringEngine.EXTERNAL_CHECKS_UNAVAILABLE_REASON))
        assertFalse(ScoringEngine.isCoverageWarning("Suspicious link behavior detected"))
    }

    @Test
    fun `default parameter keeps full coverage behavior`() {
        val verdict = engine.evaluate(emptyList())
        assertEquals(Confidence.HIGH, verdict.confidence)
        assertFalse(verdict.secondaryReasons.contains(ScoringEngine.EXTERNAL_CHECKS_UNAVAILABLE_REASON))
    }
}
