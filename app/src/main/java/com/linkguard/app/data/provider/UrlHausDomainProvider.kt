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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Malware-distribution signal via abuse.ch URLhaus (host lookup). URLhaus tracks live malware
 * payload/distribution hosts and is often fresher than the aggregated VirusTotal feeds, so it
 * complements the existing providers for zero-hour malware sites.
 *
 * Queries `POST /v1/host/` with `host=<domain>` and the free Auth-Key header. A listed host with
 * an ONLINE malware URL is treated as CRITICAL only when its path and query match the scanned
 * URL. Activity on a different path and offline history are retained only as WEAK evidence because
 * host-level records can refer to another shared-host tenant or outlive a later ownership change.
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
                    "ok" -> signalFor(json, input, domain)?.let { listOf(it) } ?: emptyList()
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

    private fun signalFor(json: JsonObject, scannedUrl: String, domain: String): ScanSignal? {
        val urls = json.get("urls")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (urls.size() == 0) return null

        val onlineRecords = urls.filter { el ->
            el.isJsonObject && el.asJsonObject.get("url_status")?.asString.equals("online", true)
        }
        val onlineUrls = onlineRecords.mapNotNull { it.asJsonObject.get("url")?.asString }
        val exactActiveMatch = onlineUrls.any { sameUrlTarget(scannedUrl, it) }

        return if (exactActiveMatch) ScanSignal(
            ruleId = "URLHAUS_ACTIVE_MALWARE",
            title = "Listed on URLhaus malware blocklist",
            description = "abuse.ch URLhaus tracks this exact path as an ACTIVE malware distribution URL.",
            strength = SignalStrength.CRITICAL,
            source = SignalSource.EXTERNAL_REPUTATION,
            score = 100,
            matchedValue = scannedUrl
        ) else if (onlineRecords.isNotEmpty()) ScanSignal(
            ruleId = "URLHAUS_SHARED_HOST_ACTIVITY",
            title = "Active URLhaus record elsewhere on this host",
            description = "abuse.ch URLhaus tracks a different active malware path on this host. Shared hosting can affect unrelated sites, so this is supporting evidence only.",
            strength = SignalStrength.WEAK,
            source = SignalSource.EXTERNAL_REPUTATION,
            score = 15,
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

    private fun sameUrlTarget(scannedUrl: String, listedUrl: String): Boolean {
        val scanned = scannedUrl.toHttpUrlOrNull() ?: return false
        val listed = listedUrl.toHttpUrlOrNull() ?: return false
        return scanned.host.equals(listed.host, ignoreCase = true) &&
            scanned.encodedPath == listed.encodedPath &&
            scanned.encodedQuery == listed.encodedQuery
    }
}
