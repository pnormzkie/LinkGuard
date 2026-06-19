package com.linkguard.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip of the persisted flagGroups column (history grouping). Pre-v2 rows have a null
 * column and must read back as an empty list so the UI falls back to the flat flags.
 */
class ScanDataMapperTest {

    private fun result(groups: List<FlagGroup>) = ScanResult(
        url = "http://x.test",
        threatLevel = ThreatLevel.DANGER,
        riskScore = 100,
        category = "Suspicious link behavior detected",
        flags = listOf("Unencrypted HTTP connection", "2 Vendors Flagged"),
        sourceApp = "Manual",
        senderInfo = "You",
        flagGroups = groups
    )

    @Test
    fun `flagGroups survive entity round-trip`() {
        val groups = listOf(
            FlagGroup("Heuristic", listOf("Unencrypted HTTP connection")),
            FlagGroup("Vendors flagged", listOf("Google Safebrowsing", "Phishtank"), count = 2)
        )
        val back = result(groups).toEntity().toDomain()
        assertEquals(groups, back.flagGroups)
        assertEquals(listOf("Unencrypted HTTP connection", "2 Vendors Flagged"), back.flags)
    }

    @Test
    fun `empty flagGroups persist as null column`() {
        assertNull(result(emptyList()).toEntity().flagGroups)
    }

    @Test
    fun `pre-v2 row with null flagGroups reads back empty`() {
        val legacyRow = ScanHistoryEntity(
            url = "http://x.test",
            threatLevel = "DANGER",
            riskScore = 100,
            category = "c",
            flags = """["a","b"]""",
            sourceApp = "Manual",
            senderInfo = "You",
            scannedAt = 0L,
            flagGroups = null
        )
        assertTrue(legacyRow.toDomain().flagGroups.isEmpty())
    }
}
