package com.linkguard.app.domain.mapper

import com.linkguard.app.domain.model.ScanResult as DomainScanResult
import com.linkguard.app.domain.model.ScanSignal
import com.linkguard.app.domain.model.SignalSource
import com.linkguard.app.domain.model.SignalStrength
import com.linkguard.app.domain.scoring.ScoringEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies toLegacy()'s flagGroups grouping (the data behind the collapsible verdict UI).
 * The flat `flags` list must stay byte-for-byte what it was (DB/history depend on it).
 */
class ScanMapperTest {

    private fun signal(
        title: String,
        source: SignalSource,
        score: Int = 20,
        strength: SignalStrength = SignalStrength.WEAK
    ) = ScanSignal(
        ruleId = "RULE",
        title = title,
        description = "desc",
        strength = strength,
        source = source,
        score = score
    )

    private fun legacyOf(signals: List<ScanSignal>) =
        DomainScanResult(
            url = "http://example.test",
            normalizedUrl = "http://example.test",
            verdict = ScoringEngine().evaluate(signals)
        ).toLegacy()

    @Test
    fun `heuristic-only signals form a single Heuristic group`() {
        val legacy = legacyOf(
            listOf(
                signal("Unencrypted HTTP connection", SignalSource.LOCAL_HEURISTIC),
                signal("Phishing keyword in domain", SignalSource.LOCAL_HEURISTIC)
            )
        )
        assertEquals(1, legacy.flagGroups.size)
        assertEquals("Heuristic", legacy.flagGroups[0].category)
        assertEquals(
            listOf("Unencrypted HTTP connection", "Phishing keyword in domain"),
            legacy.flagGroups[0].items
        )
    }

    @Test
    fun `reputation and enrichment merge into one Vendors flagged group`() {
        val legacy = legacyOf(
            listOf(
                signal("Flagged by Safe Browsing", SignalSource.EXTERNAL_REPUTATION),
                signal("Detected by 2 vendors", SignalSource.ENRICHMENT)
            )
        )
        assertEquals(1, legacy.flagGroups.size)
        assertEquals("Vendors flagged", legacy.flagGroups[0].category)
        assertEquals(
            listOf("Flagged by Safe Browsing", "Detected by 2 vendors"),
            legacy.flagGroups[0].items
        )
    }

    @Test
    fun `mixed sources produce groups in stable order`() {
        val legacy = legacyOf(
            listOf(
                signal("Blocked by NextDNS", SignalSource.DOMAIN_SIGNAL),
                signal("Detected by vendors", SignalSource.ENRICHMENT),
                signal("Suspicious keyword", SignalSource.LOCAL_HEURISTIC)
            )
        )
        assertEquals(
            listOf("Heuristic", "Vendors flagged", "Domain checks"),
            legacy.flagGroups.map { it.category }
        )
        assertEquals(listOf("Suspicious keyword"), legacy.flagGroups[0].items)
        assertEquals(listOf("Detected by vendors"), legacy.flagGroups[1].items)
        assertEquals(listOf("Blocked by NextDNS"), legacy.flagGroups[2].items)
    }

    @Test
    fun `no signals yields no groups`() {
        assertTrue(legacyOf(emptyList()).flagGroups.isEmpty())
    }

    @Test
    fun `duplicate titles within a group are de-duplicated`() {
        val legacy = legacyOf(
            listOf(
                signal("Unencrypted HTTP connection", SignalSource.LOCAL_HEURISTIC),
                signal("Unencrypted HTTP connection", SignalSource.LOCAL_HEURISTIC)
            )
        )
        assertEquals(1, legacy.flagGroups.size)
        assertEquals(listOf("Unencrypted HTTP connection"), legacy.flagGroups[0].items)
    }

    @Test
    fun `flat flags list is unchanged by grouping`() {
        val signals = listOf(
            signal("Suspicious keyword", SignalSource.LOCAL_HEURISTIC),
            signal("Detected by vendors", SignalSource.ENRICHMENT)
        )
        val legacy = legacyOf(signals)
        // flags still mirror the verdict's secondaryReasons (signal titles, de-duplicated).
        assertEquals(listOf("Suspicious keyword", "Detected by vendors"), legacy.flags)
    }
}
