package net.ssync.connect

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI

/**
 * DESIGN-CONNECT v0 Section 8 error codes.
 */
enum class ConnectErrorCode(val wireValue: String) {
    RATE_LIMITED("rate_limited"),
    BAD_SIGNATURE("bad_signature"),
    UNKNOWN_OR_EXPIRED_CODE("unknown_or_expired_code"),
    VERSION_MISMATCH("version_mismatch"),
    INVITE_LIMIT("invite_limit"),
    DNS01_LIMIT("dns01_limit"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(value: String?): ConnectErrorCode =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

@Serializable
data class ConnectErrorEnvelope(
    val error: ConnectError,
)

@Serializable
data class ConnectError(
    val code: String,
    val message: String,
    @SerialName("retry_after_seconds")
    val retryAfterSeconds: Long? = null,
) {
    val errorCode: ConnectErrorCode
        get() = ConnectErrorCode.fromWire(code)
}

/**
 * Unauthenticated `GET /v1/status` response.
 */
@Serializable
data class StatusResponse(
    val ok: Boolean,
    val version: String,
    val time: String,
)

enum class CandidateScope {
    LAN,
    WAN,
    CUSTOM,
}

@Serializable
data class CandidateAddress(
    val url: String,
    val scope: CandidateScope = classifyUrl(url),
) {
    companion object {
        fun classifyUrl(url: String): CandidateScope {
            val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return CandidateScope.CUSTOM
            // Check connect.ssync.net / staging.connect.ssync.net IP-encoded labels first
            val ipFromLabel = runCatching {
                val firstLabel = host.substringBefore('.')
                IpLabelCodec.decodeLabel(firstLabel)
            }.getOrNull()
            if (ipFromLabel != null) {
                return if (isPrivateOrLinkLocal(ipFromLabel)) CandidateScope.LAN else CandidateScope.WAN
            }
            // Check raw IP literal
            val literalIp = runCatching {
                if (host.all { it.isDigit() || it == '.' } || host.contains(':')) {
                    InetAddress.getByName(host.removePrefix("[").removeSuffix("]"))
                } else {
                    null
                }
            }.getOrNull()
            if (literalIp != null) {
                return if (isPrivateOrLinkLocal(literalIp)) CandidateScope.LAN else CandidateScope.WAN
            }
            return CandidateScope.CUSTOM
        }

        private fun isPrivateOrLinkLocal(addr: InetAddress): Boolean {
            if (addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isLoopbackAddress) return true
            if (addr is Inet4Address) {
                val b = addr.address
                val b0 = b[0].toInt() and 0xFF
                val b1 = b[1].toInt() and 0xFF
                // RFC 6598 CGNAT 100.64.0.0/10 is not LAN; RFC 1918 is covered by isSiteLocalAddress
                if (b0 == 10 || (b0 == 172 && b1 in 16..31) || (b0 == 192 && b1 == 168)) return true
            }
            return false
        }
    }
}

/**
 * Inner JSON payload signed by the SoulSync server inside the HPKE sealed answer.
 */
@Serializable
data class SealedAnswerPayload(
    val v: Int = 1,
    @SerialName("lookup_id")
    val lookupId: String,
    @SerialName("code_hash")
    val codeHash: String,
    val fingerprint: String? = null,
    @SerialName("server_pubkey")
    val serverPubkey: String,
    @SerialName("cert_fingerprint")
    val certFingerprintSha256: String,
    val addresses: List<String>,
    @SerialName("server_name")
    val serverName: String? = null,
) {
    override fun toString(): String =
        "SealedAnswerPayload(v=$v, fingerprint=${fingerprint ?: lookupId.take(12)}, lookupId=REDACTED, addresses=${addresses.size})"
}

/**
 * Verified server answer produced by [RendezvousAnswerVerifier] after passing
 * all DESIGN-CONNECT v0 Option B cryptographic checks.
 */
data class VerifiedServerAnswer(
    val identity: ServerIdentity,
    val candidateAddresses: List<CandidateAddress>,
    val certFingerprintSha256: String,
    val serverName: String?,
) {
    val lookupId: String get() = identity.lookupId
    val dnsId: String get() = identity.dnsId
    val fingerprint: String get() = identity.fingerprint
    val formattedFingerprint: String get() = identity.formattedFingerprint

    override fun toString(): String =
        "VerifiedServerAnswer(identity=$identity, serverName=$serverName, candidates=${candidateAddresses.size})"
}
