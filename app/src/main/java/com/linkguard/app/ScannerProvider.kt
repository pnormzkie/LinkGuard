package com.linkguard.app

import com.linkguard.app.data.provider.DomainAgeProvider
import com.linkguard.app.data.provider.HttpCredentialFormInspector
import com.linkguard.app.data.provider.HttpRedirectResolver
import com.linkguard.app.data.provider.HostSafetyValidator
import com.linkguard.app.data.provider.Ipv4FirstDns
import com.linkguard.app.data.provider.HybridAnalysisProvider
import com.linkguard.app.data.provider.NextDnsDomainSignalProvider
import com.linkguard.app.data.provider.RetryInterceptor
import com.linkguard.app.data.provider.SafeBrowsingReputationProvider
import com.linkguard.app.data.provider.UrlHausDomainProvider
import com.linkguard.app.data.provider.VirusTotalEnrichmentProvider
import com.linkguard.app.domain.orchestrator.ScanOrchestrator
import com.linkguard.app.domain.scanner.LegacyHeuristicEngine
import com.linkguard.app.domain.scoring.ScoringEngine
import com.linkguard.app.util.AppConfig
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Singleton provider for the new ScanOrchestrator and its dependencies.
 */
object ScannerProvider {

    private val hostSafetyValidator: HostSafetyValidator by lazy { HostSafetyValidator(Ipv4FirstDns()) }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(Ipv4FirstDns())
            .callTimeout(AppConfig.PROVIDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // Well under the call budget, so a dead address still leaves time for the next one.
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            // One retry on transient 5xx / connection failures; never on 4xx (esp. 429 quota).
            .addInterceptor(RetryInterceptor())
            .build()
    }

    // Dedicated client for redirect resolution: auto-redirects DISABLED so the resolver
    // inspects and bounds every hop itself; short per-hop timeouts to protect click-time UX.
    private val redirectHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(hostSafetyValidator)
            .connectTimeout(AppConfig.REDIRECT_PER_HOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(AppConfig.REDIRECT_PER_HOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    // Dedicated client for the credential-form page fetch: short timeouts for click-time UX;
    // auto-redirects DISABLED because the URL is already resolved (and to avoid SSRF via a
    // redirect to a private host). The inspector caps how much of the body it reads.
    private val contentHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(hostSafetyValidator)
            .callTimeout(AppConfig.CONTENT_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectTimeout(AppConfig.CONTENT_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(AppConfig.CONTENT_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private val scoringEngine = ScoringEngine()

    val orchestrator: ScanOrchestrator by lazy {
        ScanOrchestrator(
            heuristicEngine = LegacyHeuristicEngine(),
            reputationProvider = SafeBrowsingReputationProvider(
                okHttpClient,
                AppConfig.SAFE_BROWSING_API_KEY
            ),
            domainSignalProvider = NextDnsDomainSignalProvider(okHttpClient),
            enrichmentProvider = VirusTotalEnrichmentProvider(
                okHttpClient,
                AppConfig.VIRUSTOTAL_API_KEY
            ),
            scoringEngine = scoringEngine,
            hybridAnalysisProvider = HybridAnalysisProvider(
                okHttpClient,
                AppConfig.HYBRID_ANALYSIS_API_KEY
            ),
            domainAgeProvider = DomainAgeProvider(okHttpClient),
            urlHausProvider = UrlHausDomainProvider(okHttpClient, AppConfig.URLHAUS_AUTH_KEY),
            redirectResolver = HttpRedirectResolver(redirectHttpClient),
            credentialFormInspector = HttpCredentialFormInspector(contentHttpClient)
        )
    }
}
