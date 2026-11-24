package net.ssync.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Direct SoulSync music API client aligned with SoulSync server routes:
 * - `GET /api/player/stream/track/{id}` (Direct Play audio stream)
 * - `GET /api/library/tracks/recent?limit=...`
 * - `GET /api/library/tracks?q=...&limit=...`
 * - `GET /api/player/playlists` (`yours` + `mirrored`)
 * - `PUT /api/player/progress`
 *
 * Strictly talks ONLY to the paired SoulSync server (`baseUrl`) over the cert-pinned,
 * device-token-authenticated [OkHttpClient].
 * Zero dependency on `connect.ssync.net`, MetaSync, or SIN.
 */
class MusicApiClient(
    private val baseUrl: String,
    private val pinnedAuthenticatedClient: OkHttpClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    private val normalizedBaseUrl = baseUrl.trimEnd('/')

    fun trackStreamUrl(trackId: String): String =
        "$normalizedBaseUrl/api/player/stream/track/$trackId"

    suspend fun fetchRecentTracks(limit: Int = 50): MusicApiResult<List<MusicTrack>> {
        val url = "$normalizedBaseUrl/api/library/tracks/recent".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            .build()
        return getJson(url.toString()) { body ->
            json.decodeFromString<LibraryTracksEnvelope>(body)
                .data
                ?.tracks
                .orEmpty()
                .map { it.toDomain() }
        }
    }

    suspend fun searchLibraryTracks(query: String, limit: Int = 50): MusicApiResult<List<MusicTrack>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return fetchRecentTracks(limit = limit)
        }
        val url = "$normalizedBaseUrl/api/library/tracks".toHttpUrl().newBuilder()
            .addQueryParameter("q", trimmed)
            .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            .build()
        return getJson(url.toString()) { body ->
            json.decodeFromString<LibraryTracksEnvelope>(body)
                .data
                ?.tracks
                .orEmpty()
                .map { it.toDomain() }
        }
    }

    suspend fun fetchPlaylists(): MusicApiResult<List<MusicPlaylist>> =
        getJson("$normalizedBaseUrl/api/player/playlists") { body ->
            val env = json.decodeFromString<PlayerPlaylistsEnvelope>(body)
            env.yours + env.mirrored
        }

    suspend fun reportProgress(
        trackId: String,
        positionMs: Long,
        durationMs: Long? = null,
        finished: Boolean = false,
    ): MusicApiResult<Unit> = withContext(Dispatchers.IO) {
        val payload = PlaybackProgressRequest(
            itemType = "track",
            itemId = trackId,
            positionSeconds = positionMs / 1000.0,
            durationSeconds = durationMs?.let { it / 1000.0 },
            finished = finished,
        )
        val request = Request.Builder()
            .url("$normalizedBaseUrl/api/player/progress")
            .put(
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
                    response.code == 401 && net.ssync.network.isRevocation(body) -> MusicApiResult.DeviceRevoked
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
