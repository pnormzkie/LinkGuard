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
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Malware-distribution signal via abuse.ch URLhaus (host lookup). URLhaus tracks live malware
 * payload/distribution hosts and is often fresher than the aggregated VirusTotal feeds, so it
 * complements the existing providers for zero-hour malware sites.
 *
 * Queries `POST /v1/host/` with `host=<domain>` and the free Auth-Key header. A listed host with
 * an ONLINE malware URL is treated as CRITICAL (active distribution). A host whose tracked URLs
 * are all offline is retained only as WEAK historical evidence because host-level records can
 * outlive the malicious path or a later ownership change. `no_results` is a clean absence.
 *
 * Failure semantics match the other providers: a blank key is a no-op (feature disabled); any
 * transport error, auth error, or non-2xx is rethrown so the orchestrator can tell "check failed"
 * apart from "checked clean" and not cache an unvetted verdict.
 */
class UrlHausDomainProvider(
    private val client: OkHttpClient,
    private val authKey: String
) : SignalProvider {

    private val gson = Gson()

    override suspend fun fetchSignals(input: String): List<ScanSignal> = withContext(Dispatchers.IO) {
        if (authKey.isBlank()) return@withContext emptyList()

        val domain = DomainExtractor.extract(input) ?: return@withContext emptyList()
        // Trusted majors are not malware-distribution hosts — skip the lookup (and the quota).
        if (KnownDomains.isTrusted(domain)) return@withContext emptyList()

        try {
            val body = FormBody.Builder().add("host", domain).build()
            val request = Request.Builder()
                .url("https://urlhaus-api.abuse.ch/v1/host/")
                .addHeader("Auth-Key", authKey)
                .post(body)
                .build()

            client.executeCancellable(request).use { response ->
                if (!response.isSuccessful) {
                    throw IOException("URLhaus HTTP ${response.code}")
                }
                val raw = response.body?.string().orEmpty()
                if (raw.isBlank()) return@withContext emptyList()

                val json = gson.fromJson(raw, JsonObject::class.java) ?: return@withContext emptyList()
                val status = json.get("query_status")?.asString.orEmpty()
                when (status) {
                    // Listed — inspect the tracked URLs to grade severity.
                    "ok" -> signalFor(json, domain)?.let { listOf(it) } ?: emptyList()
                    // Host is simply not on the blocklist — a real "clean", not a failure.
                    "no_results", "invalid_host" -> emptyList()
                    // Auth / request problems must fail loud, not look clean.
                    else -> throw IOException("URLhaus query_status: $status")
                }
            }
        } catch (e: Exception) {
            // Rethrow so the orchestrator distinguishes "check failed" from "no signal".
            Log.w("URLhaus", "Lookup failed${if (BuildConfig.DEBUG) " for $domain" else ""}: ${e.message}")
            throw e
        }
    }

    private fun signalFor(json: JsonObject, domain: String): ScanSignal? {
        val urls = json.get("urls")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (urls.size() == 0) return null

        val hasOnline = urls.any { el ->
            el.isJsonObject && el.asJsonObject.get("url_status")?.asString.equals("online", ignoreCase = true)
        }

        return if (hasOnline) ScanSignal(
            ruleId = "URLHAUS_ACTIVE_MALWARE",
            title = "Listed on URLhaus malware blocklist",
            description = "abuse.ch URLhaus tracks an ACTIVE malware distribution URL on this host.",
            strength = SignalStrength.CRITICAL,
            source = SignalSource.EXTERNAL_REPUTATION,
            score = 100,
            matchedValue = domain
        ) else ScanSignal(
            ruleId = "URLHAUS_HISTORICAL_MALWARE",
            title = "Historical URLhaus malware record",
            description = "abuse.ch URLhaus previously recorded malware on this host, but every tracked URL is now offline.",
            strength = SignalStrength.WEAK,
            source = SignalSource.EXTERNAL_REPUTATION,
            score = 15,
            matchedValue = domain
        )
    }
}
