package com.linkguard.app.update

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.linkguard.app.util.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class UpdateInfo(
    val versionName: String,
    val releaseNotes: String,
    val apkUrl: String?,
    val apkName: String?,
    val htmlUrl: String
)

/**
 * Checks the GitHub Releases API for a newer published version of the app.
 */
class UpdateChecker(private val client: OkHttpClient) {

    suspend fun fetchLatestRelease(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/${AppConfig.GITHUB_REPO}/releases/latest")
                .addHeader("Accept", "application/vnd.github+json")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful || body.isBlank()) {
                    Log.w(TAG, "Update check failed: HTTP ${response.code}")
                    return@withContext null
                }
                parseRelease(body)
            }
        } catch (e: Exception) {
            // An update check must never disturb app startup — fail silently.
            Log.w(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "UpdateChecker"
        private val gson = Gson()

        fun parseRelease(json: String): UpdateInfo? {
            return try {
                val obj = gson.fromJson(json, JsonObject::class.java) ?: return null
                val tagElement = obj.get("tag_name")
                if (tagElement == null || tagElement.isJsonNull) return null
                val tag = tagElement.asString.trim()
                if (tag.isEmpty()) return null

                var apkUrl: String? = null
                var apkName: String? = null
                val assets = obj.get("assets")?.takeIf { it.isJsonArray }?.asJsonArray
                assets?.forEach { element ->
                    if (apkUrl == null && element.isJsonObject) {
                        val asset = element.asJsonObject
                        val name = asset.get("name")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.get("browser_download_url")?.takeIf { !it.isJsonNull }?.asString
                            apkName = name
                        }
                    }
                }

                UpdateInfo(
                    versionName = tag.removePrefix("v").removePrefix("V"),
                    releaseNotes = obj.get("body")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                    apkUrl = apkUrl,
                    apkName = apkName,
                    htmlUrl = obj.get("html_url")?.takeIf { !it.isJsonNull }?.asString
                        ?: "https://github.com/${AppConfig.GITHUB_REPO}/releases"
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not parse release JSON: ${e.message}")
                null
            }
        }

        /**
         * True when [remote] is strictly newer than [current] (numeric, part by part,
         * "v" prefix and pre-release suffixes ignored). Unparseable versions never alert.
         */
        fun isNewerVersion(remote: String, current: String): Boolean {
            val r = parseVersion(remote)
            val c = parseVersion(current)
            if (r.isEmpty() || c.isEmpty()) return false
            for (i in 0 until maxOf(r.size, c.size)) {
                val rPart = r.getOrElse(i) { 0 }
                val cPart = c.getOrElse(i) { 0 }
                if (rPart != cPart) return rPart > cPart
            }
            return false
        }

        private fun parseVersion(version: String): List<Int> =
            version.trim()
                .removePrefix("v").removePrefix("V")
                .takeWhile { it.isDigit() || it == '.' }
                .split(".")
                .mapNotNull { it.toIntOrNull() }
    }
}
