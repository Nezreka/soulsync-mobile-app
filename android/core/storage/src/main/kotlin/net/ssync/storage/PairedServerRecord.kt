package net.ssync.storage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.ssync.connect.CandidateAddress

/**
 * Encrypted persistent record for a paired SoulSync server.
 *
 * Security: [lookupId] and [deviceToken] are secrets and MUST NEVER appear in logs,
 * crash reports, or plaintext SharedPreferences.
 */
@Serializable
data class PairedServerRecord(
    @SerialName("lookup_id")
    val lookupId: String,
    @SerialName("server_pubkey")
    val serverPubkey: String,
    @SerialName("cert_fingerprint_sha256")
    val certFingerprintSha256: String,
    @SerialName("candidate_addresses")
    val candidateAddresses: List<CandidateAddress>,
    @SerialName("last_working_base_url")
    val lastWorkingBaseUrl: String,
    @SerialName("device_token")
    val deviceToken: String,
    @SerialName("server_name")
    val serverName: String? = null,
    @SerialName("profile_id")
    val profileId: String,
    @SerialName("profile_name")
    val profileName: String,
) {
    val fingerprint: String
        get() = lookupId.take(12)

    val formattedFingerprint: String
        get() = fingerprint.uppercase().chunked(4).joinToString("-")

    override fun toString(): String =
        "PairedServerRecord(serverName=$serverName, fingerprint=$formattedFingerprint, " +
            "profileName=$profileName, lookupId=REDACTED, deviceToken=REDACTED)"
}
