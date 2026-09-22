package com.linkguard.app.accuracy

import com.linkguard.app.data.provider.HybridAnalysisProvider
import com.linkguard.app.data.provider.clientReturning
import com.linkguard.app.domain.model.ScanSignal
import com.linkguard.app.domain.model.Verdict
import com.linkguard.app.domain.orchestrator.ScanOrchestrator
import com.linkguard.app.domain.scanner.LegacyHeuristicEngine
import com.linkguard.app.domain.scanner.SignalProvider
import com.linkguard.app.domain.scoring.ScoringEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Measures false positives and false negatives over [ScanCorpus] using the REAL heuristic
 * engine, the REAL scoring engine and — where a payload was recorded — the REAL provider
 * parsing. Prints a per-URL table so a failure says which URL moved and in which direction,
 * not just that a count changed.
 *
 * Read [ScanCorpus]'s header before adding cases: the corpus is only worth what its inputs are
 * worth, and a hand-written vendor payload proves nothing.
 */
class ScanAccuracyTest {

    /**
     * A provider with nothing to say. This is the genuine outcome for a URL no vendor has a
     * report on — each provider's own test pins an empty list to a 404 or an empty match — so
     * the scan under test rests on the heuristics and the scoring engine.
     */
    private object NoReport : SignalProvider {
        override suspend fun fetchSignals(input: String): List<ScanSignal> = emptyList()
    }

    private fun orchestratorFor(case: ScanCorpus.Case): ScanOrchestrator {
        val hybridAnalysis: SignalProvider = case.hybridAnalysisBody
            ?.let { HybridAnalysisProvider(clientReturning(200, it), apiKey = "corpus-key") }
            ?: NoReport

        return ScanOrchestrator(
            heuristicEngine = LegacyHeuristicEngine(),
            reputationProvider = NoReport,
            domainSignalProvider = NoReport,
            enrichmentProvider = NoReport,
            scoringEngine = ScoringEngine(),
            hybridAnalysisProvider = hybridAnalysis,
            domainAgeProvider = NoReport,
            urlHausProvider = NoReport,
            // The corpus URLs are already final destinations, and page fetching is not part of
            // what this test measures.
            redirectResolver = null,
            credentialFormInspector = null
        )
    }

    private data class Outcome(
        val case: ScanCorpus.Case,
        val verdict: Verdict,
        val score: Int,
        val reason: String,
        /** Printed so a green run can't hide an external layer that never actually ran. */
        val coverage: ScoringEngine.CoverageState
    ) {
        val correct: Boolean
            get() = when (case.expected) {
                ScanCorpus.Expected.SAFE -> verdict == Verdict.SAFE
                ScanCorpus.Expected.FLAGGED -> verdict != Verdict.SAFE
            }
    }

    private suspend fun run(cases: List<ScanCorpus.Case>): List<Outcome> = cases.map { case ->
        val result = orchestratorFor(case).scan(case.url)
        Outcome(
            case,
            result.verdict.verdict,
            result.verdict.finalScore,
            result.verdict.primaryReason,
            ScoringEngine.coverageStateOf(result.verdict.secondaryReasons)
        )
    }

    private fun report(title: String, outcomes: List<Outcome>): String = buildString {
        appendLine()
        appendLine("── $title ".padEnd(100, '─'))
        outcomes.forEach { o ->
            appendLine(
                "%-4s %-46s %-11s %3d%%  %-11s %s".format(
                    if (o.correct) "ok" else "FAIL",
                    o.case.url.take(46),
                    o.verdict,
                    o.score,
                    o.coverage,
                    o.reason
                )
            )
        }
        val wrong = outcomes.count { !it.correct }
        appendLine("%d/%d correct, %d wrong".format(outcomes.size - wrong, outcomes.size, wrong))
    }

    @Test
    fun `legitimate urls are not flagged`() = runBlocking {
        val outcomes = run(ScanCorpus.LEGITIMATE)
        val falsePositives = outcomes.filterNot { it.correct }

        println(report("FALSE POSITIVES (legitimate URLs)", outcomes))

        assertTrue(
            "False positive(s): " + falsePositives.joinToString { "${it.case.url} -> ${it.verdict} ${it.score}%" },
            falsePositives.isEmpty()
        )
    }

    @Test
    fun `phishing shapes are still caught`() = runBlocking {
        val outcomes = run(ScanCorpus.PHISHING_SHAPES)
        val falseNegatives = outcomes.filterNot { it.correct }

        println(report("FALSE NEGATIVES (phishing shapes)", outcomes))

        assertTrue(
            "False negative(s): " + falseNegatives.joinToString { "${it.case.url} -> SAFE" },
            falseNegatives.isEmpty()
        )
    }

    @Test
    fun `the notion regression stays safe`() = runBlocking {
        // Guards the specific pairing that failed on 2026-09-22: a real low-score sandbox
        // report must contribute its points without dragging the badge away from the score.
        val case = ScanCorpus.LEGITIMATE.first { it.url == "https://www.notion.com/" }
        val outcome = run(listOf(case)).single()

        // Assert the sandbox report ARRIVED before asserting what it did. Without these two
        // lines the test passes when the provider silently fails, which is exactly how it
        // first went green: under a virtual-time dispatcher the 8s provider timeout fired
        // instantly, the signal never landed, and 0% "SAFE" looked like a pass.
        assertEquals(
            "the recorded sandbox report never reached the scan",
            ScoringEngine.CoverageState.FULL,
            outcome.coverage
        )
        assertEquals("threat_score 29 must contribute 14 points", 14, outcome.score)

        assertTrue(
            "notion.com scored ${outcome.score}% but rendered ${outcome.verdict}",
            outcome.verdict == Verdict.SAFE
        )
    }
}
