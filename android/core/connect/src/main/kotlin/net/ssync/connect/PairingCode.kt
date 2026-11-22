package net.ssync.connect

/**
 * DESIGN-CONNECT v0 Section 2 pairing code (~80 bits of entropy).
 *
 * 16 characters from the 32-symbol unambiguous alphabet:
 * `0123456789ABCDEFGHJKMNPQRSTVWXYZ` (Crockford Base32, excluding I, L, O, U).
 *
 * Raw code travels ONLY `user -> app` and `app -> SoulSync` (direct cert-pinned redemption).
 * NEVER send [rawValue] to `connect.ssync.net` and NEVER log it.
 */
class PairingCode private constructor(
    private val normalized: String,
) {
    /**
     * Returns the raw 16-character normalized code strictly for direct redemption against SoulSync.
     */
    fun rawForDirectRedemption(): String = normalized

    /**
     * `code_hash = lowercase hex SHA-256(code as UTF-8)`.
     * Safe to send to `connect.ssync.net` for blind rendezvous lookup.
     */
    val codeHash: String = CryptoUtils.sha256Hex(normalized)

    override fun toString(): String = "PairingCode(REDACTED)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingCode) return false
        return CryptoUtils.constantTimeEquals(normalized, other.normalized)
    }

    override fun hashCode(): Int = codeHash.hashCode()

    companion object {
        const val LENGTH = 16
        const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private val ALPHABET_SET: Set<Char> = ALPHABET.toSet()

        fun parse(input: String): Result<PairingCode> {
            val cleaned = input
                .trim()
                .replace("-", "")
                .replace(" ", "")
                .uppercase()

            if (cleaned.length != LENGTH) {
                return Result.failure(
                    IllegalArgumentException("Pairing code must be $LENGTH characters (got ${cleaned.length})"),
                )
            }
            for (ch in cleaned) {
                if (ch !in ALPHABET_SET) {
                    return Result.failure(
                        IllegalArgumentException("Pairing code contains invalid character"),
                    )
                }
            }
            return Result.success(PairingCode(cleaned))
        }
    }
}
