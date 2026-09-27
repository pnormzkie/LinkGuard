package com.linkguard.app.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class QrTypeDetectorTest {
    @Test
    fun `https QR is classified as URL`() {
        assertEquals(QrType.URL, QrTypeDetector.detect("https://example.com/login"))
    }

    @Test
    fun `domain-only QR is classified as URL for normalized scanning`() {
        assertEquals(QrType.URL, QrTypeDetector.detect("secure-login.example.com/account"))
    }

    @Test
    fun `dangerous non-web schemes are not classified as URL`() {
        assertEquals(QrType.UNKNOWN, QrTypeDetector.detect("javascript:alert(1)"))
        assertEquals(QrType.UNKNOWN, QrTypeDetector.detect("intent://evil.example/#Intent;end"))
        assertEquals(QrType.UNKNOWN, QrTypeDetector.detect("file:///sdcard/payload.html"))
    }

    @Test
    fun `EMV payment payload remains payment QR`() {
        assertEquals(QrType.PAYMENT_QR, QrTypeDetector.detect("00020101021229370016PH.PAYMENT.TEST6304ABCD"))
    }

    @Test
    fun `ordinary text remains unsupported`() {
        assertEquals(QrType.UNKNOWN, QrTypeDetector.detect("Meet me at 5 PM"))
    }

    // ─── 2026-09-27: links inside other QR text, and payment detection ────────

    @Test
    fun `a link inside poster text is found and scanned`() {
        val qr = "Scan para sa libreng load: https://gcash-promo.xyz/claim"
        assertEquals(QrType.URL, QrTypeDetector.detect(qr))
        assertEquals("https://gcash-promo.xyz/claim", QrTypeDetector.linkIn(qr))
    }

    @Test
    fun `a link inside a contact card is found and scanned`() {
        val qr = "BEGIN:VCARD\nVERSION:3.0\nFN:Juan\nURL:https://bdo-verify.top\nEND:VCARD"
        assertEquals(QrType.URL, QrTypeDetector.detect(qr))
        assertEquals("https://bdo-verify.top", QrTypeDetector.linkIn(qr))
    }

    @Test
    fun `a whole-URL QR is scanned as itself`() {
        assertEquals("https://example.com/login", QrTypeDetector.linkIn("https://example.com/login"))
    }

    @Test
    fun `wifi and app-link QR codes are not payment QR codes`() {
        assertEquals(
            QrType.UNKNOWN,
            QrTypeDetector.detect("WIFI:S:HomeNetwork;T:WPA;P:correcthorsebatterystaple123456789;H:false;;")
        )
        assertEquals(
            QrType.UNKNOWN,
            QrTypeDetector.detect("intent://pay#Intent;scheme=gcash;package=com.globe.gcash.android;end")
        )
    }

    @Test
    fun `a long code that is not EMV is not a payment QR`() {
        assertEquals(QrType.UNKNOWN, QrTypeDetector.detect("A1B2C3D4E5F6G7H8I9J0K1L2M3N4O5P6Q7R8S9T0U1V2W3X4Y5Z6"))
        assertEquals(QrType.UNKNOWN, QrTypeDetector.detect("ORDER-6304-REF-99812-PICKUP-AT-COUNTER-3"))
    }
}
