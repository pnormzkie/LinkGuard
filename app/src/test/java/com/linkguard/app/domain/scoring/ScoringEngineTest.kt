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
        title: String = "Test signal $score"
    ) = ScanSignal(
        ruleId = "TEST_RULE",
        title = title,
        description = "test",
        strength = strength,
        source = source,
        score = score
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
        val verdict = engine.evaluate(listOf(signal(30), signal(30)))
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
        val verdict = engine.evaluate(listOf(signal(15), signal(15)))
        assertEquals(Verdict.SUSPICIOUS, verdict.verdict)
    }

    @Test
    fun `weak low score yields safe`() {
        val verdict = engine.evaluate(listOf(signal(10)))
        assertEquals(Verdict.SAFE, verdict.verdict)
    }

    @Test
    fun `total score is clamped to 100`() {
        val verdict = engine.evaluate(listOf(signal(60), signal(60)))
        assertEquals(100, verdict.finalScore)
    }

    @Test
    fun `multiple signals raise confidence to high`() {
        val verdict = engine.evaluate(listOf(signal(15), signal(15)))
        assertEquals(Confidence.HIGH, verdict.confidence)
    }

    @Test
    fun `single non-threat signal has medium confidence`() {
        val verdict = engine.evaluate(listOf(signal(10)))
        assertEquals(Confidence.MEDIUM, verdict.confidence)
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
    fun `threat verdict without external coverage is capped at medium confidence`() {
        val verdict = engine.evaluate(
            listOf(signal(50, SignalStrength.CRITICAL), signal(50)),
            externalCoverageMissing = true
        )
        assertEquals(Verdict.THREAT, verdict.verdict)
        assertEquals(Confidence.MEDIUM, verdict.confidence)
        assertTrue(verdict.secondaryReasons.contains(ScoringEngine.EXTERNAL_CHECKS_UNAVAILABLE_REASON))
    }

    @Test
    fun `default parameter keeps full coverage behavior`() {
        val verdict = engine.evaluate(emptyList())
        assertEquals(Confidence.HIGH, verdict.confidence)
        assertFalse(verdict.secondaryReasons.contains(ScoringEngine.EXTERNAL_CHECKS_UNAVAILABLE_REASON))
    }
}
