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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

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

    private data class RedirectState(
        val chain: MutableList<String>,
        var current: String
    )

    private data class HopResponse(val code: Int, val location: String?)

    override suspend fun resolve(url: String): RedirectResolution {
        val state = RedirectState(mutableListOf(url), url)
        return withTimeoutOrNull(totalBudgetMs) {
            withContext(Dispatchers.IO) { resolveWithinBudget(state) }
        } ?: result(state.chain, state.current, RedirectOutcome.TIMEOUT)
    }

    private suspend fun resolveWithinBudget(state: RedirectState): RedirectResolution {
        val startedAt = now()
        var hops = 0

        while (true) {
            if (now() - startedAt >= totalBudgetMs) {
                return result(state.chain, state.current, RedirectOutcome.TIMEOUT)
            }
            if (hops >= maxHops) return result(state.chain, state.current, RedirectOutcome.MAX_HOPS)

            val response = try {
                fetchHop(state.current)
            } catch (e: CancellationException) {
                throw e // never swallow real coroutine cancellation
            } catch (e: Exception) {
                Log.w(TAG, "redirect hop failed${if (BuildConfig.DEBUG) " for ${state.current}" else ""}: ${e.message}")
                return result(state.chain, state.current, RedirectOutcome.ERROR)
            }

            val code = response.code
            val location = response.location

            if (code !in 300..399 || location.isNullOrBlank()) {
                val outcome = if (state.chain.size > 1) RedirectOutcome.RESOLVED else RedirectOutcome.NO_REDIRECT
                return result(state.chain, state.current, outcome)
            }

            // resolve() returns null for relative-against-invalid or non-http(s) schemes —
            // that is exactly the "blocked scheme" case (javascript:/data:/intent:/mailto:).
            val next = state.current.toHttpUrlOrNull()?.resolve(location)
                ?: return result(state.chain, state.current, RedirectOutcome.BLOCKED_SCHEME)
            if (isBlockedHost(next.host)) {
                return result(state.chain, state.current, RedirectOutcome.BLOCKED_PRIVATE_HOST)
            }
            val nextStr = next.toString()
            if (state.chain.contains(nextStr)) return result(state.chain, state.current, RedirectOutcome.LOOP)

            state.chain.add(nextStr)
            state.current = nextStr
            hops++
        }
    }

    /** HEAD first (minimal side effects); fall back to GET when the server rejects HEAD. */
    private suspend fun fetchHop(url: String): HopResponse {
        client.executeCancellable(request(url, head = true)).use { head ->
            if (head.code != 405 && head.code != 501) {
                return HopResponse(head.code, head.header("Location"))
            }
        }
        client.executeCancellable(request(url, head = false)).use { get ->
            return HopResponse(get.code, get.header("Location"))
        }
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
        fun isPrivateOrLocalHost(host: String): Boolean =
            HostSafetyValidator.isPrivateOrLocalHost(host)

        private fun registrableDomain(domain: String): String {
            val parts = domain.removePrefix("www.").split(".").filter { it.isNotEmpty() }
            if (parts.size <= 2) return parts.joinToString(".")
            val lastTwo = parts.takeLast(2).joinToString(".")
            return if (lastTwo in SECOND_LEVEL_TLDS) parts.takeLast(3).joinToString(".") else lastTwo
        }
    }
}
