package com.linkguard.app.data.provider

import android.util.Log
import com.linkguard.app.BuildConfig
import com.linkguard.app.domain.model.RedirectOutcome
import com.linkguard.app.domain.model.RedirectResolution
import com.linkguard.app.domain.scanner.RedirectResolver
import com.linkguard.app.util.AppConfig
import com.linkguard.app.util.DomainExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.InetAddress

/**
 * Follows a tapped link's HTTP redirect chain to its final destination, read-only and bounded.
 *
 * Safety invariants:
 *  - never opens/renders the target — it only walks `Location` headers (HEAD, GET fallback),
 *    never reads the body;
 *  - the injected [client] MUST disable auto-redirects so every hop is inspected here;
 *  - rejects non-http(s) redirect targets and private/loopback/link-local hosts (anti-SSRF);
 *  - bounded by [maxHops], a loop check, and a total [totalBudgetMs] time budget.
 *
 * Never throws: transport failures resolve to [RedirectOutcome.ERROR] with the best-known URL,
 * so the orchestrator can still score what it has and keep a shortener suspicious.
 */
class HttpRedirectResolver(
    private val client: OkHttpClient,
    private val maxHops: Int = AppConfig.REDIRECT_MAX_HOPS,
    private val totalBudgetMs: Long = AppConfig.REDIRECT_TOTAL_BUDGET_MS,
    // Injected so timeout behaviour is testable without real time passing.
    private val now: () -> Long = System::currentTimeMillis,
    // Injected so the host-safety check can be exercised deterministically in tests.
    private val isBlockedHost: (String) -> Boolean = { isPrivateOrLocalHost(it) }
) : RedirectResolver {

    override suspend fun resolve(url: String): RedirectResolution = withContext(Dispatchers.IO) {
        val chain = mutableListOf(url)
        var current = url
        val startedAt = now()
        var hops = 0

        while (true) {
            if (now() - startedAt >= totalBudgetMs) return@withContext result(chain, current, RedirectOutcome.TIMEOUT)
            if (hops >= maxHops) return@withContext result(chain, current, RedirectOutcome.MAX_HOPS)

            val response = try {
                fetchHop(current)
            } catch (e: CancellationException) {
                throw e // never swallow real coroutine cancellation
            } catch (e: Exception) {
                Log.w(TAG, "redirect hop failed${if (BuildConfig.DEBUG) " for $current" else ""}: ${e.message}")
                return@withContext result(chain, current, RedirectOutcome.ERROR)
            }

            val code: Int
            val location: String?
            response.use {
                code = it.code
                location = if (code in 300..399) it.header("Location") else null
            }

            if (code !in 300..399 || location.isNullOrBlank()) {
                val outcome = if (chain.size > 1) RedirectOutcome.RESOLVED else RedirectOutcome.NO_REDIRECT
                return@withContext result(chain, current, outcome)
            }

            // resolve() returns null for relative-against-invalid or non-http(s) schemes —
            // that is exactly the "blocked scheme" case (javascript:/data:/intent:/mailto:).
            val next = current.toHttpUrlOrNull()?.resolve(location)
                ?: return@withContext result(chain, current, RedirectOutcome.BLOCKED_SCHEME)
            if (isBlockedHost(next.host)) {
                return@withContext result(chain, current, RedirectOutcome.BLOCKED_PRIVATE_HOST)
            }
            val nextStr = next.toString()
            if (chain.contains(nextStr)) return@withContext result(chain, current, RedirectOutcome.LOOP)

            chain.add(nextStr)
            current = nextStr
            hops++
        }
        @Suppress("UNREACHABLE_CODE")
        result(chain, current, RedirectOutcome.RESOLVED)
    }

    /** HEAD first (minimal side effects); fall back to GET when the server rejects HEAD. */
    private fun fetchHop(url: String): Response {
        val head = client.newCall(request(url, head = true)).execute()
        if (head.code == 405 || head.code == 501) {
            head.close()
            return client.newCall(request(url, head = false)).execute()
        }
        return head
    }

    private fun request(url: String, head: Boolean): Request {
        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        if (head) builder.head() else builder.get()
        return builder.build()
    }

    private fun result(chain: List<String>, finalUrl: String, outcome: RedirectOutcome) =
        RedirectResolution(
            finalUrl = finalUrl,
            hops = chain.toList(),
            crossedDomains = crossedDomains(chain.first(), finalUrl),
            outcome = outcome
        )

    private fun crossedDomains(first: String, last: String): Boolean {
        val a = registrableDomain(DomainExtractor.extract(first) ?: return false)
        val b = registrableDomain(DomainExtractor.extract(last) ?: return false)
        return a.isNotEmpty() && b.isNotEmpty() && a != b
    }

    companion object {
        private const val TAG = "RedirectResolver"
        private const val USER_AGENT = "LinkGuard-SafetyCheck/1.0"
        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        private val SECOND_LEVEL_TLDS = setOf(
            "com.ph", "net.ph", "org.ph", "gov.ph",
            "co.uk", "org.uk", "gov.uk",
            "com.au", "com.sg", "com.my"
        )

        /**
         * True if [host] is a loopback/private/link-local/unique-local address or a local-only
         * name — redirect targets we must not contact (anti-SSRF / LAN probing). Named hosts that
         * are not IP literals are not blocked here (resolving DNS to check would add latency); a
         * named host pointing at a private IP is a documented residual.
         */
        fun isPrivateOrLocalHost(host: String): Boolean {
            val h = host.trim().lowercase().removeSurrounding("[", "]")
            if (h.isEmpty()) return true
            if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") ||
                h.endsWith(".internal") || h.endsWith(".lan")
            ) return true
            val addr = ipLiteralOrNull(h) ?: return false
            return addr.isLoopbackAddress || addr.isAnyLocalAddress ||
                addr.isLinkLocalAddress || addr.isSiteLocalAddress || isUniqueLocalV6(addr)
        }

        // Only parse when the host looks like an IP literal — InetAddress.getByName does NOT
        // perform DNS for a literal, so this stays network-free for named hosts (returns null).
        private fun ipLiteralOrNull(host: String): InetAddress? {
            if (!IPV4.matches(host) && !host.contains(':')) return null
            return runCatching { InetAddress.getByName(host) }.getOrNull()
        }

        private fun isUniqueLocalV6(addr: InetAddress): Boolean {
            val bytes = addr.address
            return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc // fc00::/7
        }

        private fun registrableDomain(domain: String): String {
            val parts = domain.removePrefix("www.").split(".").filter { it.isNotEmpty() }
            if (parts.size <= 2) return parts.joinToString(".")
            val lastTwo = parts.takeLast(2).joinToString(".")
            return if (lastTwo in SECOND_LEVEL_TLDS) parts.takeLast(3).joinToString(".") else lastTwo
        }
    }
}
