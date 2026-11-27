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
import java.io.IOException

sealed interface LookupOutcome {
    data class Verified(val answer: VerifiedServerAnswer) : LookupOutcome

    /**
     * The lookup failed. The relay itself never says whether a code exists
     * (DESIGN-CONNECT v0 Section 3: no existence oracle), but what failed
     * (the phone's internet, the relay, the answer) is said plainly (#2328).
     */
    data class Unreachable(
        val userMessage: String = DEFAULT_UNREACHABLE_MESSAGE,
        val reason: Reason = Reason.OTHER,
    ) : LookupOutcome

    enum class Reason {
        /** 404 unknown_or_expired_code: unknown, expired, or the server isn't connected */
        UNKNOWN_CODE,

        /** the relay answered with an error it doesn't name, or a 5xx */
        RELAY_ERROR,

        /** the phone couldn't reach the relay at all */
        NO_INTERNET,

        /** the sealed answer didn't open or verify */
        BAD_ANSWER,
        OTHER,
    }

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
    @SerialName("reply_key")
    val replyKey: String,
    @SerialName("ephemeral_public_key")
    val ephemeralPublicKey: String = replyKey,
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
 * - Never tells an unknown code from an expired one or an offline server (the relay can't either:
 *   no existence oracle); it does say whether the phone's internet, the relay or the answer failed.
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
        val pubKeyB64 = CryptoUtils.base64UrlEncodeNoPadding(ephemeral.publicKey)
        val requestPayload = LookupRequestBody(
            codeHash = pairingCode.codeHash,
            replyKey = pubKeyB64,
            ephemeralPublicKey = pubKeyB64,
        )
        val requestJson = json.encodeToString(LookupRequestBody.serializer(), requestPayload)
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/lookup")
            .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext mapErrorResponse(bodyString)
                }
                val parsed = runCatching { json.decodeFromString<LookupResponseBody>(bodyString) }.getOrNull()
                    ?: return@withContext relayError()
                val sealedEncoded = parsed.sealedAnswer ?: parsed.sealed
                    ?: return@withContext relayError()
                val sealedBytes = decodeSealedBytes(sealedEncoded)
                    ?: return@withContext badAnswer()

                val verified = try {
                    verifier.verifyAndUnseal(
                        ephemeralPrivateKey = ephemeral.privateKey,
                        sealedBytes = sealedBytes,
                        requestedCodeHash = pairingCode.codeHash,
                    )
                } catch (_: Exception) {
                    return@withContext badAnswer()
                }
                LookupOutcome.Verified(verified)
            }
        } catch (_: IOException) {
            LookupOutcome.Unreachable(PairingMessages.NO_INTERNET, LookupOutcome.Reason.NO_INTERNET)
        } catch (_: Exception) {
            LookupOutcome.Unreachable()
        } finally {
            ephemeral.privateKey.fill(0)
        }
    }

    private fun mapErrorResponse(bodyString: String): LookupOutcome {
        val envelope = runCatching {
            json.decodeFromString<ConnectErrorEnvelope>(bodyString)
        }.getOrNull() ?: return relayError()

        return when (envelope.error.errorCode) {
            ConnectErrorCode.RATE_LIMITED -> LookupOutcome.RateLimited(
                retryAfterSeconds = envelope.error.retryAfterSeconds ?: 60L,
            )
            ConnectErrorCode.VERSION_MISMATCH -> LookupOutcome.VersionMismatch()
            // what connect.ssync.net says for an unknown or expired code, and
            // for a server that isn't connected (its codes go with it)
            ConnectErrorCode.UNKNOWN_OR_EXPIRED_CODE -> LookupOutcome.Unreachable(
                PairingMessages.UNKNOWN_CODE,
                LookupOutcome.Reason.UNKNOWN_CODE,
            )
            else -> relayError()
        }
    }

    private fun relayError() =
        LookupOutcome.Unreachable(PairingMessages.RELAY_ERROR, LookupOutcome.Reason.RELAY_ERROR)

    private fun badAnswer() =
        LookupOutcome.Unreachable(PairingMessages.BAD_ANSWER, LookupOutcome.Reason.BAD_ANSWER)

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
        const val STAGING_BASE_URL = "https://soulsync-connect-staging.fly.dev"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
