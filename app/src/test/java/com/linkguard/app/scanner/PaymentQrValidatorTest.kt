package com.linkguard.app.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentQrValidatorTest {

    /** CRC16-CCITT (0xFFFF seed, 0x1021 poly) — mirrors the EMVCo spec the validator implements. */
    private fun crc16(data: String): String {
        var crc = 0xFFFF
        for (ch in data.toByteArray(Charsets.UTF_8)) {
            crc = crc xor ((ch.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc.toString(16).uppercase().padStart(4, '0')
    }

    /** Builds a minimal valid EMV-style payload: format indicator, merchant name/city, CRC. */
    private fun validPayload(): String {
        val body = "000201" + "5910TEST STORE" + "6006MANILA" + "6304"
        return body + crc16(body)
    }

    @Test
    fun `valid payload passes and extracts merchant details`() {
        val result = PaymentQrValidator.validate(validPayload())
        assertTrue(result.message, result.isValid)
        assertEquals("TEST STORE", result.merchantName)
        assertEquals("MANILA", result.merchantCity)
    }

    @Test
    fun `payload with newlines still validates`() {
        val payload = validPayload()
        val result = PaymentQrValidator.validate(payload.substring(0, 10) + "\n" + payload.substring(10))
        assertTrue(result.message, result.isValid)
    }

    @Test
    fun `tampered payload fails crc check`() {
        val tampered = validPayload().replace("TEST STORE", "FAKE STORE")
        val result = PaymentQrValidator.validate(tampered)
        assertFalse(result.isValid)
        assertTrue(result.message.contains("CRC Mismatch"))
    }

    @Test
    fun `too short payload is rejected`() {
        val result = PaymentQrValidator.validate("000201")
        assertFalse(result.isValid)
        assertTrue(result.message.contains("too short"))
    }

    @Test
    fun `empty payload is rejected`() {
        assertFalse(PaymentQrValidator.validate("").isValid)
    }

    @Test
    fun `tlv length exceeding payload is rejected`() {
        // tag 59 claims 50 chars but only 10 remain
        val result = PaymentQrValidator.validate("000201" + "5950" + "ABCDEFGHIJ")
        assertFalse(result.isValid)
        assertEquals("Invalid TLV format", result.message)
    }

    @Test
    fun `non-numeric tlv length is rejected`() {
        val result = PaymentQrValidator.validate("000201" + "59XY" + "ABCDEFGHIJ")
        assertFalse(result.isValid)
        assertEquals("Invalid TLV format", result.message)
    }

    @Test
    fun `non-numeric tlv tag is rejected`() {
        val result = PaymentQrValidator.validate("000201" + "AB04WXYZ" + "63040000")
        assertFalse(result.isValid)
        assertEquals("Invalid TLV format", result.message)
    }

    @Test
    fun `unicode digits in tlv length are rejected`() {
        // Arabic-Indic digits pass Char.isDigit() but are invalid EMVCo
        val result = PaymentQrValidator.validate("000201" + "59٤٢" + "ABCDEFGHIJ")
        assertFalse(result.isValid)
        assertEquals("Invalid TLV format", result.message)
    }

    @Test
    fun `missing crc tag is rejected`() {
        val result = PaymentQrValidator.validate("000201" + "5910TEST STORE" + "6006MANILA")
        assertFalse(result.isValid)
        assertTrue(result.message.contains("CRC"))
    }

    @Test
    fun `missing payload format indicator is rejected`() {
        val body = "5910TEST STORE" + "6006MANILA" + "6304"
        val result = PaymentQrValidator.validate(body + crc16(body))
        assertFalse(result.isValid)
        assertTrue(result.message.contains("payload format"))
    }
}
