package net.ssync.connect

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Stateless IP-decoding DNS label codec for `connect.ssync.net`.
 *
 * Per DESIGN-CONNECT v0 + Nov 21, 2026 DNS label correction:
 * - IPv4 label: `a-b-c-d` (no leading zeros, octets 0..255)
 * - IPv6 label: 32-char lowercase hex (16 bytes)
 * - DNS hostnames use `dns_id = lookup_id.take(32)` (32 lowercase hex chars):
 *   - Prod: `<ip-label>.<dns_id>.connect.ssync.net`
 *   - Staging: `<ip-label>.<dns_id>.staging.connect.ssync.net`
 */
object IpLabelCodec {
    const val PROD_ZONE = "connect.ssync.net"
    const val STAGING_ZONE = "staging.connect.ssync.net"

    private val IPV4_OCTET_REGEX = Regex("^(0|[1-9][0-9]{0,2})$")
    private val LOWER_HEX_32_REGEX = Regex("^[0-9a-f]{32}$")

    fun encodeIp(ip: String): String {
        val addr = parseStrictIp(ip)
        return when (addr) {
            is Inet4Address -> addr.address.joinToString("-") { (it.toInt() and 0xFF).toString() }
            is Inet6Address -> CryptoUtils.toLowerHex(addr.address)
            else -> throw IllegalArgumentException("Unsupported IP address type")
        }
    }

    fun decodeLabel(label: String): InetAddress {
        require(label.isNotEmpty()) { "IP label must not be empty" }

        if (label.contains('-')) {
            val parts = label.split('-')
            require(parts.size == 4) { "Invalid IPv4 label: expected 4 octets" }
            val bytes = ByteArray(4)
            for (i in 0 until 4) {
                val part = parts[i]
                require(IPV4_OCTET_REGEX.matches(part)) { "Invalid IPv4 octet: '$part'" }
                val value = part.toInt()
                require(value in 0..255) { "IPv4 octet out of range: $value" }
                bytes[i] = value.toByte()
            }
            return InetAddress.getByAddress(bytes)
        }

        require(LOWER_HEX_32_REGEX.matches(label)) {
            "Invalid IP label: must be dashed IPv4 or 32-char lowercase hex IPv6"
        }
        val bytes = CryptoUtils.fromHex(label)
        return InetAddress.getByAddress(bytes)
    }

    fun buildHostname(ip: String, dnsId: String, staging: Boolean = false): String {
        validateDnsId(dnsId)
        val label = encodeIp(ip)
        val zone = if (staging) STAGING_ZONE else PROD_ZONE
        return "$label.$dnsId.$zone"
    }

    fun buildWildcardHostname(dnsId: String, staging: Boolean = false): String {
        validateDnsId(dnsId)
        val zone = if (staging) STAGING_ZONE else PROD_ZONE
        return "*.$dnsId.$zone"
    }

    fun parseHostname(hostname: String): ParsedConnectHost {
        val normalized = hostname.trim().removeSuffix(".")
        val (prefix, staging) = when {
            normalized.endsWith(".$STAGING_ZONE") ->
                normalized.removeSuffix(".$STAGING_ZONE") to true
            normalized.endsWith(".$PROD_ZONE") ->
                normalized.removeSuffix(".$PROD_ZONE") to false
            else -> throw IllegalArgumentException("Hostname does not belong to $PROD_ZONE or $STAGING_ZONE")
        }
        val labels = prefix.split('.')
        require(labels.size == 2) { "Expected <ip-label>.<dns_id> before zone" }
        val ipLabel = labels[0]
        val dnsId = labels[1]
        validateDnsId(dnsId)
        val inetAddress = decodeLabel(ipLabel)
        return ParsedConnectHost(
            address = inetAddress,
            ipLabel = ipLabel,
            dnsId = dnsId,
            staging = staging,
        )
    }

    fun validateDnsId(dnsId: String) {
        require(LOWER_HEX_32_REGEX.matches(dnsId)) {
            "dns_id must be 32 lowercase hex characters (first 32 chars of lookup_id)"
        }
    }

    private fun parseStrictIp(ip: String): InetAddress {
        val trimmed = ip.trim()
        require(trimmed.isNotEmpty()) { "IP string must not be empty" }
        if (trimmed.contains('.')) {
            val parts = trimmed.split('.')
            require(parts.size == 4) { "Invalid IPv4 string" }
            val bytes = ByteArray(4)
            for (i in 0 until 4) {
                require(IPV4_OCTET_REGEX.matches(parts[i])) { "Invalid IPv4 octet: '${parts[i]}'" }
                val value = parts[i].toInt()
                require(value in 0..255) { "IPv4 octet out of range: $value" }
                bytes[i] = value.toByte()
            }
            return InetAddress.getByAddress(bytes)
        }
        require(trimmed.contains(':')) { "Invalid IP address: '$trimmed'" }
        val addr = InetAddress.getByName(trimmed)
        require(addr is Inet6Address) { "Expected IPv6 address" }
        return addr
    }
}

data class ParsedConnectHost(
    val address: InetAddress,
    val ipLabel: String,
    val dnsId: String,
    val staging: Boolean,
)
