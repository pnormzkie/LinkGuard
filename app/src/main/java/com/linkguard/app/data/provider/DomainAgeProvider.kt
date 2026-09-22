package com.linkguard.app.data.provider

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.linkguard.app.BuildConfig
import com.linkguard.app.domain.model.ScanSignal
import com.linkguard.app.domain.model.SignalSource
import com.linkguard.app.domain.model.SignalStrength
import com.linkguard.app.domain.scanner.SignalProvider
import com.linkguard.app.util.DomainExtractor
import com.linkguard.app.util.KnownDomains
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Domain-age signal via RDAP (RFC 9083). A freshly registered domain is a strong phishing
 * indicator: attackers spin up throwaway look-alike domains, while legitimate brands have
 * long-lived registrations.
 *
 * Queries the rdap.org bootstrap (`/domain/{registrable}`) and reads the `registration`
 * event date. Emits a DOMAIN_SIGNAL (reusing the existing source so the scoring engine's
 * reason cascade is unchanged): < 7 days → STRONG, 7–30 days → WEAK, older → no signal.
 *
 * Failure semantics match the other providers: a 404 (no RDAP record / unsupported TLD) is a
 * legitimate "no signal"; any other non-2xx or a network error is rethrown so the orchestrator
 * can tell "check failed" apart from "checked clean" and not cache an unvetted verdict.
 */
class DomainAgeProvider(
    private val client: OkHttpClient,
    // Injected so age math is testable without real time passing.
    private val now: () -> Long = System::currentTimeMillis,
) : SignalProvider {

    private val gson = Gson()

    override suspend fun fetchSignals(input: String): List<ScanSignal> = withContext(Dispatchers.IO) {
        val domain = DomainExtractor.extract(input) ?: return@withContext emptyList()
        // Trusted majors are long-established — skip the request (and avoid penalizing a
        // subdomain of a trusted root that RDAP wouldn't resolve anyway).
        if (KnownDomains.isTrusted(domain)) return@withContext emptyList()

        val registrable = registrableDomain(domain)
        if (registrable.isBlank()) return@withContext emptyList()

        try {
            val request = Request.Builder()
                .url("https://rdap.org/domain/$registrable")
                .addHeader("Accept", "application/rdap+json")
                // RDAP is unauthenticated public infrastructure, and the registry behind
                // rdap.org's redirect rejects OkHttp's default agent with a 403 — which, since
                // any non-404 fails loud, silently removed the domain-age check for every user
                // on every .com lookup. Measured 2026-09-22: "okhttp/4.12.0" and an absent
                // header both return 403; a named agent returns 200.
                .addHeader("User-Agent", USER_AGENT)
                .get()
                .build()

            client.executeCancellable(request).use { response ->
                // 404 = registry has no RDAP record for this name (unsupported TLD, or not
                // registered). That's a legitimate absence of signal, not a failed check.
                if (response.code == 404) return@withContext emptyList()
                if (!response.isSuccessful) {
                    throw IOException("RDAP HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@withContext emptyList()

                val registeredAt = parseRegistrationEpochMillis(body)
                    ?: return@withContext emptyList()

                val ageDays = (now() - registeredAt) / DAY_MS
                signalForAge(ageDays, registrable)?.let { listOf(it) } ?: emptyList()
            }
        } catch (e: Exception) {
            // Rethrow so the orchestrator distinguishes "check failed" from "no signal".
            Log.w("DomainAge", "RDAP lookup failed${if (BuildConfig.DEBUG) " for $registrable" else ""}: ${e.message}")
            throw e
        }
    }

    private fun parseRegistrationEpochMillis(body: String): Long? {
        // Unparseable JSON throws (JsonSyntaxException) and is rethrown by the caller's catch
        // — a garbage 200 must fail loud, not silently become "no signal". A well-formed body
        // without a registration event legitimately returns null (no signal).
        val json = gson.fromJson(body, JsonObject::class.java) ?: return null
        val events = json.getAsJsonArray("events") ?: return null
        for (element in events) {
            val obj = runCatching { element.asJsonObject }.getOrNull() ?: continue
            val action = obj.get("eventAction")?.asString ?: continue
            if (action.equals("registration", ignoreCase = true)) {
                val dateStr = obj.get("eventDate")?.asString ?: continue
                return parseIso8601(dateStr)
            }
        }
        return null
    }

    private fun parseIso8601(value: String): Long? =
        runCatching { Instant.parse(value).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .getOrNull()

    private fun signalForAge(ageDays: Long, domain: String): ScanSignal? = when {
        ageDays < 0 -> null // future date / clock skew — ignore
        ageDays < YOUNG_DAYS -> ScanSignal(
            ruleId = "DOMAIN_AGE_VERY_NEW",
            title = "Newly registered domain",
            description = "This domain was registered very recently ($ageDays day(s) ago). " +
                "Brand-new domains are a common phishing signal.",
            strength = SignalStrength.STRONG,
            source = SignalSource.DOMAIN_SIGNAL,
            score = 45,
            matchedValue = domain
        )
        ageDays < RECENT_DAYS -> ScanSignal(
            ruleId = "DOMAIN_AGE_RECENT",
            title = "Recently registered domain",
            description = "This domain was registered about $ageDays days ago.",
            strength = SignalStrength.WEAK,
            source = SignalSource.DOMAIN_SIGNAL,
            score = 20,
            matchedValue = domain
        )
        else -> null
    }

    /**
     * Reduces a host to the registrable domain RDAP expects (eTLD+1). Uses last-two-labels
     * with a small list of common second-level TLDs (e.g. com.ph, co.uk) kept as three
     * labels — mirrors the existing PH-focused handling in HeuristicScanner rather than
     * pulling in a full Public Suffix List.
     */
    private fun registrableDomain(domain: String): String {
        val parts = domain.removePrefix("www.").split(".").filter { it.isNotEmpty() }
        if (parts.size <= 2) return parts.joinToString(".")
        val lastTwo = parts.takeLast(2).joinToString(".")
        return if (lastTwo in SECOND_LEVEL_TLDS) parts.takeLast(3).joinToString(".") else lastTwo
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        const val YOUNG_DAYS = 7L
        const val RECENT_DAYS = 30L

        /** Any named agent is accepted; the default OkHttp one is not. */
        const val USER_AGENT = "LinkGuard/1.0"

        private val SECOND_LEVEL_TLDS = setOf(
            "com.ph", "net.ph", "org.ph", "gov.ph",
            "co.uk", "org.uk", "gov.uk",
            "com.au", "com.sg", "com.my"
        )
    }
}
