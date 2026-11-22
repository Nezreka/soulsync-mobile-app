package net.ssync.connect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Constructs HPKE `info` bytes bound strictly to `code_hash` (DESIGN-CONNECT v0 Option B).
 *
 * Pinned layout from `conformance-vectors.json`:
 * ASCII `"soulsync-connect-hpke-info-v1\n"` followed by `code_hash` lowercase hex.
 */
interface HpkeInfoBuilder {
    fun build(codeHash: String): ByteArray
}

object DefaultHpkeInfoBuilder : HpkeInfoBuilder {
    const val PREFIX = "soulsync-connect-hpke-info-v1\n"

    override fun build(codeHash: String): ByteArray {
        val normalized = codeHash.lowercase()
        require(normalized.length == 64 && normalized.all { it in '0'..'9' || it in 'a'..'f' }) {
            "code_hash must be 64 lowercase hex characters"
        }
        return (PREFIX + normalized).toByteArray(Charsets.US_ASCII)
    }
}

data class DecodedSealedEnvelope(
    val payload: SealedAnswerPayload,
    val canonicalPayloadBytes: ByteArray,
    val signatureBytes: ByteArray,
)

interface SealedPayloadCodec {
    fun decode(plaintext: ByteArray): DecodedSealedEnvelope
}

/**
 * Decodes the inner JSON envelope `{"payload": {...}, "signature": "<b64url>"}`.
 *
 * Produces canonical compact JSON bytes (keys sorted alphabetically) for `payload`,
 * matching the reference implementation's `json.dumps(payload, separators=(',', ':'), sort_keys=True)`.
 */
class JsonSealedPayloadCodec(
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SealedPayloadCodec {

    override fun decode(plaintext: ByteArray): DecodedSealedEnvelope {
        val root = json.parseToJsonElement(plaintext.toString(Charsets.UTF_8)).jsonObject
        val payloadElement = root["payload"]?.jsonObject
            ?: throw AnswerVerificationException("Missing 'payload' object in sealed answer")
        val signatureB64 = root["signature"]?.jsonPrimitive?.content
            ?: throw AnswerVerificationException("Missing 'signature' field in sealed answer")

        val canonicalBytes = canonicalizeJson(payloadElement).toByteArray(Charsets.UTF_8)
        val payload = json.decodeFromJsonElement<SealedAnswerPayload>(payloadElement)
        val signatureBytes = try {
            CryptoUtils.base64UrlDecodeNoPadding(signatureB64)
        } catch (e: Exception) {
            throw AnswerVerificationException("Invalid base64url signature", e)
        }
        return DecodedSealedEnvelope(
            payload = payload,
            canonicalPayloadBytes = canonicalBytes,
            signatureBytes = signatureBytes,
        )
    }

    companion object {
        fun canonicalizeJson(element: JsonElement): String = when (element) {
            is JsonObject -> element.entries
                .sortedBy { it.key }
                .joinToString(separator = ",", prefix = "{", postfix = "}") { (k, v) ->
                    "${JsonPrimitive(k)}:${canonicalizeJson(v)}"
                }
            is JsonArray -> element.joinToString(separator = ",", prefix = "[", postfix = "]") {
                canonicalizeJson(it)
            }
            is JsonPrimitive -> element.toString()
        }
    }
}

class AnswerVerificationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * DESIGN-CONNECT v0 Section 6 (Amended Nov 21, 2026 Option B) verification pipeline:
 * 1. Build HPKE `info` bound to `requestedCodeHash` only.
 * 2. Open raw `enc || ciphertext` with the app's ephemeral X25519 private key + `info`.
 * 3. Decode inner envelope and verify the Ed25519 signature over `payload` using `payload.server_pubkey`.
 * 4. Recompute `derived_lookup_id = lowercase hex SHA-256(raw 32-byte Ed25519 pubkey)` and assert
 *    constant-time equality with `payload.lookup_id` (stops cross-server replay).
 * 5. Assert constant-time equality `payload.code_hash == requestedCodeHash` (stops cross-code replay).
 * 6. Extract verified `{ candidateAddresses, certFingerprintSha256, identity, serverName }`.
 */
class RendezvousAnswerVerifier(
    private val hpkeOpener: RawRfc9180HpkeOpener = BouncyCastleRfc9180Hpke,
    private val infoBuilder: HpkeInfoBuilder = DefaultHpkeInfoBuilder,
    private val payloadCodec: SealedPayloadCodec = JsonSealedPayloadCodec(),
    private val ed25519Verifier: Ed25519SignatureVerifier = BouncyCastleEd25519Verifier,
) {
    fun verifyAndUnseal(
        ephemeralPrivateKey: ByteArray,
        sealedBytes: ByteArray,
        requestedCodeHash: String,
    ): VerifiedServerAnswer {
        val info = infoBuilder.build(requestedCodeHash)

        val plaintext = try {
            hpkeOpener.open(
                recipientPrivateKey = ephemeralPrivateKey,
                sealedBytes = sealedBytes,
                info = info,
                aad = ByteArray(0),
            )
        } catch (e: Exception) {
            throw AnswerVerificationException("Failed to unseal HPKE answer", e)
        }

        val envelope = payloadCodec.decode(plaintext)
        val payload = envelope.payload

        val rawServerPubkey = try {
            CryptoUtils.base64UrlDecodeNoPadding(payload.serverPubkey)
        } catch (e: Exception) {
            throw AnswerVerificationException("Invalid server_pubkey encoding", e)
        }
        if (rawServerPubkey.size != ServerIdentity.ED25519_PUBLIC_KEY_BYTES) {
            throw AnswerVerificationException("Invalid server_pubkey length: ${rawServerPubkey.size}")
        }

        // Step 3: Verify inner Ed25519 signature over payload bytes.
        val sigOk = ed25519Verifier.verify(
            publicKey = rawServerPubkey,
            message = envelope.canonicalPayloadBytes,
            signature = envelope.signatureBytes,
        )
        if (!sigOk) {
            throw AnswerVerificationException("Inner Ed25519 signature verification failed")
        }

        // Step 4: Recompute derived_lookup_id = SHA-256(raw 32-byte Ed25519 pubkey).
        val identity = ServerIdentity.fromEd25519PublicKey(rawServerPubkey)
        if (!CryptoUtils.constantTimeEquals(identity.lookupId, payload.lookupId.lowercase())) {
            throw AnswerVerificationException("Cross-server replay rejected: derived lookup_id mismatch")
        }

        // Optional sanity check on payload.fingerprint if present.
        if (payload.fingerprint != null &&
            !CryptoUtils.constantTimeEquals(identity.fingerprint, payload.fingerprint.lowercase())
        ) {
            throw AnswerVerificationException("Payload fingerprint does not match derived lookup_id")
        }

        // Step 5: Assert payload.code_hash == requestedCodeHash (constant-time).
        if (!CryptoUtils.constantTimeEquals(payload.codeHash.lowercase(), requestedCodeHash.lowercase())) {
            throw AnswerVerificationException("Cross-code replay rejected: code_hash mismatch")
        }

        // Step 6: Extract verified answer.
        val certFp = payload.certFingerprintSha256.lowercase()
        if (certFp.length != 64 || !certFp.all { it in '0'..'9' || it in 'a'..'f' }) {
            throw AnswerVerificationException("Invalid cert_fingerprint SHA-256 hex")
        }
        if (payload.addresses.isEmpty()) {
            throw AnswerVerificationException("Sealed answer contains no candidate addresses")
        }

        return VerifiedServerAnswer(
            identity = identity,
            candidateAddresses = payload.addresses.map { CandidateAddress(url = it) },
            certFingerprintSha256 = certFp,
            serverName = payload.serverName,
        )
    }
}
