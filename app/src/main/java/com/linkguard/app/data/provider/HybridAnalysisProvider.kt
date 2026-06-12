package com.linkguard.app.data.provider

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
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
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest

class HybridAnalysisProvider(
    private val client: OkHttpClient,
    private val apiKey: String
) : SignalProvider {

    private val gson = Gson()

    companion object {
        private const val TAG = "ScanOrchestrator"
        private const val HA_BASE_URL = "https://hybrid-analysis.com/api/v2"
    }

    override suspend fun fetchSignals(input: String): List<ScanSignal> = withContext(Dispatchers.IO) {
        if (apiKey.trim().isEmpty()) return@withContext emptyList()

        val trimmedInput = input.trim()
        val domain = DomainExtractor.extract(trimmedInput)

        // Identify if the domain or its parent is trusted
        val isTrusted = KnownDomains.isTrusted(domain)
        val isTracker = KnownDomains.isTracker(domain)

        // Optimization: Skip Hybrid Analysis entirely for ALL trusted domains (including Google trackers).
        // This prevents timeouts and relies on NextDNS for faster, more reliable tracker flagging.
        if (isTrusted) {
            if (BuildConfig.DEBUG) Log.d(TAG, "HybridAnalysis: Skipping trusted domain $domain")
            return@withContext emptyList()
        }

        // Search original URL only (No more redundant fallback searches to avoid timeouts)
        val signals = performSearch(trimmedInput, isTrusted, isTracker)
        if (signals.isNotEmpty()) {
            if (BuildConfig.DEBUG) Log.i(TAG, "HybridAnalysis Match Found: $trimmedInput")
            return@withContext signals
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "HybridAnalysis: No reports found for $trimmedInput")
        emptyList()
    }

    private fun performSearch(term: String, isTrusted: Boolean, isTracker: Boolean): List<ScanSignal> {
        try {
            val isUrl = term.startsWith("http://", ignoreCase = true) ||
                    term.startsWith("https://", ignoreCase = true)

            val requestBuilder = Request.Builder()
                .addHeader("api-key", apiKey.trim())
                .addHeader("User-Agent", "Falcon/1.0")
                .addHeader("Accept", "application/json")

            val fieldName = if (isUrl) "url" else "domain"
            val body = FormBody.Builder()
                .add(fieldName, term)
                .build()

            requestBuilder
                .url("$HA_BASE_URL/search/terms")
                .post(body)

            val request = requestBuilder.build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()

                if (BuildConfig.DEBUG) Log.d(TAG, "HA Search ($term): HTTP ${response.code}")

                if (!response.isSuccessful) {
                    // 404 = URL unknown to the sandbox: a legitimate "no report" result.
                    if (response.code == 404) {
                        if (BuildConfig.DEBUG) Log.w(TAG, "HA Search ($term): 404 no report")
                        return emptyList()
                    }
                    // Other non-2xx (429 quota, 5xx server down) mean the check did not run.
                    // Fail loud so the orchestrator doesn't read an outage as "no threat found".
                    throw IOException("Hybrid Analysis HTTP ${response.code}")
                }
                if (responseBody.trim().isEmpty()) return emptyList()

                // A garbage payload means the check did not run; let the parse exception
                // propagate so it fails loud rather than reading as "no report found".
                val jsonElement = gson.fromJson(responseBody, JsonElement::class.java)

                val results = when {
                    jsonElement == null -> null
                    jsonElement.isJsonArray -> jsonElement.asJsonArray
                    jsonElement.isJsonObject -> extractResultsArray(jsonElement.asJsonObject)
                    else -> null
                }

                if (results == null || results.size() == 0) return emptyList()

                var bestMatch: JsonObject? = null
                var bestRiskValue = Int.MIN_VALUE

                for (item in results) {
                    if (!item.isJsonObject) continue

                    val obj = item.asJsonObject
                    val score = obj.getIntSafely("threat_score")
                    val verdict = obj.getStringSafely("verdict").lowercase()

                    val riskValue = when {
                        verdict == "malicious" -> score + 1000
                        verdict == "suspicious" -> score + 500
                        else -> score
                    }

                    if (riskValue > bestRiskValue) {
                        bestRiskValue = riskValue
                        bestMatch = obj
                    }
                }

                val chosen = bestMatch ?: return emptyList()

                val score = chosen.getIntSafely("threat_score")
                val rawVerdict = chosen.getStringSafely("verdict").let { if (it.trim().isEmpty()) "unknown" else it }
                val formattedVerdict = rawVerdict.lowercase().replaceFirstChar { it.uppercase() }

                if (BuildConfig.DEBUG) Log.d(TAG, "HA Best Match ($term): verdict=$formattedVerdict score=$score")

                if (score >= 50 || formattedVerdict.equals("Malicious", ignoreCase = true) || 
                    formattedVerdict.equals("Suspicious", ignoreCase = true)) {
                    
                    val adjustedScore = when {
                        isTrusted -> minOf(score / 4, 15)
                        isTracker -> minOf(score / 3, 21)
                        else -> score / 2
                    }
                    
                    val finalStrength = if (isTrusted || isTracker) SignalStrength.WEAK 
                                      else if (score >= 75) SignalStrength.CRITICAL 
                                      else SignalStrength.STRONG

                    return listOf(
                        ScanSignal(
                            ruleId = "HYBRID_ANALYSIS_THREAT",
                            title = if (isTracker) "Falcon Sandbox: Tracker ($formattedVerdict)" else "Falcon Sandbox: $formattedVerdict",
                            description = when {
                                isTrusted -> "Trusted domain with a sandbox report. Potential false positive. Score: $score/100."
                                isTracker -> "Domain associated with ad tracking or telemetry services. Score: $score/100."
                                else -> "Hybrid Analysis sandbox found suspicious behavior (Threat Score: $score/100)."
                            },
                            strength = finalStrength,
                            source = SignalSource.ENRICHMENT,
                            score = adjustedScore.coerceAtMost(50),
                            matchedValue = term
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Rethrow so the orchestrator can tell "check failed" apart from "no threat found".
            Log.w(TAG, "HA Search failed${if (BuildConfig.DEBUG) " for $term" else ""}: ${e.message}")
            throw e
        }

        return emptyList()
    }

    private fun extractResultsArray(obj: JsonObject): JsonArray? {
        return when {
            obj.has("results") && obj.get("results").isJsonArray -> obj.getAsJsonArray("results")
            obj.has("search_results") && obj.get("search_results").isJsonArray -> obj.getAsJsonArray("search_results")
            obj.has("data") && obj.get("data").isJsonArray -> obj.getAsJsonArray("data")
            obj.has("result") && obj.get("result").isJsonArray -> obj.getAsJsonArray("result")
            else -> null
        }
    }

    private fun JsonObject.getIntSafely(key: String): Int {
        return try {
            if (has(key) && !get(key).isJsonNull) get(key).asInt else 0
        } catch (_: Exception) {
            0
        }
    }

    private fun JsonObject.getStringSafely(key: String): String {
        return try {
            if (has(key) && !get(key).isJsonNull) get(key).asString else ""
        } catch (_: Exception) {
            ""
        }
    }

}
