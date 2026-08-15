package com.linkguard.app.util

/** Canonical brand identities used by content-based impersonation checks. */
object BrandRegistry {
    data class Brand(
        val name: String,
        val aliases: Set<String>,
        val officialDomains: Set<String>
    )

    val brands = listOf(
        Brand("Google", setOf("google", "gmail"), setOf("google.com", "google.com.ph")),
        Brand("Microsoft", setOf("microsoft", "office 365", "outlook"), setOf("microsoft.com", "live.com", "office.com")),
        Brand("Apple", setOf("apple", "icloud"), setOf("apple.com", "icloud.com")),
        Brand("PayPal", setOf("paypal"), setOf("paypal.com")),
        Brand("Amazon", setOf("amazon"), setOf("amazon.com")),
        Brand("Meta", setOf("facebook", "instagram"), setOf("facebook.com", "instagram.com")),
        Brand("Netflix", setOf("netflix"), setOf("netflix.com")),
        Brand("GCash", setOf("gcash"), setOf("gcash.com")),
        Brand("Maya", setOf("maya", "paymaya"), setOf("maya.ph", "paymaya.com")),
        Brand("BPI", setOf("bpi", "bank of the philippine islands"), setOf("bpi.com.ph")),
        Brand("BDO", setOf("bdo", "bdo unibank"), setOf("bdo.com.ph")),
        Brand("Metrobank", setOf("metrobank"), setOf("metrobank.com.ph")),
        Brand("UnionBank", setOf("unionbank"), setOf("unionbankph.com")),
        Brand("RCBC", setOf("rcbc"), setOf("rcbc.com")),
        Brand("OpenAI", setOf("openai", "chatgpt"), setOf("openai.com", "chatgpt.com"))
    )

    fun claimedBrand(text: String, pageDomain: String): Brand? = brands.firstOrNull { brand ->
        !brand.officialDomains.any { pageDomain == it || pageDomain.endsWith(".$it") } &&
            brand.aliases.any { alias ->
                Regex("(?<![a-z0-9])${Regex.escape(alias)}(?![a-z0-9])", RegexOption.IGNORE_CASE)
                    .containsMatchIn(text)
            }
    }
}
