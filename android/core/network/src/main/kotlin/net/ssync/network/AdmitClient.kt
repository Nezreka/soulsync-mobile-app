package net.ssync.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import net.ssync.connect.PairingCode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Response from `POST /api/devices/redeem` in SoulSync (`api/devices.py`).
 */
@Serializable
data class RedeemCodeResponse(
    val success: Boolean = true,
    val token: String,
    @SerialName("device_id")
    val deviceId: Long = 0L,
    @SerialName("pick_seconds")
    val pickSeconds: Long = 900L,
) {
    override fun toString(): String =
        "RedeemCodeResponse(deviceId=$deviceId, pickSeconds=$pickSeconds, token=REDACTED)"
}

/**
 * Profile item from `GET /api/devices/me/profiles` (`core/connect/pick.py:pick_list`).
 * `needs` is `"pin"`, `"password"`, or `null`.
 */
@Serializable
data class MusicProfile(
    val id: JsonPrimitive,
    val name: String,
    val needs: String? = null,
    @SerialName("avatar_color")
    val avatarColor: String? = null,
    @SerialName("avatar_url")
    val avatarUrl: String? = null,
) {
    val idString: String get() = id.content
    val idInt: Int get() = id.content.toInt()
    val requiresPin: Boolean get() = needs == "pin"
    val requiresPassword: Boolean get() = needs == "password"
}

@Serializable
data class ProfilePickListResponse(
    val success: Boolean = true,
    val profiles: List<MusicProfile> = emptyList(),
)

@Serializable
data class PickedProfileDto(
    val id: JsonPrimitive,
    val name: String,
)

@Serializable
internal data class PickProfileEnvelope(
    val success: Boolean = true,
    val profile: PickedProfileDto,
)

/**
 * Completed profile pick result carrying the upgraded permanent revocable device token.
 */
data class PickProfileResponse(
    val deviceToken: String,
    val profileId: String,
    val profileName: String,
) {
    override fun toString(): String =
        "PickProfileResponse(profileId=$profileId, profileName=$profileName, deviceToken=REDACTED)"
}

sealed interface AdmitResult<out T> {
    data class Success<T>(val data: T) : AdmitResult<T>
    data object UnprofiledTokenExpired : AdmitResult<Nothing>
    data object InvalidCredentials : AdmitResult<Nothing>
    /** [status] is the HTTP status, 0 when the server never answered */
    data class Failed(val message: String, val status: Int = 0) : AdmitResult<Nothing>
}

@Serializable
private data class RedeemRequestBody(
    val code: String,
    val name: String,
    val platform: String = "android",
    val app: String = "player",
)

@Serializable
private data class PickProfileRequestBody(
    @SerialName("profile_id")
    val profileId: Int,
    val pin: String? = null,
    val password: String? = null,
)

/**
 * Direct SoulSync Admit layer client aligned 1:1 with `api/devices.py` (DESIGN-CONNECT v0 Section 7).
 *
 * All calls go directly to the paired SoulSync server over cert-pinned HTTPS:
 * 1. `POST /api/devices/redeem`: presents raw pairing code -> mints 15-min unprofiled token.
 * 2. `GET /api/devices/me/profiles`: returns profiles ALREADY filtered server-side by `"Use music apps"`
 *    (kid profiles excluded; client never filters).
 * 3. `POST /api/devices/me/profile`: completes profile pick (with PIN or password if `needs` requires it)
 *    -> upgrades the token in-place to a permanent per-device revocable token.
 */
class AdmitClient(
    private val baseUrl: String,
    private val pinnedClient: OkHttpClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    suspend fun redeemCode(
        pairingCode: PairingCode,
        deviceName: String,
    ): AdmitResult<RedeemCodeResponse> = withContext(Dispatchers.IO) {
        val payload = RedeemRequestBody(
            code = pairingCode.rawForDirectRedemption(),
            name = deviceName,
        )
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/devices/redeem")
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
            .url("${baseUrl.trimEnd('/')}/api/devices/me/profiles")
            .header("Authorization", "Bearer $unprofiledToken")
            .get()
            .build()

        executeAdmitCall(request) { body ->
            json.decodeFromString<ProfilePickListResponse>(body).profiles
        }
    }

    suspend fun pickProfile(
        unprofiledToken: String,
        profileId: Int,
        pin: String? = null,
        password: String? = null,
    ): AdmitResult<PickProfileResponse> = withContext(Dispatchers.IO) {
        val payload = PickProfileRequestBody(
            profileId = profileId,
            pin = pin,
            password = password,
        )
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/devices/me/profile")
            .header("Authorization", "Bearer $unprofiledToken")
            .post(json.encodeToString(PickProfileRequestBody.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        executeAdmitCall(request) { body ->
            val env = json.decodeFromString<PickProfileEnvelope>(body)
            PickProfileResponse(
                deviceToken = unprofiledToken,
                profileId = env.profile.id.content,
                profileName = env.profile.name,
            )
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
                    response.code == 409 && body.contains("device_pick_expired") ->
                        AdmitResult.UnprofiledTokenExpired
                    response.code == 403 -> AdmitResult.InvalidCredentials
                    else -> AdmitResult.Failed("Admit request failed (HTTP ${response.code})", response.code)
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
