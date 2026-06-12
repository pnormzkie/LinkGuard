package com.linkguard.app.data.provider

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.linkguard.app.domain.model.ScanSignal
import com.linkguard.app.domain.model.SignalSource
import com.linkguard.app.domain.model.SignalStrength
import com.linkguard.app.BuildConfig
import com.linkguard.app.domain.scanner.SignalProvider
import com.linkguard.app.util.DomainExtractor
import com.linkguard.app.util.KnownDomains
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Base64

class VirusTotalEnrichmentProvider(
    private val client: OkHttpClient,
    private val apiKey: String
) : SignalProvider {

    private val gson = Gson()

    override suspend fun fetchSignals(input: String): List<ScanSignal> = withContext(Dispatchers.IO) {
        if (apiKey.trim().isEmpty()) return@withContext emptyList()

        val domain = DomainExtractor.extract(input)
        if (KnownDomains.isTrusted(domain)) {
            return@withContext emptyList()
        }

        try {
            // VirusTotal needs URL-safe base64 without padding. java.util.Base64 (API 26+,
            // = minSdk) matches android.util.Base64 URL_SAFE|NO_WRAP|NO_PADDING output and,
            // unlike the Android stub, is testable under JVM unit tests.
            val encodedUrl = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(input.toByteArray())

            val request = Request.Builder()
                .url("https://www.virustotal.com/api/v3/urls/$encodedUrl")
                .addHeader("x-apikey", apiKey)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                // 404 = URL not yet in VirusTotal's database: a legitimate "no report" result.
                if (response.code == 404) return@withContext emptyList()
                // Other non-2xx (401 bad key, 429 quota, 5xx) mean the check did not run.
                // Fail loud so the orchestrator doesn't read an outage as "checked clean".
                if (!response.isSuccessful) {
                    throw IOException("VirusTotal HTTP ${response.code}")
                }
                if (responseBody.trim().isEmpty()) return@withContext emptyList()

                val stats = gson.fromJson(responseBody, JsonObject::class.java)
                    ?.getAsJsonObject("data")
                    ?.getAsJsonObject("attributes")
                    ?.getAsJsonObject("last_analysis_stats")
                    ?: return@withContext emptyList()

                val malicious = stats.get("malicious")?.asInt ?: 0
                if (malicious == 0) return@withContext emptyList()

                // Security-First Scoring: 
                // - 1-2 flags: 25 points (Minimum Suspicious threshold)
                // - 3-4 flags: 45 points (Stronger warning)
                // - 5+ flags: 65 points (DANGER threshold)
                val finalScore = when {
                    malicious >= 5 -> 65
                    malicious >= 3 -> 45
                    else -> 25
                }

                listOf(
                    ScanSignal(
                        ruleId = "VT_MALICIOUS",
                        title = "$malicious Vendors Flagged",
                        description = "$malicious vendors flagged this URL as malicious on VirusTotal.",
                        strength = when {
                            malicious >= 5 -> SignalStrength.CRITICAL
                            malicious >= 3 -> SignalStrength.STRONG
                            else -> SignalStrength.WEAK
                        },
                        source = SignalSource.ENRICHMENT,
                        score = finalScore,
                        matchedValue = input,
                        metadata = mapOf("malicious_count" to malicious.toString())
                    )
                )
            }
        } catch (e: Exception) {
            // Rethrow so the orchestrator can tell "check failed" apart from "no threat found".
            Log.w("VirusTotal", "Lookup failed${if (BuildConfig.DEBUG) " for $input" else ""}: ${e.message}")
            throw e
        }
    }
}
