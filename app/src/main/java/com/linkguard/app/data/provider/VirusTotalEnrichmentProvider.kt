package com.linkguard.app.data.provider

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.linkguard.app.domain.model.ScanSignal
import com.linkguard.app.domain.model.SignalSource
import com.linkguard.app.domain.model.SignalStrength
import com.linkguard.app.BuildConfig
import com.linkguard.app.domain.scanner.SignalProvider
import com.linkguard.app.util.KnownDomains
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

            client.executeCancellable(request).use { response ->
                val responseBody = response.body?.string().orEmpty()
                // 404 = URL not yet in VirusTotal's database: a legitimate "no report" result.
                if (response.code == 404) return@withContext emptyList()
                // Other non-2xx (401 bad key, 429 quota, 5xx) mean the check did not run.
                // Fail loud so the orchestrator doesn't read an outage as "checked clean".
                if (!response.isSuccessful) {
                    throw IOException("VirusTotal HTTP ${response.code}")
                }
                if (responseBody.trim().isEmpty()) return@withContext emptyList()

                val attributes = gson.fromJson(responseBody, JsonObject::class.java)
                    ?.getAsJsonObject("data")
                    ?.getAsJsonObject("attributes")
                    ?: return@withContext emptyList()

                val stats = attributes.getAsJsonObject("last_analysis_stats")
                    ?: return@withContext emptyList()

                val malicious = stats.get("malicious")?.asInt ?: 0
                if (malicious == 0) return@withContext emptyList()

                // Names of the specific engines that flagged it (VT lists these per result).
                // Shown to the user so "N vendors" isn't an opaque number.
                val vendorNames = attributes.getAsJsonObject("last_analysis_results")
                    ?.entrySet()
                    ?.mapNotNull { (engine, value) ->
                        val category = runCatching { value.asJsonObject.get("category")?.asString }.getOrNull()
                        if (category == "malicious") engine else null
                    }
                    .orEmpty()

                // One or two engines on a trusted site's bare address is routine noise (VirusTotal
                // lists two for https://google.com), and at 25 it alone forced SUSPICIOUS.
                val calibratedTrustedRoot = malicious < 3 && isTrustedSiteRoot(input)

                // Security-First Scoring:
                // - 1-2 flags: 25 points (Minimum Suspicious threshold); 10 on a trusted site root
                // - 3-4 flags: 45 points (Stronger warning)
                // - 5+ flags: 65 points (DANGER threshold)
                val finalScore = when {
                    malicious >= 5 -> 65
                    malicious >= 3 -> 45
                    calibratedTrustedRoot -> TRUSTED_ROOT_LOW_DETECTION_SCORE
                    else -> 25
                }

                // A single detection is common, so the count must read as "1 Vendor", never
                // "1 Vendors" — the title is shown verbatim in the flag list.
                val vendorNoun = if (malicious == 1) "Vendor" else "Vendors"

                listOf(
                    ScanSignal(
                        ruleId = "VT_MALICIOUS",
                        title = "$malicious $vendorNoun Flagged",
                        description = if (calibratedTrustedRoot) {
                            "$malicious ${vendorNoun.lowercase()} flagged this trusted site on VirusTotal. Potential false positive."
                        } else {
                            "$malicious ${vendorNoun.lowercase()} flagged this URL as malicious on VirusTotal."
                        },
                        strength = when {
                            malicious >= 5 -> SignalStrength.CRITICAL
                            malicious >= 3 -> SignalStrength.STRONG
                            else -> SignalStrength.WEAK
                        },
                        source = SignalSource.ENRICHMENT,
                        score = finalScore,
                        matchedValue = input,
                        metadata = buildMap {
                            put("malicious_count", malicious.toString())
                            if (vendorNames.isNotEmpty()) {
                                put("vendor_names", vendorNames.joinToString("||"))
                            }
                        }
                    )
                )
            }
        } catch (e: Exception) {
            // Rethrow so the orchestrator can tell "check failed" apart from "no threat found".
            Log.w("VirusTotal", "Lookup failed${if (BuildConfig.DEBUG) " for $input" else ""}: ${e.message}")
            throw e
        }
    }

    /**
     * True only for the bare address of a trusted host: path "/" and no query. A report there
     * describes the operator's own site. Any path or query on a trusted host may be an
     * attacker-authored page (sites.google.com, github.com), so it keeps full weight — host
     * trust never waives path evidence. This relies on [KnownDomains.TRUSTED_DOMAINS] holding
     * no platform that gives users their own subdomain (github.io, blogspot.com).
     */
    private fun isTrustedSiteRoot(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.encodedPath == "/" && parsed.encodedQuery == null &&
            KnownDomains.isTrusted(parsed.host)
    }

    private companion object {
        const val TRUSTED_ROOT_LOW_DETECTION_SCORE = 10
    }
}
