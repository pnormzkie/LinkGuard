package com.linkguard.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanSourceLabelTest {

    private fun label(app: String, sender: String) = scanSourceLabel(
        app, sender,
        manualLabel = "Manual Scan",
        qrLabel = "QR Scan",
        tappedLabel = "Tapped link",
        appSenderFormat = "%1\$s · %2\$s",
    )

    @Test
    fun `a report opened from an SMS alert names the app and the sender`() {
        // v1.36 showed "Manual Scan" here (exploratory finding #3, 2026-09-27).
        assertEquals("SMS · 555-0001", label("SMS", "555-0001"))
    }

    @Test
    fun `a notification without a sender shows the app alone`() {
        assertEquals("WhatsApp", label("WhatsApp", "Unknown"))
        assertEquals("Telegram", label("Telegram", ""))
    }

    @Test
    fun `a tapped link reads as tapped, not manual`() {
        assertEquals("Tapped link", label("Link Tap", "Tapped link"))
    }

    @Test
    fun `manual and QR scans keep their existing labels`() {
        assertEquals("Manual Scan", label("Manual", "You"))
        assertEquals("Manual Scan", label("", ""))
        assertEquals("QR Scan", label("QR", "Physical Code"))
    }
}
