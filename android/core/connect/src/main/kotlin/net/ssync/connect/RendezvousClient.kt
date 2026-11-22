package net.ssync.connect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface LookupOutcome {
    data class Verified(val answer: VerifiedServerAnswer) : LookupOutcome

    /**
     * Collapsed honest failure message (DESIGN-CONNECT v0 Section 3: no existence oracle).
     */
    data class Unreachable(
        val userMessage: String = DEFAULT_UNREACHABLE_MESSAGE,
    ) : LookupOutcome

    data class RateLimited(
        val retryAfterSeconds: Long,
        val userMessage: String = DEFAULT_UNREACHABLE_MESSAGE,
    ) : LookupOutcome

    data class VersionMismatch(
        val userMessage: String = "Please update the SoulSync app to pair with this server.",
    ) : LookupOutcome

    companion object {
        const val DEFAULT_UNREACHABLE_MESSAGE = "Couldn't reach that server with this code."
    }
}

@Serializable
private data class LookupRequestBody(
    @SerialName("code_hash")
    val codeHash: String,
    @SerialName("ephemeral_public_key")
    val ephemeralPublicKey: String,
)

@Serializable
private data class LookupResponseBody(
    @SerialName("sealed_answer")
    val sealedAnswer: String? = null,
    val sealed: String? = null,
)

/**
 * Blind rendezvous client for `connect.ssync.net` (or `staging.connect.ssync.net`).
 *
 * Security invariants:
 * - Sends ONLY `code_hash` + ephemeral X25519 public key; NEVER sends the raw pairing code.
 * - Lookups never burn a code.
 * - Collapses all unknown / expired / offline / verification failures into [LookupOutcome.Unreachable]
 *   with a single honest message (no existence oracle).
 */
class RendezvousClient(
    private val baseUrl: String = PROD_BASE_URL,
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val verifier: RendezvousAnswerVerifier = RendezvousAnswerVerifier(),
    private val keyPairFactory: () -> EphemeralX25519KeyPair = { EphemeralX25519KeyPair.generate() },
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun getStatus(): Result<StatusResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/v1/status")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("Status check failed with HTTP ${response.code}")
                }
                json.decodeFromString<StatusResponse>(body)
            }
        }
    }

    suspend fun lookup(pairingCode: PairingCode): LookupOutcome = withContext(Dispatchers.IO) {
        val ephemeral = keyPairFactory()
        val requestPayload = LookupRequestBody(
            codeHash = pairingCode.codeHash,
            ephemeralPublicKey = CryptoUtils.base64UrlEncodeNoPadding(ephemeral.publicKey),
        )
        val requestJson = json.encodeToString(LookupRequestBody.serializer(), requestPayload)
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/rendezvous/lookup")
            .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext mapErrorResponse(bodyString)
                }
                val parsed = json.decodeFromString<LookupResponseBody>(bodyString)
                val sealedEncoded = parsed.sealedAnswer ?: parsed.sealed
                    ?: return@withContext LookupOutcome.Unreachable()
                val sealedBytes = decodeSealedBytes(sealedEncoded)
                    ?: return@withContext LookupOutcome.Unreachable()

                val verified = verifier.verifyAndUnseal(
                    ephemeralPrivateKey = ephemeral.privateKey,
                    sealedBytes = sealedBytes,
                    requestedCodeHash = pairingCode.codeHash,
                )
                LookupOutcome.Verified(verified)
            }
        } catch (_: Exception) {
            LookupOutcome.Unreachable()
        } finally {
            ephemeral.privateKey.fill(0)
        }
    }

    private fun mapErrorResponse(bodyString: String): LookupOutcome {
        val envelope = runCatching {
            json.decodeFromString<ConnectErrorEnvelope>(bodyString)
        }.getOrNull() ?: return LookupOutcome.Unreachable()

        return when (envelope.error.errorCode) {
            ConnectErrorCode.RATE_LIMITED -> LookupOutcome.RateLimited(
                retryAfterSeconds = envelope.error.retryAfterSeconds ?: 60L,
            )
            ConnectErrorCode.VERSION_MISMATCH -> LookupOutcome.VersionMismatch()
            else -> LookupOutcome.Unreachable()
        }
    }

    private fun decodeSealedBytes(encoded: String): ByteArray? {
        val trimmed = encoded.trim()
        // Accept lowercase/uppercase hex or unpadded base64url
        if (trimmed.length % 2 == 0 && trimmed.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return runCatching { CryptoUtils.fromHex(trimmed) }.getOrNull()
        }
        return runCatching { CryptoUtils.base64UrlDecodeNoPadding(trimmed) }.getOrNull()
    }

    companion object {
        const val PROD_BASE_URL = "https://connect.ssync.net"
        const val STAGING_BASE_URL = "https://staging.connect.ssync.net"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
