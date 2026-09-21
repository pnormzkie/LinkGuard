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
    fun `elevated risk tld is weak context but not suspicious by itself`() {
        val result = HeuristicScanner.scanWithContext("https://abcd.xyz", null)
        assertTrue(result.flags.any { it.contains("elevated abuse risk") })
        assertEquals(ThreatLevel.SAFE, result.threatLevel)
        assertTrue(result.riskScore < 25)
    }

    @Test
    fun `mainstream tlds are not treated as suspicious`() {
        listOf("portfolio.io", "example.info", "example.shop", "example.store").forEach { host ->
            val result = HeuristicScanner.scanWithContext("https://$host", null)
            assertFalse("$host should not receive a TLD warning", result.flags.any {
                it.contains("extension", ignoreCase = true) || it.contains("TLD", ignoreCase = true)
            })
        }
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
    fun `dangerous file extension in a query value is flagged`() {
        val flags = flagsOf("https://files.abcd.com/download?file=setup.apk")
        assertTrue(flags.any { it.contains("Dangerous file type") })
    }

    @Test
    fun `file-like public domain is not treated as a download`() {
        val flags = flagsOf("https://example.zip/")
        assertFalse(flags.any { it.contains("Dangerous file type") })
    }

    @Test
    fun `file extension in a fragment is not treated as a download`() {
        val flags = flagsOf("https://example.com/docs#setup.apk")
        assertFalse(flags.any { it.contains("Dangerous file type") })
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
    fun `scam-pattern message text alone does not flag a trusted domain`() {
        val trusted = listOf(
            "https://www.google.com" to "Your account will be suspended if you do not act.",
            "https://mail.google.com/mail/u/0" to "Please verify within 24 hours to keep your mailbox."
        )
        trusted.forEach { (url, text) ->
            val result = HeuristicScanner.scanWithContext(url, text)
            assertEquals(url, ThreatLevel.SAFE, result.threatLevel)
            assertFalse(url, result.flags.any { it.contains("smishing", ignoreCase = true) })
        }
    }

    @Test
    fun `same scam-pattern text still flags an untrusted domain`() {
        val result = HeuristicScanner.scanWithContext(
            "https://example.com",
            "Your account will be suspended if you do not act."
        )
        assertEquals(ThreatLevel.SUSPICIOUS, result.threatLevel)
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
        assertTrue(result.riskScore < 25)
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
    fun `mixed script analysis is preserved across IDN encodings`() {
        val unicodeHost = "pаypal.com" // Existing fixture: Cyrillic U+0430.
        val asciiHost = java.net.IDN.toASCII(unicodeHost)
        val unicode = HeuristicScanner.findingsWithContext("https://$unicodeHost", null)
        val ascii = HeuristicScanner.findingsWithContext("https://$asciiHost", null)

        assertNotEquals(unicodeHost, asciiHost)
        val unicodeFinding = unicode.single { it.ruleId == "MIXED_SCRIPT_DOMAIN" }
        assertEquals(unicodeFinding, ascii.singleOrNull { it.ruleId == "MIXED_SCRIPT_DOMAIN" })
    }

    @Test
    fun `lookalike evidence and verdict survive IDN encoding`() {
        val unicodeHost = "pаypal.com"
        val asciiHost = java.net.IDN.toASCII(unicodeHost)
        val unicode = HeuristicScanner.findingsWithContext("https://$unicodeHost", null)
        val ascii = HeuristicScanner.findingsWithContext("https://$asciiHost", null)
        val unicodeTypos = unicode.filter { it.ruleId.startsWith("TYPOSQUAT_") }

        assertTrue(unicodeTypos.isNotEmpty())
        assertEquals(unicodeTypos, ascii.filter { it.ruleId.startsWith("TYPOSQUAT_") })
        assertEquals(ThreatLevel.DANGER, HeuristicScanner.scan("https://$asciiHost").threatLevel)
    }

    @Test
    fun `legitimate single script IDNs remain safe in both encodings`() {
        listOf("bücher.de", "mañana.com", "пример.рф").forEach { host ->
            listOf(host, java.net.IDN.toASCII(host)).forEach { spelling ->
                val url = "https://$spelling"
                val findings = HeuristicScanner.findingsWithContext(url, null)
                assertFalse(spelling, findings.any {
                    it.ruleId == "MIXED_SCRIPT_DOMAIN" ||
                        it.ruleId.startsWith("TYPOSQUAT_") ||
                        it.ruleId == "PUNYCODE_BRAND_LOOKALIKE"
                })
                assertEquals(spelling, ThreatLevel.SAFE, HeuristicScanner.scan(url).threatLevel)
                assertEquals(url, HeuristicScanner.scan(url).url)
            }
        }
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

        // An elevated-risk TLD is context, not a verdict by itself.
        val tldOnly = HeuristicScanner.scanWithContext("https://abcd.xyz", null)
        assertEquals(ThreatLevel.SAFE, tldOnly.threatLevel)
        assertTrue(tldOnly.riskScore < 25)

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
    fun `trusted github apk remains visible but does not become suspicious by itself`() {
        val result = HeuristicScanner.scanWithContext(
            "https://github.com/pnormzkie/LinkGuard/releases/download/v1.28/LinkGuard-v1.28.apk",
            null
        )
        assertTrue(result.flags.any { it.contains("Dangerous file type") })
        assertEquals(ThreatLevel.SAFE, result.threatLevel)
        assertTrue(result.riskScore < 25)
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
    fun `multiple marketing keywords count as one weak evidence family`() {
        val result = HeuristicScanner.scanWithContext(
            "https://campaign-page.net/promo/gift/reward/bonus/prize",
            null
        )

        assertEquals(1, result.flags.count { it.contains("Phishing keyword") })
        assertEquals(ThreatLevel.SAFE, result.threatLevel)
        assertEquals(10, result.riskScore)
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

    // ─── F2: digit-substitution must match a brand token, not any substring ──────

    @Test
    fun `digit substitution inside an ordinary word is not a brand lookalike`() {
        // "ups"/"apple" are brands AND substrings of everyday words. Rule 6 normalizes
        // digits to letters, so these must not become STRONG lookalike evidence.
        listOf(
            "https://gr0ups.com",      // -> "groups"
            "https://st4rtups.com",    // -> "startups"
            "https://5ignup5.com",     // -> "signups"
            "https://pine4pple.com"    // -> "pineapple"
        ).forEach { url ->
            val result = HeuristicScanner.scanWithContext(url, null)
            assertFalse(url, result.flags.any { it.contains("numbers as letters") })
            assertEquals(url, ThreatLevel.SAFE, result.threatLevel)
        }
    }

    @Test
    fun `digit substitution on a real brand token is still a lookalike`() {
        listOf("https://g00gle.com", "https://4mazon.com").forEach { url ->
            val flags = flagsOf(url)
            assertTrue(url, flags.any { it.contains("numbers as letters") })
        }
        // "1" maps to "i", not "l", so "paypa1" never normalizes to "paypal". The
        // Levenshtein typosquat rule is what catches this one — assert it still does.
        val flags = flagsOf("https://paypa1.com")
        assertTrue(flags.any { it.contains("Lookalike domain", ignoreCase = true) })
    }

    // ─── F3: the query is attacker-controlled even without a path separator ──────

    @Test
    fun `phishing keyword in a query with no path separator is detected`() {
        // Browsers accept "https://host?a=b"; dropping the query let one missing slash
        // hide the keyword evidence entirely.
        val withoutSlash = HeuristicScanner.scanWithContext(
            "https://evil-site.xyz?action=verify&do=login", null
        )
        val withSlash = HeuristicScanner.scanWithContext(
            "https://evil-site.xyz/?action=verify&do=login", null
        )
        assertTrue(withoutSlash.flags.any { it.contains("Phishing keyword in URL path") })
        assertEquals(withSlash.threatLevel, withoutSlash.threatLevel)
        assertEquals(withSlash.riskScore, withoutSlash.riskScore)
    }

    // ─── F4: one rule id must contribute its score exactly once ──────────────────

    @Test
    fun `a rule that matches several brands scores once and stays monotonic`() {
        val twoBrands = HeuristicScanner.scanWithContext("https://g00gle-4pple.com", null)
        val oneBrand = HeuristicScanner.scanWithContext("https://g00gle.com", null)
        assertEquals(
            1,
            twoBrands.flags.count { it.contains("numbers as letters") }
        )
        // Score must match the evidence actually shown: a single 45-point finding.
        assertEquals(45, twoBrands.riskScore)
        // ...and a strictly smaller evidence set must not outscore a larger one.
        assertTrue(oneBrand.riskScore >= twoBrands.riskScore)
    }

    // ─── F1: user-authored pages on trusted hosts are not vouched for by the host ─

    @Test
    fun `brand phishing slug on a user-content host is flagged`() {
        val scam = "Your BPI account will be suspended. Verify within 24 hours."
        listOf(
            "https://sites.google.com/view/bpi-verify-account/login",
            "https://docs.google.com/forms/d/e/1FA/viewform?usp=gcash-verify",
            "https://github.com/bpi-verify-account/login"
        ).forEach { url ->
            val result = HeuristicScanner.scanWithContext(url, scam)
            assertNotEquals(url, ThreatLevel.SAFE, result.threatLevel)
            assertTrue(url, result.flags.any { it.contains("impersonat", ignoreCase = true) })
        }
    }

    @Test
    fun `ordinary pages on user-content hosts stay safe`() {
        listOf(
            "https://docs.google.com/document/d/abc123/edit",
            "https://drive.google.com/file/d/abc123/view",
            "https://github.com/microsoft/vscode/issues/update",
            "https://github.com/paypal/paypal-checkout-sdk",
            "https://github.com/apple/swift"
        ).forEach { url ->
            val result = HeuristicScanner.scanWithContext(url, null)
            assertEquals(url, ThreatLevel.SAFE, result.threatLevel)
            assertFalse(url, result.flags.any { it.contains("impersonat", ignoreCase = true) })
        }
    }

    @Test
    fun `trusted hosts that are not user-content keep their clean verdict`() {
        // Regression guard: these must NOT pick up path-keyword evidence.
        listOf(
            "https://chatgpt.com/advanced-account-security?originator=android_app_homepage_beacon",
            "https://accounts.google.com/signin"
        ).forEach { url ->
            val result = HeuristicScanner.scanWithContext(url, null)
            assertEquals(url, ThreatLevel.SAFE, result.threatLevel)
            assertEquals(url, 0, result.riskScore)
            assertTrue(url, result.flags.isEmpty())
        }
    }
}
