package com.linkguard.app.scanner

import com.linkguard.app.data.ThreatLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeuristicScannerTest {

    private fun flagsOf(url: String, message: String? = null): List<String> =
        HeuristicScanner.scanWithContext(url, message).flags

    @Test
    fun `official domain is safe with no flags`() {
        val result = HeuristicScanner.scanWithContext("https://www.bpi.com.ph", null)
        assertEquals(ThreatLevel.SAFE, result.threatLevel)
        assertEquals(0, result.riskScore)
        assertTrue(result.flags.isEmpty())
    }

    @Test
    fun `suspicious tld is flagged`() {
        val flags = flagsOf("https://abcd.xyz")
        assertTrue(flags.any { it.contains("Suspicious domain extension") })
    }

    @Test
    fun `url shortener is flagged`() {
        val flags = flagsOf("https://bit.ly/abc")
        assertTrue(flags.any { it.contains("URL shortener") })
    }

    @Test
    fun `brand spoofing over http is danger`() {
        val result = HeuristicScanner.scanWithContext("http://gcash-verify.com", null)
        assertTrue(result.flags.any { it.contains("brand spoofing", ignoreCase = true) })
        assertEquals(ThreatLevel.DANGER, result.threatLevel)
    }

    @Test
    fun `homograph domain is flagged as lookalike`() {
        val flags = flagsOf("https://g0ogle-login.com")
        assertTrue(flags.any { it.contains("Lookalike domain detected") })
    }

    @Test
    fun `ip address url is flagged`() {
        val flags = flagsOf("http://192.168.0.1/login")
        assertTrue(flags.any { it.contains("IP address") })
    }

    @Test
    fun `dangerous file extension is flagged`() {
        val flags = flagsOf("https://files.abcd.com/setup.apk")
        assertTrue(flags.any { it.contains("Dangerous file type") })
    }

    @Test
    fun `typosquatted brand is flagged`() {
        val flags = flagsOf("https://paypa1.com")
        assertTrue(flags.any { it.contains("typosquatting") })
    }

    @Test
    fun `english smishing message is flagged`() {
        val flags = flagsOf(
            "https://example.com",
            "Congratulations! Your reward is pending, click link to claim"
        )
        assertTrue(flags.any { it.contains("smishing", ignoreCase = true) })
    }

    @Test
    fun `tagalog smishing message is flagged`() {
        val flags = flagsOf(
            "https://example.com",
            "Nanalo ka ng P10,000! I-claim mo na dito"
        )
        assertTrue(flags.any { it.contains("smishing", ignoreCase = true) })
    }

    @Test
    fun `tagalog blocked account message is flagged`() {
        val flags = flagsOf(
            "https://example.com",
            "Ang iyong account ay na-block. I-verify agad para ma-restore."
        )
        assertTrue(flags.any { it.contains("smishing", ignoreCase = true) })
    }

    @Test
    fun `benign tagalog message is not flagged`() {
        val flags = flagsOf("https://example.com", "Kumusta, kita tayo bukas sa bahay")
        assertFalse(flags.any { it.contains("smishing", ignoreCase = true) })
    }

    @Test
    fun `smishing scores at most once even with multiple matches`() {
        val result = HeuristicScanner.scanWithContext(
            "https://example.com",
            "Nanalo ka! Congratulations, your reward is pending. Click link to claim."
        )
        assertEquals(1, result.flags.count { it.contains("smishing", ignoreCase = true) })
    }

    @Test
    fun `punycode domain is flagged as internationalized`() {
        // xn-- label (a famous Cyrillic "apple" homoglyph encoding)
        val result = HeuristicScanner.scanWithContext("https://xn--80ak6aa92e.com", null)
        assertTrue(result.flags.any { it.contains("punycode", ignoreCase = true) })
        assertTrue(result.riskScore >= 25)
    }

    @Test
    fun `mixed-script homoglyph domain is flagged`() {
        // "pаypal.com" — the 'а' is Cyrillic U+0430, not Latin 'a'
        val result = HeuristicScanner.scanWithContext("https://pаypal.com", null)
        assertTrue(result.flags.any { it.contains("homoglyph", ignoreCase = true) })
        // Correct Unicode host extraction also lets the typosquatting rule corroborate the
        // mixed-script signal, so a direct PayPal lookalike crosses the danger threshold.
        assertEquals(ThreatLevel.DANGER, result.threatLevel)
    }

    @Test
    fun `plain ascii domain is not flagged as homoglyph or punycode`() {
        val flags = flagsOf("https://example.com")
        assertFalse(flags.any { it.contains("homoglyph", ignoreCase = true) })
        assertFalse(flags.any { it.contains("punycode", ignoreCase = true) })
    }

    @Test
    fun `courier release-fee smishing is flagged`() {
        val flags = flagsOf(
            "https://example.com",
            "Your parcel is on hold. Please pay the release fee to receive it."
        )
        assertTrue(flags.any { it.contains("smishing", ignoreCase = true) })
    }

    @Test
    fun `work-from-home recruitment smishing is flagged`() {
        val flags = flagsOf(
            "https://example.com",
            "Work from home and earn daily payout! Apply now."
        )
        assertTrue(flags.any { it.contains("smishing", ignoreCase = true) })
    }

    @Test
    fun `tech-support scare smishing is flagged`() {
        val flags = flagsOf(
            "https://example.com",
            "Warning: your device has been hacked. Call support now."
        )
        assertTrue(flags.any { it.contains("smishing", ignoreCase = true) })
    }

    @Test
    fun `benign delivery message is not flagged as smishing`() {
        val flags = flagsOf(
            "https://example.com",
            "I will send the package tomorrow, thanks!"
        )
        assertFalse(flags.any { it.contains("smishing", ignoreCase = true) })
    }

    @Test
    fun `score thresholds map to threat levels`() {
        // ~85 points (http 30 + keyword 15 + spoof 40)
        val danger = HeuristicScanner.scanWithContext("http://gcash-verify.com", null)
        assertEquals(ThreatLevel.DANGER, danger.threatLevel)
        assertTrue(danger.riskScore >= 60)

        // 25 points (suspicious TLD only)
        val suspicious = HeuristicScanner.scanWithContext("https://abcd.xyz", null)
        assertEquals(ThreatLevel.SUSPICIOUS, suspicious.threatLevel)
        assertTrue(suspicious.riskScore in 25..59)

        // clean url
        val safe = HeuristicScanner.scanWithContext("https://example.com", null)
        assertEquals(ThreatLevel.SAFE, safe.threatLevel)
    }

    // ─── False-positive regressions: boundary-aware matching (2026-06-26) ───────────

    @Test
    fun `battle_net is not flagged as a dangerous bat file`() {
        // ".bat" must not match inside "account.battle.net" — the reported false positive.
        val result = HeuristicScanner.scanWithContext(
            "https://account.battle.net/login/logout?ref=https%3A%2F%2Fus.account.battle.net",
            null
        )
        assertFalse(result.flags.any { it.contains("Dangerous file type") })
        assertNotEquals(ThreatLevel.DANGER, result.threatLevel)
    }

    @Test
    fun `whitelisted battle_net auth flow is safe with no flags`() {
        val result = HeuristicScanner.scanWithContext(
            "https://account.battle.net/login/logout?ref=https%3A%2F%2Fus.account.battle.net",
            null
        )
        assertEquals(ThreatLevel.SAFE, result.threatLevel)
        assertTrue(result.flags.isEmpty())
    }

    @Test
    fun `chatgpt advanced account security page is safe`() {
        val result = HeuristicScanner.scanWithContext(
            "https://chatgpt.com/advanced-account-security?originator=android_app_homepage_beacon",
            null
        )
        assertEquals(ThreatLevel.SAFE, result.threatLevel)
        assertEquals(0, result.riskScore)
        assertTrue(result.flags.isEmpty())
    }

    @Test
    fun `indeed oauth authorization url is safe`() {
        val result = HeuristicScanner.scanWithContext(
            "https://secure.indeed.com/oauth/v2/authorize?response_type=code&client_id=" +
                "b945f2c710f278485e264bd779d3eec629d0f193c&redirect_uri=" +
                "https%3A%2F%2Fexample.com%2Foauth%2Fcallback&scope=openid%20email%20profile",
            null
        )
        assertEquals(ThreatLevel.SAFE, result.threatLevel)
        assertEquals(0, result.riskScore)
        assertTrue(result.flags.isEmpty())
    }

    @Test
    fun `legit subdomain of an official domain is treated as safe`() {
        val result = HeuristicScanner.scanWithContext("https://accounts.google.com/signin", null)
        assertEquals(ThreatLevel.SAFE, result.threatLevel)
        assertTrue(result.flags.isEmpty())
    }

    @Test
    fun `suffix-spoof of an official domain is not treated as official`() {
        // "secure-google.com" ends with "google.com" but is NOT a subdomain of it.
        val flags = flagsOf("https://secure-google.com/login")
        assertTrue(flags.any { it.contains("brand spoofing", ignoreCase = true) })
    }

    @Test
    fun `a real bat file download is still flagged`() {
        val flags = flagsOf("https://files.example.com/setup.bat")
        assertTrue(flags.any { it.contains("Dangerous file type") })
    }

    @Test
    fun `groups_google_com is not flagged as brand spoofing or subdomain abuse`() {
        val flags = flagsOf("https://groups.google.com/g/some-group")
        assertFalse(flags.any { it.contains("brand spoofing", ignoreCase = true) })
        assertFalse(flags.any { it.contains("subdomain", ignoreCase = true) })
    }

    @Test
    fun `startups_com is not flagged as UPS brand spoofing`() {
        val flags = flagsOf("https://startups.com")
        assertFalse(flags.any { it.contains("brand spoofing", ignoreCase = true) })
    }

    @Test
    fun `pineapple_com is not flagged as APPLE brand spoofing`() {
        val flags = flagsOf("https://pineapple.com")
        assertFalse(flags.any { it.contains("brand spoofing", ignoreCase = true) })
    }

    @Test
    fun `brand glued to a prefix affix is still flagged as spoofing`() {
        val flags = flagsOf("https://verifypaypal.com")
        assertTrue(flags.any { it.contains("brand spoofing", ignoreCase = true) })
    }

    @Test
    fun `brand glued to a suffix affix is still flagged as spoofing`() {
        val flags = flagsOf("https://paypalsupport.com")
        assertTrue(flags.any { it.contains("brand spoofing", ignoreCase = true) })
    }

    @Test
    fun `brand with a separator is still flagged as spoofing`() {
        val flags = flagsOf("https://secure-amazon.com")
        assertTrue(flags.any { it.contains("brand spoofing", ignoreCase = true) })
    }

    @Test
    fun `dictionary word containing a brand substring is not flagged`() {
        // "appleton" contains "apple" but the remainder "ton" is not a phishing affix.
        val flags = flagsOf("https://appleton.com")
        assertFalse(flags.any { it.contains("brand spoofing", ignoreCase = true) })
    }

    @Test
    fun `php file extension does not trigger a phishing keyword`() {
        val flags = flagsOf("https://blog.example.com/index.php")
        assertFalse(flags.any { it.contains("php") })
    }

    @Test
    fun `keyword inside a longer word is not flagged`() {
        // "last" must not match inside "elasticsearch".
        val flags = flagsOf("https://search.example.com/elasticsearch/query")
        assertFalse(flags.any { it.contains("Phishing keyword") && it.contains("last") })
    }

    @Test
    fun `suspicious tld inside a redirect parameter is not flagged`() {
        // ".ru" appears only in the embedded ref URL, not the real host (example.com).
        val flags = flagsOf("https://example.com/go?url=https://news.example.ru/article")
        assertFalse(flags.any { it.contains("Suspicious domain extension") })
    }

    @Test
    fun `remote access app instruction is flagged as smishing`() {
        val flags = flagsOf("https://example.com", "For account support, install AnyDesk now.")
        assertTrue(flags.any { it.contains("smishing", ignoreCase = true) })
    }

    @Test
    fun `task commission scam is flagged`() {
        val flags = flagsOf("https://example.com", "Like and review products to earn commission per task.")
        assertTrue(flags.any { it.contains("smishing", ignoreCase = true) })
    }

    @Test
    fun `digital arrest payment demand is flagged`() {
        val flags = flagsOf("https://example.com", "May warrant at kaso ka. Bayad agad ng legal fee.")
        assertTrue(flags.any { it.contains("smishing", ignoreCase = true) })
    }

    @Test
    fun `benign remote support discussion is not flagged`() {
        val flags = flagsOf("https://example.com", "Our IT policy explains remote desktop security.")
        assertFalse(flags.any { it.contains("smishing", ignoreCase = true) })
    }
}
