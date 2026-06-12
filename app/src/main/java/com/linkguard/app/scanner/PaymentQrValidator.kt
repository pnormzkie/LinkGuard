package com.linkguard.app.scanner

data class PaymentQrValidationResult(
    val isValid: Boolean,
    val message: String,
    val merchantName: String? = null,
    val merchantCity: String? = null
)

object PaymentQrValidator {

    fun validate(raw: String): PaymentQrValidationResult {
        // Alisin lang ang newlines at leading/trailing spaces.
        // HUWAG tanggalin ang spaces sa loob (e.g., "BPI MERCHANT") dahil masisira ang TLV length at CRC.
        val text = raw
            .replace("\n", "")
            .replace("\r", "")
            .trim()

        if (text.length < 20) {
            return PaymentQrValidationResult(
                isValid = false,
                message = "Payment QR is too short"
            )
        }

        val tags = parseTlv(text)
        if (tags.isEmpty()) {
            return PaymentQrValidationResult(
                isValid = false,
                message = "Invalid TLV format"
            )
        }

        // Tag 00: Payload Format Indicator (Dapat "01")
        val payloadFormat = tags["00"]
        if (payloadFormat.isNullOrBlank()) {
            return PaymentQrValidationResult(
                isValid = false,
                message = "Missing payload format indicator"
            )
        }

        // Tag 63: CRC (Checksum)
        val crcValue = tags["63"]
        if (crcValue.isNullOrBlank() || crcValue.length != 4) {
            return PaymentQrValidationResult(
                isValid = false,
                message = "Missing or invalid CRC"
            )
        }

        val crcIndex = text.lastIndexOf("6304")
        if (crcIndex == -1 || crcIndex + 8 > text.length) {
            return PaymentQrValidationResult(
                isValid = false,
                message = "CRC field positioning error"
            )
        }

        // Ang CRC ay kinocompute mula sa simula hanggang sa "6304" (kasama ang 6304)
        val payloadWithoutCrcValue = text.substring(0, crcIndex + 4)
        val computedCrc = computeCrc16Ccitt(payloadWithoutCrcValue)

        if (!computedCrc.equals(crcValue, ignoreCase = true)) {
            return PaymentQrValidationResult(
                isValid = false,
                message = "CRC Mismatch (Data might be tampered)"
            )
        }

        val merchantName = tags["59"]
        val merchantCity = tags["60"]

        return PaymentQrValidationResult(
            isValid = true,
            message = "Valid payment QR format",
            merchantName = merchantName,
            merchantCity = merchantCity
        )
    }

    private fun parseTlv(raw: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var index = 0

        try {
            while (index < raw.length) {
                if (index + 4 > raw.length) break

                val tag = raw.substring(index, index + 2)
                val lenStr = raw.substring(index + 2, index + 4)

                // EMVCo tags and lengths are ASCII digits only — Char.isDigit()
                // also accepts Unicode digits, which are invalid here.
                if (!tag.all { it in '0'..'9' }) return emptyMap()
                if (!lenStr.all { it in '0'..'9' }) return emptyMap()

                val length = lenStr.toInt()
                val valueStart = index + 4
                val valueEnd = valueStart + length

                if (valueEnd > raw.length) return emptyMap()

                val value = raw.substring(valueStart, valueEnd)
                result[tag] = value
                index = valueEnd

                if (tag == "63") break
            }
        } catch (_: Exception) {
            return emptyMap()
        }

        return result
    }

    private fun computeCrc16Ccitt(data: String): String {
        var crc = 0xFFFF
        val polynomial = 0x1021

        for (ch in data.toByteArray(Charsets.UTF_8)) {
            crc = crc xor ((ch.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) {
                    (crc shl 1) xor polynomial
                } else {
                    crc shl 1
                }
                crc = crc and 0xFFFF
            }
        }

        return crc.toString(16).uppercase().padStart(4, '0')
    }
}