package com.linkguard.app.data.provider

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/** DNS policy that fails closed when any resolved address is local or non-public. */
class HostSafetyValidator(
    private val delegate: Dns = Dns.SYSTEM
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        if (isPrivateOrLocalHost(hostname)) throw blocked(hostname)
        val addresses = try {
            delegate.lookup(hostname)
        } catch (e: Exception) {
            throw UnknownHostException("Host address could not be validated").apply { initCause(e) }
        }
        if (addresses.isEmpty() || addresses.any(::isPrivateOrLocalAddress)) throw blocked(hostname)
        return addresses
    }

    companion object {
        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        fun isPrivateOrLocalHost(host: String): Boolean {
            val normalized = host.trim().lowercase().removeSurrounding("[", "]")
            if (normalized.isEmpty()) return true
            if (normalized == "localhost" || normalized.endsWith(".localhost") ||
                normalized.endsWith(".local") || normalized.endsWith(".internal") ||
                normalized.endsWith(".lan")
            ) return true
            val literal = ipLiteralOrNull(normalized) ?: return false
            return isPrivateOrLocalAddress(literal)
        }

        private fun ipLiteralOrNull(host: String): InetAddress? {
            if (!IPV4.matches(host) && !host.contains(':')) return null
            return runCatching { InetAddress.getByName(host) }.getOrNull()
        }

        private fun isPrivateOrLocalAddress(address: InetAddress): Boolean {
            val bytes = address.address
            val uniqueLocalV6 = bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
            return address.isLoopbackAddress || address.isAnyLocalAddress ||
                address.isLinkLocalAddress || address.isSiteLocalAddress || uniqueLocalV6
        }

        private fun blocked(hostname: String) =
            UnknownHostException("Blocked non-public host: $hostname")
    }
}
