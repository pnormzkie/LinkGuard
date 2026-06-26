package com.linkguard.app.domain.scanner

import com.linkguard.app.domain.model.RedirectResolution

/**
 * Resolves a tapped link's HTTP redirect chain to its final destination so the scan pipeline
 * scores the real landing page, not just a shortener/wrapper. Implementations must be
 * read-only (never open/render the target) and bounded (hops + time). Mirrors the
 * [SignalProvider] pattern: interface in the domain layer, HTTP impl in the data layer.
 */
interface RedirectResolver {
    suspend fun resolve(url: String): RedirectResolution
}
