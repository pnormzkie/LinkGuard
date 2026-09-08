package com.linkguard.app.data.provider

import android.util.Log
import com.linkguard.app.BuildConfig
import com.linkguard.app.domain.model.ScanSignal
import com.linkguard.app.domain.model.SignalSource
import com.linkguard.app.domain.model.SignalStrength
import com.linkguard.app.domain.scanner.CredentialFormInspector
import com.linkguard.app.util.AppConfig
import com.linkguard.app.util.BrandRegistry
import com.linkguard.app.util.DomainExtractor
import com.linkguard.app.util.KnownDomains
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.util.concurrent.TimeUnit

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
 * Detection is static-HTML only (no JavaScript execution). Credential collection is the primary
 * signal; page branding, urgent account language, suspicious embeds/scripts, and executable
 * downloads are corroborating signals.
 */
class HttpCredentialFormInspector(
    private val client: OkHttpClient
) : CredentialFormInspector {

    private val safeClient = client.newBuilder()
        .dns((client.dns as? HostSafetyValidator) ?: HostSafetyValidator(client.dns))
        .callTimeout(AppConfig.CONTENT_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    override fun isEligible(finalUrl: String): Boolean {
        val httpUrl = finalUrl.toHttpUrlOrNull() ?: return false
        val pageDomain = DomainExtractor.extract(finalUrl) ?: return false
        return !KnownDomains.isTrusted(pageDomain) &&
            !HttpRedirectResolver.isPrivateOrLocalHost(httpUrl.host)
    }

    override suspend fun inspect(finalUrl: String): List<ScanSignal> = withContext(Dispatchers.IO) {
        if (!isEligible(finalUrl)) return@withContext emptyList()
        val httpUrl = finalUrl.toHttpUrlOrNull() ?: return@withContext emptyList()
        val pageDomain = DomainExtractor.extract(finalUrl) ?: return@withContext emptyList()

        try {
            val request = Request.Builder()
                .url(httpUrl)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            execute(request).use { response ->
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

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = safeClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response) { response.close() }
                } else {
                    response.close()
                }
            }
        })
    }

    private fun signalsFor(html: String, finalUrl: String, pageDomain: String): List<ScanSignal> {
        val document = Jsoup.parse(html, finalUrl)
        val normalized = html.lowercase()
        val visibleText = document.body().text().lowercase()
        val inputs = document.select("input, textarea, select")
        val hasPassword = document.select("input[type=password]").isNotEmpty()
        val hasOtpOrPayment = inputs.any { elementMatchesSensitiveTerms(it, OTP_PAYMENT_TERMS) }
        val hasIdentityRequest = inputs.any { elementMatchesSensitiveTerms(it, IDENTITY_TERMS) }
        val hasHiddenSensitive = document.select("input[type=hidden]")
            .any { elementMatchesSensitiveTerms(it, HIDDEN_SENSITIVE_TERMS) }
        val hasMultiStepLogin = !hasPassword && document.select("form").isNotEmpty() &&
            inputs.any { it.attr("type").equals("email", true) || elementMatchesSensitiveTerms(it, LOGIN_ID_TERMS) } &&
            LOGIN_LANGUAGE.containsMatchIn(visibleText)
        val hasSensitiveRequest = hasPassword || hasOtpOrPayment || hasIdentityRequest ||
            hasHiddenSensitive || hasMultiStepLogin
        val postsToUntrustedDomain = hasPassword &&
            postsCredentialsCrossDomain(document, pageDomain)
        val postsToTrustedIdentityProvider = hasPassword &&
            postsCredentialsToTrustedDomain(document)
        val hasUrgentLanguage = URGENT_ACCOUNT_LANGUAGE.containsMatchIn(visibleText)
        val hasExecutableDownload = hasExecutableDownload(document)
        val clientSideRedirectTarget = crossDomainClientRedirect(document, html, finalUrl, pageDomain)
        if (!hasSensitiveRequest && !hasExecutableDownload && !hasClickFix(visibleText) &&
            clientSideRedirectTarget == null) return emptyList()

        return buildList {
            if (clientSideRedirectTarget != null) add(
                ScanSignal(
                    ruleId = "CLIENT_SIDE_CROSS_DOMAIN_REDIRECT",
                    title = "Page hides a redirect to another site",
                    description = "The page uses a static browser redirect to send you to a different untrusted domain.",
                    strength = SignalStrength.MEDIUM,
                    source = SignalSource.LOCAL_HEURISTIC,
                    score = 25,
                    matchedValue = clientSideRedirectTarget,
                    metadata = evidence("page_evasion")
                )
            )
            if (hasPassword) add(if (postsToUntrustedDomain) {
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
            } else {
                ScanSignal(
                    ruleId = "CREDENTIAL_FORM_UNTRUSTED",
                    title = "Login form on an unverified site",
                    description = "This page asks for a password on a site LinkGuard does not recognize. This is supporting evidence unless other risky behavior is present.",
                    strength = SignalStrength.WEAK,
                    source = SignalSource.LOCAL_HEURISTIC,
                    score = 10,
                    matchedValue = finalUrl
                )
            })
            if (!hasPassword && hasOtpOrPayment) add(
                ScanSignal(
                    ruleId = "SENSITIVE_FORM_UNTRUSTED",
                    title = "OTP or payment details requested on an unverified site",
                    description = "This page requests sensitive verification or payment information " +
                        "outside a recognized trusted site.",
                    strength = SignalStrength.MEDIUM,
                    source = SignalSource.LOCAL_HEURISTIC,
                    score = 25,
                    matchedValue = finalUrl
                )
            )
            if (!hasPassword && hasIdentityRequest) add(
                ScanSignal(
                    ruleId = "IDENTITY_FORM_UNTRUSTED",
                    title = "Identity details requested on an unverified site",
                    description = "This page requests identity or recovery information outside a recognized trusted site.",
                    strength = SignalStrength.MEDIUM,
                    source = SignalSource.LOCAL_HEURISTIC,
                    score = 25,
                    matchedValue = finalUrl,
                    metadata = evidence("sensitive_data")
                )
            )
            if (hasMultiStepLogin) add(
                ScanSignal(
                    ruleId = "MULTI_STEP_LOGIN_FORM",
                    title = "Multi-step login starts on an unverified site",
                    description = "The page begins an account sign-in flow before revealing the password step. This is supporting evidence unless other risky behavior is present.",
                    strength = SignalStrength.WEAK,
                    source = SignalSource.LOCAL_HEURISTIC,
                    score = 10,
                    matchedValue = finalUrl,
                    metadata = evidence("sensitive_data")
                )
            )
            if (hasHiddenSensitive) add(
                ScanSignal(
                    ruleId = "HIDDEN_SENSITIVE_INPUT",
                    title = "Hidden field requests sensitive account data",
                    description = "The page contains a hidden input associated with sensitive account data.",
                    strength = SignalStrength.MEDIUM,
                    source = SignalSource.LOCAL_HEURISTIC,
                    score = 20,
                    matchedValue = finalUrl
                )
            )
            if (hasSensitiveRequest && hasUrgentLanguage) add(
                ScanSignal(
                    ruleId = "URGENT_ACCOUNT_LANGUAGE",
                    title = "Urgent account warning used with a sensitive form",
                    description = "The page combines account-threat or verification language with a request for sensitive information.",
                    strength = SignalStrength.MEDIUM,
                    source = SignalSource.LOCAL_HEURISTIC,
                    score = 20,
                    matchedValue = finalUrl
                )
            )
            if (hasSensitiveRequest && !postsToUntrustedDomain && !postsToTrustedIdentityProvider &&
                BrandRegistry.claimedBrand(visibleText, pageDomain) != null) add(
                ScanSignal(
                    ruleId = "PAGE_BRAND_IMPERSONATION",
                    title = "Brand identity claimed on an unrelated domain",
                    description = "The page uses a known service name while collecting sensitive information from a different domain.",
                    strength = SignalStrength.STRONG,
                    source = SignalSource.LOCAL_HEURISTIC,
                    score = 40,
                    matchedValue = finalUrl
                )
            )
            if (hasSensitiveRequest && SUSPICIOUS_PAGE_CODE.containsMatchIn(normalized)) add(
                ScanSignal(
                    ruleId = "SUSPICIOUS_PAGE_CODE",
                    title = "Obfuscated page code protects a sensitive form",
                    description = "The page combines a sensitive form with script patterns commonly used to hide phishing behavior.",
                    strength = SignalStrength.MEDIUM,
                    source = SignalSource.LOCAL_HEURISTIC,
                    score = 20,
                    matchedValue = finalUrl
                )
            )
            if (hasSensitiveRequest && document.select("script[src^=http://], script[src^=https://]").isNotEmpty()) add(
                ScanSignal(
                    ruleId = "EXTERNAL_SCRIPT_WITH_SENSITIVE_FORM",
                    title = "Sensitive form loads code from another site",
                    description = "The page requests sensitive information while loading executable code from an external domain.",
                    strength = SignalStrength.WEAK,
                    source = SignalSource.LOCAL_HEURISTIC,
                    score = 10,
                    matchedValue = finalUrl
                )
            )
            if (hasSensitiveRequest && document.select("iframe[src^=http://], iframe[src^=https://]").isNotEmpty()) add(
                ScanSignal(
                    ruleId = "SUSPICIOUS_CROSS_DOMAIN_FRAME",
                    title = "Sensitive form embeds another site",
                    description = "The page embeds a cross-domain frame while requesting sensitive information.",
                    strength = SignalStrength.MEDIUM,
                    source = SignalSource.LOCAL_HEURISTIC,
                    score = 20,
                    matchedValue = finalUrl
                )
            )
            if (hasSensitiveRequest && hasSuspiciousOverlay(document)) add(
                ScanSignal(
                    ruleId = "FULLSCREEN_SENSITIVE_OVERLAY",
                    title = "Full-screen overlay requests sensitive information",
                    description = "The page uses a fixed full-screen layer around a sensitive form, a common browser-impersonation technique.",
                    strength = SignalStrength.MEDIUM,
                    source = SignalSource.LOCAL_HEURISTIC,
                    score = 20,
                    matchedValue = finalUrl,
                    metadata = evidence("page_evasion")
                )
            )
            if (hasSensitiveRequest && SVG_OR_DATA_PAYLOAD.containsMatchIn(normalized)) add(
                ScanSignal(
                    ruleId = "ENCODED_PAGE_PAYLOAD",
                    title = "Sensitive page contains an encoded SVG or data payload",
                    description = "The page combines sensitive-data collection with an embedded encoded payload.",
                    strength = SignalStrength.MEDIUM,
                    source = SignalSource.LOCAL_HEURISTIC,
                    score = 20,
                    matchedValue = finalUrl,
                    metadata = evidence("page_evasion")
                )
            )
            if (hasSensitiveRequest && clientSideRedirectTarget == null &&
                CLIPBOARD_OR_DELAYED_REDIRECT.containsMatchIn(normalized)) add(
                ScanSignal(
                    ruleId = "SCRIPTED_USER_REDIRECTION",
                    title = "Sensitive page manipulates clipboard or delayed navigation",
                    description = "The page combines sensitive-data collection with scripted clipboard or delayed redirect behavior.",
                    strength = SignalStrength.MEDIUM,
                    source = SignalSource.LOCAL_HEURISTIC,
                    score = 20,
                    matchedValue = finalUrl,
                    metadata = evidence("page_evasion")
                )
            )
            if (hasClickFix(visibleText)) add(
                ScanSignal(
                    ruleId = "CLICKFIX_INSTRUCTIONS",
                    title = "Page instructs you to run a copied command",
                    description = "The page uses a fake verification or repair flow that asks you to paste and run a command.",
                    strength = SignalStrength.STRONG,
                    source = SignalSource.LOCAL_HEURISTIC,
                    score = 40,
                    matchedValue = finalUrl,
                    metadata = evidence("malware_delivery")
                )
            )
            if (hasExecutableDownload && hasUrgentLanguage) add(
                ScanSignal(
                    ruleId = "FORCED_EXECUTABLE_DOWNLOAD",
                    title = "Urgent page offers an executable download",
                    description = "The page pairs urgent account language with a potentially executable download.",
                    strength = SignalStrength.STRONG,
                    source = SignalSource.LOCAL_HEURISTIC,
                    score = 40,
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
    private fun postsCredentialsCrossDomain(document: Document, pageDomain: String): Boolean {
        val pageRegistrable = registrableDomain(pageDomain)
        for (form in document.select("form[action]")) {
            val actionDomain = form.absUrl("action").toHttpUrlOrNull()?.host ?: continue
            if (KnownDomains.isTrusted(actionDomain)) continue // legit IdP / known host
            if (registrableDomain(actionDomain) != pageRegistrable) return true
        }
        return false
    }

    private fun postsCredentialsToTrustedDomain(document: Document): Boolean =
        document.select("form[action]").any { form ->
            form.absUrl("action").toHttpUrlOrNull()?.host?.let(KnownDomains::isTrusted) == true
        }

    private fun elementMatchesSensitiveTerms(element: Element, terms: Set<String>): Boolean {
        val attributes = listOf("name", "id", "autocomplete", "placeholder", "aria-label")
            .joinToString(" ") { element.attr(it) }
            .lowercase()
        return terms.any { term ->
            Regex("(?<![a-z0-9])${Regex.escape(term)}(?![a-z0-9])").containsMatchIn(attributes)
        }
    }

    private fun hasExecutableDownload(document: Document): Boolean =
        document.select("a[href], source[src], iframe[src]").any { element ->
            val value = element.attr(if (element.hasAttr("href")) "href" else "src")
                .substringBefore('?').substringBefore('#').lowercase()
            DANGEROUS_DOWNLOAD_EXTENSIONS.any(value::endsWith)
        }

    private fun hasSuspiciousOverlay(document: Document): Boolean =
        document.select("form, div, section").any { element ->
            val style = element.attr("style").lowercase().replace(" ", "")
            style.contains("position:fixed") &&
                (style.contains("inset:0") ||
                    (style.contains("top:0") && style.contains("left:0") &&
                        style.contains("width:100%") && style.contains("height:100%")))
        }

    private fun hasClickFix(visibleText: String): Boolean =
        CLICKFIX_LANGUAGE.containsMatchIn(visibleText)

    /**
     * Extracts only literal meta-refresh or simple JavaScript navigation targets. Nothing is
     * executed or followed. Same-site and recognized trusted destinations are ignored to avoid
     * treating ordinary refreshes and identity-provider handoffs as malicious behavior.
     */
    private fun crossDomainClientRedirect(
        document: Document,
        rawHtml: String,
        finalUrl: String,
        pageDomain: String
    ): String? {
        val candidates = buildList {
            document.select("meta[http-equiv][content]")
                .asSequence()
                .filter { it.attr("http-equiv").equals("refresh", ignoreCase = true) }
                .mapNotNullTo(this) { metaRefreshTarget(it.attr("content")) }
            SIMPLE_SCRIPT_REDIRECT.findAll(rawHtml)
                .mapNotNullTo(this) { it.groups[1]?.value ?: it.groups[2]?.value }
        }
        val base = finalUrl.toHttpUrlOrNull() ?: return null
        val pageRegistrable = registrableDomain(pageDomain)
        return candidates.firstNotNullOfOrNull { rawTarget ->
            val target = base.resolve(rawTarget.trim()) ?: return@firstNotNullOfOrNull null
            val targetDomain = DomainExtractor.extract(target.toString())
                ?: return@firstNotNullOfOrNull null
            target.toString().takeIf {
                !KnownDomains.isTrusted(targetDomain) &&
                    registrableDomain(targetDomain) != pageRegistrable
            }
        }
    }

    private fun metaRefreshTarget(content: String): String? {
        val directive = content.substringAfter(';', missingDelimiterValue = "").trim()
        if (!directive.startsWith("url", ignoreCase = true)) return null
        return directive.substringAfter('=', missingDelimiterValue = "")
            .trim().trim('\'', '"').takeIf { it.isNotBlank() }
    }

    private fun evidence(group: String): Map<String, String> = mapOf("evidence_group" to group)

    private fun registrableDomain(domain: String): String {
        val parts = domain.removePrefix("www.").split(".").filter { it.isNotEmpty() }
        if (parts.size <= 2) return parts.joinToString(".")
        val lastTwo = parts.takeLast(2).joinToString(".")
        return if (lastTwo in SECOND_LEVEL_TLDS) parts.takeLast(3).joinToString(".") else lastTwo
    }

    companion object {
        private const val TAG = "CredentialForm"
        private const val USER_AGENT = "LinkGuard-SafetyCheck/1.0"

        private val OTP_PAYMENT_TERMS = setOf(
            "otp", "one-time", "one time", "verification code", "security code",
            "card number", "cardnumber", "cc-number", "cvv", "cvc", "expiry",
            "expiration", "credit card"
        )
        private val IDENTITY_TERMS = setOf(
            "passport", "national id", "government id", "driver license", "drivers license",
            "social security", "sss number", "birth date", "birthday", "recovery code"
        )
        private val HIDDEN_SENSITIVE_TERMS = setOf(
            "password", "passwd", "otp", "token", "cvv", "card", "security", "recovery"
        )
        private val LOGIN_ID_TERMS = setOf("email", "username", "user name", "account id", "login id")
        private val LOGIN_LANGUAGE =
            Regex("""(?:sign\s?in|log\s?in|continue\s+to\s+(?:your\s+)?account|verify\s+(?:your\s+)?identity)""", RegexOption.IGNORE_CASE)
        private val URGENT_ACCOUNT_LANGUAGE =
            Regex("""(?:account|profile|security|payment|wallet).{0,80}(?:suspend|lock|verify|confirm|expire|unauthori[sz]ed|compromis|urgent|immediately|within\s+(?:24|48)\s+hours)""", RegexOption.IGNORE_CASE)
        private val SUSPICIOUS_PAGE_CODE =
            Regex("""(?:eval\s*\(|atob\s*\(|fromCharCode\s*\(|document\.write\s*\(|unescape\s*\()""", RegexOption.IGNORE_CASE)
        private val SVG_OR_DATA_PAYLOAD =
            Regex("""(?:data:(?:text/html|image/svg\+xml)|<svg\b[^>]*(?:onload|href\s*=\s*["']?data:))""", RegexOption.IGNORE_CASE)
        private val CLIPBOARD_OR_DELAYED_REDIRECT =
            Regex("""(?:navigator\.clipboard|clipboardData|setTimeout\s*\([^)]*(?:location|window\.open)|location\.(?:href|replace|assign)\s*=)""", RegexOption.IGNORE_CASE)
        private val SIMPLE_SCRIPT_REDIRECT = Regex(
            """(?:window\.|document\.|top\.)?location(?:\.href)?\s*=\s*["']([^"']+)["']|(?:window\.|document\.|top\.)?location\.(?:replace|assign)\s*\(\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        private val CLICKFIX_LANGUAGE =
            Regex("""(?:press\s+(?:windows\s*\+\s*r|win\s*\+\s*r)|open\s+(?:powershell|terminal|command prompt)|paste\s+(?:the\s+)?command|run\s+(?:the\s+)?command|copy\s+(?:and\s+)?paste.{0,40}(?:verify|fix|captcha))""", RegexOption.IGNORE_CASE)
        private val DANGEROUS_DOWNLOAD_EXTENSIONS = setOf(
            ".apk", ".exe", ".msi", ".bat", ".cmd", ".scr", ".ps1", ".jar", ".zip"
        )

        private val SECOND_LEVEL_TLDS = setOf(
            "com.ph", "net.ph", "org.ph", "gov.ph",
            "co.uk", "org.uk", "gov.uk",
            "com.au", "com.sg", "com.my"
        )
    }
}
