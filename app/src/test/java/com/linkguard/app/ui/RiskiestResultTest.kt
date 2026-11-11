package com.linkguard.app.ui

import com.linkguard.app.data.ScanResult
import com.linkguard.app.data.ThreatLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RiskiestResultTest {

    private fun result(url: String, level: ThreatLevel, score: Int) = ScanResult(
        url = url, threatLevel = level, riskScore = score, category = "",
        flags = emptyList(), sourceApp = "Shared", senderInfo = "",
    )

    private val clean = result("https://bdo.com.ph", ThreatLevel.SAFE, 0)
    private val bad = result("https://evil.xyz/login", ThreatLevel.DANGER, 80)

    @Test
    fun `a dangerous link after a clean one is the one shown`() {
        assertEquals("https://evil.xyz/login", riskiestOf(listOf(clean, bad))?.url)
    }

    @Test
    fun `threat level outranks score`() {
        val shown = riskiestOf(
            listOf(
                result("https://a.example", ThreatLevel.SUSPICIOUS, 59),
                result("https://b.example", ThreatLevel.DANGER, 55),
            )
        )
        assertEquals("https://b.example", shown?.url)
    }

    @Test
    fun `within a level the higher score wins and a tie keeps the first link`() {
        assertEquals(
            "https://b.example",
            riskiestOf(
                listOf(
                    result("https://a.example", ThreatLevel.SUSPICIOUS, 30),
                    result("https://b.example", ThreatLevel.SUSPICIOUS, 45),
                )
            )?.url,
        )
        assertEquals(
            "https://first.example",
            riskiestOf(
                listOf(
                    result("https://first.example", ThreatLevel.SAFE, 0),
                    result("https://second.example", ThreatLevel.SAFE, 0),
                )
            )?.url,
        )
    }

    @Test
    fun `no results means nothing to show`() {
        assertNull(riskiestOf(emptyList()))
    }

    // ─── Which card ─────────────────────────────────────────────────────────────

    @Test
    fun `one link behaves as a tap always did`() {
        assertEquals(CardOutcome.Safe(clean), cardOutcomeOf(listOf(clean.url), listOf(clean), 0))
        assertEquals(CardOutcome.Block(bad), cardOutcomeOf(listOf(bad.url), listOf(bad), 0))
        assertEquals(
            CardOutcome.ScanFailed("https://x.example"),
            cardOutcomeOf(listOf("https://x.example"), listOf(null), 0),
        )
    }

    @Test
    fun `a flagged link wins over a failed scan and over links left unscanned`() {
        assertEquals(
            CardOutcome.Block(bad),
            cardOutcomeOf(listOf("https://x.example", bad.url), listOf(null, bad), linksNotScanned = 2),
        )
    }

    @Test
    fun `a failed scan is never reported safe on the strength of the others`() {
        assertEquals(
            CardOutcome.ScanFailed("https://x.example"),
            cardOutcomeOf(listOf(clean.url, "https://x.example"), listOf(clean, null), 0),
        )
    }

    // Found on the emulator 2026-11-11: five links with the bad one fourth read SAFE LINK,
    // because only the first three are scanned.
    @Test
    fun `clean links in front of unscanned ones do not make the text safe`() {
        assertEquals(
            CardOutcome.PartlyChecked,
            cardOutcomeOf(listOf(clean.url), listOf(clean), linksNotScanned = 2),
        )
    }
}
