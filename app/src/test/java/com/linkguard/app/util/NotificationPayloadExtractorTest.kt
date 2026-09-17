package com.linkguard.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPayloadExtractorTest {

    @Test
    fun `big text mirroring short text is collapsed`() {
        val combined = NotificationPayloadExtractor.collectText(
            title = "Alex",
            text = "Check http://phish.example/login now",
            bigText = "Check http://phish.example/login now",
            textLines = null,
            messages = null
        )

        // The URL appears once, not twice.
        assertEquals(1, Regex("http://phish\\.example/login").findAll(combined).count())
    }

    @Test
    fun `inbox lines and messaging bundles are included`() {
        val combined = NotificationPayloadExtractor.collectText(
            title = "Anna",
            text = "2 new messages",
            bigText = null,
            textLines = listOf("Anna: pay at https://pay.example/inv-7", "Bob: thanks"),
            messages = listOf("wire the fee to safe-pay.example/transfer")
        )

        assertTrue(combined.contains("https://pay.example/inv-7"))
        assertTrue(combined.contains("Bob: thanks"))
        assertTrue(combined.contains("safe-pay.example/transfer"))
    }

    @Test
    fun `case sensitive paths are preserved`() {
        assertEquals(
            "https://example.com/INV https://example.com/inv",
            NotificationPayloadExtractor.collectText(
                null, "https://example.com/INV", "https://example.com/inv", null, null
            )
        )
    }

    @Test
    fun `blank and null segments are dropped`() {
        val combined = NotificationPayloadExtractor.collectText(
            title = "",
            text = null,
            bigText = "  ",
            textLines = listOf("", "  ", "only-real-line"),
            messages = null
        )

        assertEquals("only-real-line", combined)
    }

    @Test
    fun `all inputs null or empty yields empty string`() {
        assertEquals("", NotificationPayloadExtractor.collectText(null, null, null, null, null))
        assertEquals("", NotificationPayloadExtractor.collectText("", "", "", emptyList(), emptyList()))
    }
}
