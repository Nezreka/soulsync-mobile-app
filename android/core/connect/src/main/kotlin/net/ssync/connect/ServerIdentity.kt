package net.ssync.connect

/**
 * Cryptographic identity of a SoulSync server derived from its raw 32-byte Ed25519 public key.
 *
 * - [lookupId]: full 64-char lowercase hex `SHA-256(ed25519_pubkey)`. Treat as a secret!
 *   Used everywhere off-DNS (`X-Server-Id`, sealed payload verification, vault storage).
 * - [dnsId]: first 32 lowercase hex chars of [lookupId]. Used in DNS hostnames
 *   (`*.<dns_id>.connect.ssync.net`) so labels stay within DNS's 63-char limit.
 * - [fingerprint]: first 12 lowercase hex chars of [lookupId], displayed as `XXXX-XXXX-XXXX`
 *   during first-contact verification.
 */
class ServerIdentity private constructor(
    val publicKeyBytes: ByteArray,
    val lookupId: String,
) {
    val publicKeyBase64Url: String = CryptoUtils.base64UrlEncodeNoPadding(publicKeyBytes)
    val publicKeyHex: String = CryptoUtils.toLowerHex(publicKeyBytes)

    /** First 32 chars of [lookupId], used in `*.<dns_id>.connect.ssync.net` hostnames. */
    val dnsId: String = lookupId.take(DNS_ID_LENGTH)

    /** First 12 chars of [lookupId] (lowercase hex). */
    val fingerprint: String = lookupId.take(FINGERPRINT_LENGTH)

    /** Human-readable first-contact verification code (`XXXX-XXXX-XXXX`). */
    val formattedFingerprint: String =
        fingerprint.uppercase().chunked(4).joinToString("-")

    /** Redacted to avoid leaking [lookupId] in logs or crash reports. */
    override fun toString(): String =
        "ServerIdentity(fingerprint=$formattedFingerprint, lookupId=REDACTED)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ServerIdentity) return false
        return CryptoUtils.constantTimeEquals(lookupId, other.lookupId)
    }

    override fun hashCode(): Int = fingerprint.hashCode()

    companion object {
        const val ED25519_PUBLIC_KEY_BYTES = 32
        const val LOOKUP_ID_LENGTH = 64
        const val DNS_ID_LENGTH = 32
        const val FINGERPRINT_LENGTH = 12

        fun fromEd25519PublicKey(rawPublicKey: ByteArray): ServerIdentity {
            require(rawPublicKey.size == ED25519_PUBLIC_KEY_BYTES) {
                "Ed25519 public key must be $ED25519_PUBLIC_KEY_BYTES bytes"
            }
            val copy = rawPublicKey.copyOf()
            val lookupId = CryptoUtils.sha256Hex(copy)
            return ServerIdentity(copy, lookupId)
        }

        fun fromEd25519PublicKeyBase64Url(base64Url: String): ServerIdentity =
            fromEd25519PublicKey(CryptoUtils.base64UrlDecodeNoPadding(base64Url))

        fun fromEd25519PublicKeyHex(hex: String): ServerIdentity =
            fromEd25519PublicKey(CryptoUtils.fromHex(hex))
    }
}
