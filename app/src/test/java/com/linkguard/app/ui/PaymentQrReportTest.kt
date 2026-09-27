package com.linkguard.app.ui

import com.linkguard.app.data.ScanResult
import com.linkguard.app.data.ThreatLevel
import com.linkguard.app.data.isUnverifiedPaymentQr
import com.linkguard.app.data.matchesHistoryFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentQrReportTest {

    private fun scan(level: ThreatLevel, category: String, app: String = "QR") = ScanResult(
        url = "Payment QR: Normzkie", threatLevel = level, riskScore = 25, category = category,
        flags = emptyList(), sourceApp = app, senderInfo = "Physical Code"
    )

    private val validQr = scan(ThreatLevel.SUSPICIOUS, "Payment QR — Payee unverified")
    private val invalidQr = scan(ThreatLevel.SUSPICIOUS, "Invalid Payment QR")

    @Test
    fun `a valid payment QR from the scanner is shown as check the payee`() {
        assertTrue(isUnverifiedPaymentQr("QR", "Payment QR — Payee unverified"))
        assertTrue(validQr.isUnverifiedPaymentQr)
    }

    @Test
    fun `an invalid payment QR keeps its warning`() {
        assertFalse(isUnverifiedPaymentQr("QR", "Invalid Payment QR"))
    }

    @Test
    fun `links keep the normal report`() {
        assertFalse(isUnverifiedPaymentQr("QR", "Phishing"))
        assertFalse(isUnverifiedPaymentQr("Manual", "Payment QR — Payee unverified"))
    }

    @Test
    fun `a valid payment QR is under All but not under the Suspicious tab`() {
        assertTrue(validQr.matchesHistoryFilter(null))
        assertFalse(validQr.matchesHistoryFilter(ThreatLevel.SUSPICIOUS.name))
    }

    @Test
    fun `an invalid payment QR and suspicious links stay under the Suspicious tab`() {
        assertTrue(invalidQr.matchesHistoryFilter(ThreatLevel.SUSPICIOUS.name))
        assertTrue(scan(ThreatLevel.SUSPICIOUS, "Phishing", app = "Link Tap").matchesHistoryFilter("SUSPICIOUS"))
        assertFalse(scan(ThreatLevel.SAFE, "No risks detected", app = "Link Tap").matchesHistoryFilter("SUSPICIOUS"))
    }
}
