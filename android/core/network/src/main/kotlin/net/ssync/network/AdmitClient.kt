package net.ssync.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.ssync.connect.PairingCode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class RedeemCodeResponse(
    @SerialName("device_token")
    val deviceToken: String,
    @SerialName("expires_in_seconds")
    val expiresInSeconds: Long = 900,
) {
    override fun toString(): String = "RedeemCodeResponse(deviceToken=REDACTED, expiresInSeconds=$expiresInSeconds)"
}

@Serializable
data class MusicProfile(
    val id: String,
    val name: String,
    @SerialName("requires_pin")
    val requiresPin: Boolean = false,
    @SerialName("requires_password")
    val requiresPassword: Boolean = false,
    @SerialName("avatar_url")
    val avatarUrl: String? = null,
)

@Serializable
data class ProfilePickListResponse(
    val profiles: List<MusicProfile>,
)

@Serializable
data class PickProfileResponse(
    @SerialName("device_token")
    val deviceToken: String,
    @SerialName("profile_id")
    val profileId: String,
    @SerialName("profile_name")
    val profileName: String,
) {
    override fun toString(): String =
        "PickProfileResponse(profileId=$profileId, profileName=$profileName, deviceToken=REDACTED)"
}

sealed interface AdmitResult<out T> {
    data class Success<T>(val data: T) : AdmitResult<T>
    data object UnprofiledTokenExpired : AdmitResult<Nothing>
    data object InvalidCredentials : AdmitResult<Nothing>
    data class Failed(val message: String) : AdmitResult<Nothing>
}

@Serializable
private data class RedeemRequestBody(
    val code: String,
    @SerialName("device_name")
    val deviceName: String,
    val platform: String = "android",
)

@Serializable
private data class PickProfileRequestBody(
    @SerialName("profile_id")
    val profileId: String,
    val pin: String? = null,
    val password: String? = null,
)

/**
 * Direct SoulSync Admit layer client (DESIGN-CONNECT v0 Section 7).
 *
 * All calls go directly to the paired SoulSync server over cert-pinned HTTPS:
 * 1. [redeemCode]: presents the raw pairing code directly to SoulSync -> mints 15-min unprofiled token.
 * 2. [fetchProfilePickList]: returns profiles ALREADY filtered server-side by `"Use music apps"`
 *    (kid profiles excluded; client never filters).
 * 3. [pickProfile]: completes profile pick (with PIN or password if required) -> upgrades to
 *    permanent per-device revocable token.
 */
class AdmitClient(
    private val baseUrl: String,
    private val pinnedClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun redeemCode(
        pairingCode: PairingCode,
        deviceName: String,
    ): AdmitResult<RedeemCodeResponse> = withContext(Dispatchers.IO) {
        val payload = RedeemRequestBody(
            code = pairingCode.rawForDirectRedemption(),
            deviceName = deviceName,
        )
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/admit/redeem")
            .post(json.encodeToString(RedeemRequestBody.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        executeAdmitCall(request) { body ->
            json.decodeFromString<RedeemCodeResponse>(body)
        }
    }

    suspend fun fetchProfilePickList(
        unprofiledToken: String,
    ): AdmitResult<List<MusicProfile>> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/admit/profiles?app=music")
            .header("Authorization", "Bearer $unprofiledToken")
            .get()
            .build()

        executeAdmitCall(request) { body ->
            json.decodeFromString<ProfilePickListResponse>(body).profiles
        }
    }

    suspend fun pickProfile(
        unprofiledToken: String,
        profileId: String,
        pin: String? = null,
        password: String? = null,
    ): AdmitResult<PickProfileResponse> = withContext(Dispatchers.IO) {
        val payload = PickProfileRequestBody(
            profileId = profileId,
            pin = pin,
            password = password,
        )
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/admit/pick")
            .header("Authorization", "Bearer $unprofiledToken")
            .post(json.encodeToString(PickProfileRequestBody.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        executeAdmitCall(request) { body ->
            json.decodeFromString<PickProfileResponse>(body)
        }
    }

    private fun <T> executeAdmitCall(
        request: Request,
        parser: (String) -> T,
    ): AdmitResult<T> {
        return try {
            pinnedClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> AdmitResult.Success(parser(body))
                    response.code == 401 -> AdmitResult.UnprofiledTokenExpired
                    response.code == 403 -> AdmitResult.InvalidCredentials
                    else -> AdmitResult.Failed("Admit request failed (HTTP ${response.code})")
                }
            }
        } catch (e: Exception) {
            AdmitResult.Failed(e.message ?: "Network error during Admit flow")
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
