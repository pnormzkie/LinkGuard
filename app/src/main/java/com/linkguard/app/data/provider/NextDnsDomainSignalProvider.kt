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

class NextDnsDomainSignalProvider(
    private val client: OkHttpClient
) : SignalProvider {

    private val gson = Gson()

    companion object {
        private const val PROFILE_ID = "2d1fa2"
    }

    override suspend fun fetchSignals(input: String): List<ScanSignal> = withContext(Dispatchers.IO) {
        val domain = DomainExtractor.extract(input) ?: return@withContext emptyList()
        val isKnownTracker = KnownDomains.isTracker(domain)

        try {
            // Using your specific NextDNS Profile ID: 2d1fa2 in the correct resolve format
            val request = Request.Builder()
                .url("https://dns.nextdns.io/resolve?profile=$PROFILE_ID&name=$domain&type=A")
                .addHeader("Accept", "application/dns-json")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()

                // Fail loud on a non-2xx resolve: an endpoint outage must not read as
                // "domain not blocked". (Known trackers still fall back below via the catch.)
                if (!response.isSuccessful) {
                    throw IOException("NextDNS HTTP ${response.code}")
                }

                val isBlockedByApi = if (responseBody.isNotBlank()) {
                    val json = gson.fromJson(responseBody, JsonObject::class.java)
                    val status = json.get("Status")?.asInt ?: 0
                    val answers = json.getAsJsonArray("Answer")
                    
                    // Status 3 is NXDOMAIN (often used for blocking), or check if resolved to 0.0.0.0
                    status == 3 || answers?.any { element ->
                        val data = element.asJsonObject.get("data")?.asString.orEmpty()
                        data == "0.0.0.0" || data == "127.0.0.1"
                    } == true
                } else false

                if (isBlockedByApi || isKnownTracker) {
                    return@withContext listOf(
                        ScanSignal(
                            ruleId = "NEXTDNS_BLOCK",
                            title = if (isKnownTracker) "Blocked by NextDNS: Ad/Tracker Filter" else "Blocked by NextDNS",
                            description = if (isKnownTracker) 
                                "This domain is filtered by your NextDNS privacy settings (Profile: $PROFILE_ID)."
                                else "NextDNS infrastructure lookup indicates this domain is blocked by your security filters.",
                            strength = SignalStrength.MEDIUM,
                            source = SignalSource.DOMAIN_SIGNAL,
                            score = 25,
                            matchedValue = domain
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w("NextDNS", "Lookup failed${if (BuildConfig.DEBUG) " for $domain" else ""}: ${e.message}")
            // Fallback to local tracker list if network fails
            if (isKnownTracker) {
                return@withContext listOf(
                    ScanSignal(
                        ruleId = "NEXTDNS_OFFLINE_BLOCK",
                        title = "Blocked by NextDNS: Ad/Tracker Filter",
                        description = "This domain is known to be filtered by NextDNS services.",
                        strength = SignalStrength.MEDIUM,
                        source = SignalSource.DOMAIN_SIGNAL,
                        score = 25,
                        matchedValue = domain
                    )
                )
            }
            // Rethrow so the orchestrator can tell "check failed" apart from "no threat found".
            throw e
        }
        emptyList()
    }
}
