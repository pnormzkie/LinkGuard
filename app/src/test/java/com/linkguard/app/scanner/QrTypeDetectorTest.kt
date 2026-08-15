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
}
