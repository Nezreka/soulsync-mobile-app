package net.ssync.connect

import okhttp3.Dns
import java.net.InetAddress

/**
 * resolves a server's own connect name (`<ip-label>.<dns_id>.connect.ssync.net`)
 * from the name itself: the first label is the ip. no dns lookup, so a home
 * router that drops answers pointing at home addresses (dns rebinding
 * protection, common on Fritz!Box, OPNsense, Pi-hole and others) can't
 * stop the app reaching the server at home (#2328).
 *
 * safe: the certificate pin, not the name, says who the server is. any
 * other host goes to [fallback].
 */
class ConnectLabelDns(private val fallback: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        decode(hostname)?.let { return listOf(it) }
        return fallback.lookup(hostname)
    }

    companion object {
        val INSTANCE = ConnectLabelDns()

        private val ZONES = listOf(IpLabelCodec.STAGING_ZONE, IpLabelCodec.PROD_ZONE)

        /** the ip a connect name carries, or null for any other name */
        fun decode(hostname: String): InetAddress? {
            val host = hostname.lowercase().trimEnd('.')
            val zone = ZONES.firstOrNull { host.endsWith(".$it") } ?: return null
            val labels = host.removeSuffix(".$zone").split('.')
            // exactly <ip-label>.<dns_id>
            if (labels.size != 2) return null
            return runCatching {
                IpLabelCodec.validateDnsId(labels[1])
                IpLabelCodec.decodeLabel(labels[0])
            }.getOrNull()
        }
    }
}
