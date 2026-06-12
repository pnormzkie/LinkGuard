package com.linkguard.app.data.provider

import android.util.Log
import com.linkguard.app.BuildConfig
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.linkguard.app.domain.model.ScanSignal
import com.linkguard.app.domain.model.SignalSource
import com.linkguard.app.domain.model.SignalStrength
import com.linkguard.app.domain.scanner.SignalProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class SafeBrowsingReputationProvider(
    private val client: OkHttpClient,
    private val apiKey: String
) : SignalProvider {

    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun fetchSignals(input: String): List<ScanSignal> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext emptyList()

        try {
            val body = buildRequestBody(input)

            val request = Request.Builder()
                .url("https://safebrowsing.googleapis.com/v4/threatMatches:find?key=$apiKey")
                .post(body.toRequestBody(mediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                // Fail loud on non-2xx (bad key = 403, quota = 429, server = 5xx) so the
                // orchestrator can tell "check failed" apart from "checked clean".
                if (!response.isSuccessful) {
                    throw IOException("Safe Browsing HTTP ${response.code}")
                }
                if (responseBody.isBlank()) return@withContext emptyList()

                val json = gson.fromJson(responseBody, JsonObject::class.java)
                val hasMatches = json.has("matches") && json.getAsJsonArray("matches").size() > 0

                if (hasMatches) {
                    listOf(
                        ScanSignal(
                            ruleId = "SAFE_BROWSING_MATCH",
                            title = "Flagged by Google Safe Browsing",
                            description = "This URL is present on Google's list of dangerous sites.",
                            strength = SignalStrength.CRITICAL,
                            source = SignalSource.EXTERNAL_REPUTATION,
                            score = 100,
                            matchedValue = input
                        )
                    )
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            // Rethrow so the orchestrator can tell "check failed" apart from "no threat found".
            Log.w("SafeBrowsing", "Lookup failed${if (BuildConfig.DEBUG) " for $input" else ""}: ${e.message}")
            throw e
        }
    }

    companion object {
        private val bodyGson = Gson()

        /**
         * Builds the Safe Browsing threatMatches:find request body with Gson so the
         * URL is always correctly JSON-escaped. Hand-built string interpolation broke
         * for URLs containing `"`, `\`, or newlines, producing malformed JSON that the
         * API rejected with 400 and silently dropped this provider from the scan.
         */
        fun buildRequestBody(input: String): String {
            val client = JsonObject().apply {
                addProperty("clientId", "linkguard")
                addProperty("clientVersion", "1.0")
            }

            val threatTypes = JsonArray().apply {
                add("MALWARE")
                add("SOCIAL_ENGINEERING")
                add("UNWANTED_SOFTWARE")
                add("POTENTIALLY_HARMFUL_APPLICATION")
            }

            val entry = JsonObject().apply { addProperty("url", input) }
            val threatEntries = JsonArray().apply { add(entry) }

            val threatInfo = JsonObject().apply {
                add("threatTypes", threatTypes)
                add("platformTypes", JsonArray().apply { add("ANY_PLATFORM") })
                add("threatEntryTypes", JsonArray().apply { add("URL") })
                add("threatEntries", threatEntries)
            }

            val root = JsonObject().apply {
                add("client", client)
                add("threatInfo", threatInfo)
            }

            return bodyGson.toJson(root)
        }
    }
}
