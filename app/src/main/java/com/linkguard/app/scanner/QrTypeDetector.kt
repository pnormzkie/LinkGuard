package com.linkguard.app.scanner

enum class QrType {
    URL,
    PAYMENT_QR,
    UNKNOWN
}

object QrTypeDetector {

    fun detect(raw: String): QrType {
        val text = raw.trim()

        if (isUrl(text)) return QrType.URL
        if (isLikelyPaymentQr(text)) return QrType.PAYMENT_QR

        return QrType.UNKNOWN
    }

    private fun isUrl(text: String): Boolean {
        val lower = text.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    private fun isLikelyPaymentQr(text: String): Boolean {
        val value = text.trim()

        if (value.length < 20) return false

        // EMV / QR Ph usually starts with this
        if (value.startsWith("000201") || value.startsWith("000202")) return true

        // CRC tag presence (very common sa payment QR)
        if (value.contains("6304")) return true

        // fallback: long dense string without spaces (common sa QR payloads)
        if (!value.contains(" ") && value.length > 50) return true

        return false

   }
}