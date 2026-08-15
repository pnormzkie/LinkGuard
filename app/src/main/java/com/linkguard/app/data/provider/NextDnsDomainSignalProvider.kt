package com.linkguard.app.data.provider

import android.util.Log
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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64

class NextDnsDomainSignalProvider(
    private val client: OkHttpClient,
    private val profileId: String = BuildConfig.NEXTDNS_PROFILE_ID
) : SignalProvider {

    override suspend fun fetchSignals(input: String): List<ScanSignal> = withContext(Dispatchers.IO) {
        val domain = DomainExtractor.extract(input) ?: return@withContext emptyList()
        val isKnownTracker = KnownDomains.isTracker(domain)

        // No profile configured: skip the network resolve entirely and fall back to the
        // offline tracker list (mirrors how blank API keys are handled in the other providers).
        if (profileId.isBlank()) {
            return@withContext if (isKnownTracker) offlineBlockSignal(domain) else emptyList()
        }

        try {
            // RFC 8484 DoH wireformat against the profile's own endpoint. The JSON
            // /resolve API ignores the profile entirely (verified: bogus profiles get
            // identical unfiltered answers), so filtering is only applied here.
            val query = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(buildDnsQuery(domain))
            val request = Request.Builder()
                .url("https://dns.nextdns.io/$profileId?dns=$query")
                .addHeader("Accept", "application/dns-message")
                .get()
                .build()

            client.executeCancellable(request).use { response ->
                // Fail loud on a non-2xx resolve: an endpoint outage must not read as
                // "domain not blocked". (Known trackers still fall back below via the catch.)
                if (!response.isSuccessful) {
                    throw IOException("NextDNS HTTP ${response.code}")
                }

                val isBlockedByApi = isBlockedResponse(response.body?.bytes() ?: ByteArray(0))

                if (isBlockedByApi || isKnownTracker) {
                    return@withContext listOf(
                        ScanSignal(
                            ruleId = "NEXTDNS_BLOCK",
                            title = if (isKnownTracker) "Blocked by NextDNS: Ad/Tracker Filter" else "Blocked by NextDNS",
                            description = if (isKnownTracker)
                                "This domain matches LinkGuard's ad/tracker filter list."
                                else "LinkGuard's DNS threat-intelligence check indicates this domain is blocked.",
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
                return@withContext offlineBlockSignal(domain)
            }
            // Rethrow so the orchestrator can tell "check failed" apart from "no threat found".
            throw e
        }
        emptyList()
    }

    private fun offlineBlockSignal(domain: String): List<ScanSignal> = listOf(
        ScanSignal(
            ruleId = "NEXTDNS_OFFLINE_BLOCK",
            title = "Blocked by NextDNS: Ad/Tracker Filter",
            description = "This domain matches LinkGuard's ad/tracker filter list.",
            strength = SignalStrength.MEDIUM,
            source = SignalSource.DOMAIN_SIGNAL,
            score = 25,
            matchedValue = domain
        )
    )

    companion object {
        private const val TYPE_A = 1
        private const val TYPE_OPT = 41
        private const val EDE_OPTION_CODE = 15
        private val SINKHOLE_IPS = setOf("0.0.0.0", "127.0.0.1")

        // RFC 8914 Extended DNS Error info-codes meaning the resolver filtered the name:
        // 15 Blocked, 16 Censored, 17 Filtered, 18 Prohibited. NextDNS sends 17 with
        // "Blocked by NextDNS: blocklist:..." on blocked domains.
        private val EDE_FILTERED_CODES = setOf(15, 16, 17, 18)

        /** Builds an RFC 1035 wireformat query for the domain's A record (ID 0, RD set). */
        fun buildDnsQuery(domain: String): ByteArray {
            val out = ByteArrayOutputStream()
            fun u16(v: Int) {
                out.write((v ushr 8) and 0xFF)
                out.write(v and 0xFF)
            }
            u16(0)      // ID 0 per RFC 8484 §4.1 (DoH transport carries no spoofing risk)
            u16(0x0100) // RD
            u16(1); u16(0); u16(0); u16(0)
            domain.trimEnd('.').split('.').forEach { label ->
                val bytes = label.toByteArray(Charsets.UTF_8)
                require(bytes.isNotEmpty() && bytes.size <= 63) { "Invalid DNS label: $label" }
                out.write(bytes.size)
                out.write(bytes)
            }
            out.write(0)
            u16(TYPE_A)
            u16(1) // class IN
            return out.toByteArray()
        }

        /**
         * True when the wireformat response says the resolver blocked the name:
         * a sinkhole A record or an RFC 8914 filtered EDE option. A bare NXDOMAIN
         * is NOT treated as blocked — that's just a nonexistent domain; NextDNS
         * attaches the EDE option whenever it is the one doing the blocking.
         * Throws on a malformed payload so a garbage response fails loud rather
         * than reading as "not blocked".
         */
        fun isBlockedResponse(data: ByteArray): Boolean {
            require(data.size >= 12) { "DNS response too short (${data.size} bytes)" }
            fun u8(i: Int): Int {
                require(i < data.size) { "Truncated DNS response" }
                return data[i].toInt() and 0xFF
            }
            fun u16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)

            val questionCount = u16(4)
            val recordCount = u16(6) + u16(8) + u16(10)
            var i = 12
            fun skipName() {
                while (true) {
                    val len = u8(i)
                    when {
                        len and 0xC0 == 0xC0 -> { i += 2; return } // compression pointer
                        len == 0 -> { i += 1; return }
                        else -> i += len + 1
                    }
                }
            }

            repeat(questionCount) {
                skipName()
                i += 4 // QTYPE + QCLASS
            }
            repeat(recordCount) {
                skipName()
                val rType = u16(i)
                val rdLength = u16(i + 8)
                i += 10
                require(i + rdLength <= data.size) { "Truncated DNS record" }
                when (rType) {
                    TYPE_A -> if (rdLength == 4) {
                        val ip = "${u8(i)}.${u8(i + 1)}.${u8(i + 2)}.${u8(i + 3)}"
                        if (ip in SINKHOLE_IPS) return true
                    }
                    TYPE_OPT -> {
                        var j = i
                        val end = i + rdLength
                        while (j + 4 <= end) {
                            val optCode = u16(j)
                            val optLength = u16(j + 2)
                            j += 4
                            if (optCode == EDE_OPTION_CODE && optLength >= 2 &&
                                u16(j) in EDE_FILTERED_CODES
                            ) return true
                            j += optLength
                        }
                    }
                }
                i += rdLength
            }
            return false
        }
    }
}
