package net.ssync.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Direct SoulSync music API client.
 *
 * Strictly talks ONLY to the paired SoulSync server (`baseUrl`) over the cert-pinned,
 * device-token-authenticated [OkHttpClient].
 * Zero dependency on `connect.ssync.net`, MetaSync, or SIN.
 */
class MusicApiClient(
    private val baseUrl: String,
    private val pinnedAuthenticatedClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val normalizedBaseUrl = baseUrl.trimEnd('/')

    fun trackStreamUrl(trackId: String): String =
        "$normalizedBaseUrl/api/player/stream/track/$trackId"

    suspend fun fetchRecentTracks(): MusicApiResult<List<MusicTrack>> =
        getJson("$normalizedBaseUrl/api/player/music/recent") { body ->
            json.decodeFromString<TrackListResponse>(body).tracks
        }

    suspend fun fetchLibraryTracks(): MusicApiResult<List<MusicTrack>> =
        getJson("$normalizedBaseUrl/api/player/music/library") { body ->
            json.decodeFromString<TrackListResponse>(body).tracks
        }

    suspend fun fetchPlaylists(): MusicApiResult<List<MusicPlaylist>> =
        getJson("$normalizedBaseUrl/api/player/music/playlists") { body ->
            json.decodeFromString<PlaylistListResponse>(body).playlists
        }

    suspend fun reportProgress(
        trackId: String,
        positionMs: Long,
        completed: Boolean = false,
    ): MusicApiResult<Unit> = withContext(Dispatchers.IO) {
        val payload = PlaybackProgressRequest(
            trackId = trackId,
            positionMs = positionMs,
            completed = completed,
        )
        val request = Request.Builder()
            .url("$normalizedBaseUrl/api/player/progress")
            .post(
                json.encodeToString(PlaybackProgressRequest.serializer(), payload)
                    .toRequestBody(JSON_MEDIA_TYPE),
            )
            .build()
        execute(request) { }
    }

    private suspend fun <T> getJson(url: String, parser: (String) -> T): MusicApiResult<T> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).get().build()
            execute(request, parser)
        }

    private fun <T> execute(request: Request, parser: (String) -> T): MusicApiResult<T> {
        return try {
            pinnedAuthenticatedClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> MusicApiResult.Success(parser(body))
                    response.code == 401 -> MusicApiResult.DeviceRevoked
                    else -> MusicApiResult.Error("Server returned HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            MusicApiResult.Error(e.message ?: "Unable to reach SoulSync server")
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
