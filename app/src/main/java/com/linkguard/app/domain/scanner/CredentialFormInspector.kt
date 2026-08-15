package com.linkguard.app.domain.scanner

import com.linkguard.app.domain.model.ScanSignal

/**
 * Inspects a destination page's HTML for a credential (login/password) form.
 *
 * Invoked CONDITIONALLY by the orchestrator — only for an untrusted host whose URL/reputation
 * signals already make it borderline-suspicious — so the network body fetch (a privacy/perf
 * cost) is paid only when a found form would actually change the verdict. A login form alone is
 * not malicious; this is a confirming amplifier, never a standalone verdict.
 *
 * Implementations must be fail-soft: any error (network, non-HTML, blocked host) yields an
 * empty list so it can never break the surrounding scan.
 */
interface CredentialFormInspector {
    /** Side-effect-free eligibility check. Must not perform network access. */
    fun isEligible(finalUrl: String): Boolean

    /** @param finalUrl the already-resolved destination URL. */
    suspend fun inspect(finalUrl: String): List<ScanSignal>
}
