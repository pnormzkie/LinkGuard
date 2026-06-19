package com.linkguard.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [formatReleaseNotes] — the lightweight Markdown-to-lines reducer used by
 * the "Update available" hero card. Verifies headers, bullets, emphasis/link stripping, and
 * that noise (checksum footer, horizontal rules, blanks) is dropped.
 */
class ReleaseNotesFormatTest {

    @Test fun `blank input yields no lines`() {
        assertTrue(formatReleaseNotes("").isEmpty())
        assertTrue(formatReleaseNotes("   \n  \n").isEmpty())
    }

    @Test fun `hash heading becomes a header`() {
        val lines = formatReleaseNotes("## What's new")
        assertEquals(1, lines.size)
        assertEquals(NoteLine("What's new", isHeader = true), lines[0])
    }

    @Test fun `bold-wrapped line becomes a header with markers stripped`() {
        val lines = formatReleaseNotes("**See which vendors flagged it**")
        assertEquals(NoteLine("See which vendors flagged it", isHeader = true), lines[0])
    }

    @Test fun `dash and star bullets become non-header bullets`() {
        val lines = formatReleaseNotes("- First point\n* Second point")
        assertEquals(2, lines.size)
        assertEquals(NoteLine("First point", isHeader = false), lines[0])
        assertEquals(NoteLine("Second point", isHeader = false), lines[1])
    }

    @Test fun `inline bold and code markers are removed from a bullet`() {
        val lines = formatReleaseNotes("- Shows **specific** engines and `counts`")
        assertEquals(NoteLine("Shows specific engines and counts", isHeader = false), lines[0])
    }

    @Test fun `markdown link is reduced to its text`() {
        val lines = formatReleaseNotes("- Flagged by [Google Safebrowsing](https://example.com)")
        assertEquals("Flagged by Google Safebrowsing", lines[0].text)
    }

    @Test fun `checksum footer and horizontal rules are dropped`() {
        val raw = """
            ## What's new
            - A change
            ---
            SHA-256 (LinkGuard-v1.13.apk): abc123
        """.trimIndent()
        val lines = formatReleaseNotes(raw)
        assertEquals(2, lines.size)
        assertEquals("What's new", lines[0].text)
        assertEquals("A change", lines[1].text)
        assertFalse(lines.any { it.text.startsWith("SHA-256") })
        assertFalse(lines.any { it.text.contains("---") })
    }

    @Test fun `prose line with no marker is kept as a bullet`() {
        val lines = formatReleaseNotes("Detection and verdicts are unchanged.")
        assertEquals(NoteLine("Detection and verdicts are unchanged.", isHeader = false), lines[0])
    }

    @Test fun `output is capped to a sane maximum`() {
        val raw = (1..50).joinToString("\n") { "- Bullet $it" }
        assertEquals(15, formatReleaseNotes(raw).size)
    }
}
