package com.linkguard.app.data.provider

import android.util.Log
import com.linkguard.app.BuildConfig
import com.linkguard.app.domain.model.ScanSignal
import com.linkguard.app.domain.model.SignalSource
import com.linkguard.app.domain.model.SignalStrength
import com.linkguard.app.domain.scanner.CredentialFormInspector
import com.linkguard.app.util.AppConfig
import com.linkguard.app.util.DomainExtractor
import com.linkguard.app.util.KnownDomains
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches a destination page's HTML (read-only, bounded) and detects a credential form.
 *
 * Safety / privacy invariants:
 *  - never fetches a trusted/official host or a private/loopback host (anti-SSRF);
 *  - GET with auto-redirect disabled (the URL is already resolved), a short timeout, and a
 *    capped body read ([AppConfig.CONTENT_MAX_BYTES]); the HTML is parsed in memory and
 *    discarded — nothing about page content is persisted, and body text is never logged;
 *  - fail-soft: any error / non-HTML response yields an empty list (never breaks the scan).
 *
 * Detection is static-HTML only (no JavaScript execution): a `<input type="password">` is the
 * core signal; a password form whose `action` posts to a *different* registrable domain (and
 * not a trusted identity provider) is the stronger credential-exfiltration case.
 */
class HttpCredentialFormInspector(
    private val client: OkHttpClient
) : CredentialFormInspector {

    override suspend fun inspect(finalUrl: String): List<ScanSignal> = withContext(Dispatchers.IO) {
        val httpUrl = finalUrl.toHttpUrlOrNull() ?: return@withContext emptyList()
        val pageDomain = DomainExtractor.extract(finalUrl) ?: return@withContext emptyList()
        // Trusted majors obviously host login forms — never fetch or flag them.
        if (KnownDomains.isTrusted(pageDomain)) return@withContext emptyList()
        // Anti-SSRF: never contact private/loopback/link-local hosts.
        if (HttpRedirectResolver.isPrivateOrLocalHost(httpUrl.host)) return@withContext emptyList()

        try {
            val request = Request.Builder()
                .url(httpUrl)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                // Only parse HTML; skip JSON/binary/etc. (also avoids reading large media bodies).
                val contentType = response.header("Content-Type").orEmpty()
                if (!contentType.contains("html", ignoreCase = true)) return@withContext emptyList()

                // Read at most CONTENT_MAX_BYTES into memory; the rest of the body is ignored.
                val html = response.peekBody(AppConfig.CONTENT_MAX_BYTES).string()
                signalsFor(html, finalUrl, pageDomain)
            }
        } catch (e: CancellationException) {
            throw e // never swallow real coroutine cancellation
        } catch (e: Exception) {
            // Amplifier must never break the scan — log without body content and move on.
            Log.w(TAG, "content inspect failed${if (BuildConfig.DEBUG) " for $pageDomain" else ""}: ${e.message}")
            emptyList()
        }
    }

    private fun signalsFor(html: String, finalUrl: String, pageDomain: String): List<ScanSignal> {
        if (!PASSWORD_INPUT.containsMatchIn(html)) return emptyList()

        return if (postsCredentialsCrossDomain(html, finalUrl, pageDomain)) {
            listOf(
                ScanSignal(
                    ruleId = "CREDENTIAL_FORM_EXFIL",
                    title = "Login form sends your password to another site",
                    description = "This page asks for a password and submits it to a different " +
                        "domain — a classic credential-theft pattern.",
                    strength = SignalStrength.STRONG,
                    source = SignalSource.LOCAL_HEURISTIC,
                    score = 50,
                    matchedValue = finalUrl
                )
            )
        } else {
            listOf(
                ScanSignal(
                    ruleId = "CREDENTIAL_FORM_UNTRUSTED",
                    title = "Login form on an unverified site",
                    description = "This page asks for a password but is not a recognized, trusted site.",
                    strength = SignalStrength.MEDIUM,
                    source = SignalSource.LOCAL_HEURISTIC,
                    score = 25,
                    matchedValue = finalUrl
                )
            )
        }
    }

    /**
     * True when a `<form action=...>` targets a registrable domain different from the page's
     * own — and not a trusted identity provider (so legitimate OAuth posts don't misfire).
     * Relative actions resolve back to the page host (same domain → not cross-domain).
     */
    private fun postsCredentialsCrossDomain(html: String, finalUrl: String, pageDomain: String): Boolean {
        val pageRegistrable = registrableDomain(pageDomain)
        val base = finalUrl.toHttpUrlOrNull() ?: return false
        for (match in FORM_ACTION.findAll(html)) {
            val action = match.groupValues[1].trim()
            if (action.isEmpty()) continue
            val resolved = base.resolve(action) ?: continue
            val actionDomain = resolved.host
            if (KnownDomains.isTrusted(actionDomain)) continue // legit IdP / known host
            if (registrableDomain(actionDomain) != pageRegistrable) return true
        }
        return false
    }

    private fun registrableDomain(domain: String): String {
        val parts = domain.removePrefix("www.").split(".").filter { it.isNotEmpty() }
        if (parts.size <= 2) return parts.joinToString(".")
        val lastTwo = parts.takeLast(2).joinToString(".")
        return if (lastTwo in SECOND_LEVEL_TLDS) parts.takeLast(3).joinToString(".") else lastTwo
    }

    companion object {
        private const val TAG = "CredentialForm"
        private const val USER_AGENT = "LinkGuard-SafetyCheck/1.0"

        private val PASSWORD_INPUT =
            Regex("""<input\b[^>]*\btype\s*=\s*["']?password\b""", RegexOption.IGNORE_CASE)
        private val FORM_ACTION =
            Regex("""<form\b[^>]*\baction\s*=\s*["']([^"'>]+)["']""", RegexOption.IGNORE_CASE)

        private val SECOND_LEVEL_TLDS = setOf(
            "com.ph", "net.ph", "org.ph", "gov.ph",
            "co.uk", "org.uk", "gov.uk",
            "com.au", "com.sg", "com.my"
        )
    }
}
