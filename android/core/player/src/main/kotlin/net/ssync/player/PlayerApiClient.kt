package net.ssync.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Full 1:1 SoulSync `/player` API client covering Movies, TV, YouTube, Music,
 * Podcasts, Audiobooks, Guide, Watchlist, Presence (Who's Watching), Progress,
 * and Player Settings over the cert-pinned, Bearer-authenticated OkHttpClient.
 */
class PlayerApiClient(
    val baseUrl: String,
    val pinnedAuthenticatedClient: OkHttpClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    },
) {
    private val base = baseUrl.trimEnd('/')
    val artwork = PlayerArtwork(base)

    fun streamUrl(itemType: String, itemId: String): String =
        "$base/api/player/stream/$itemType/$itemId"

    // ── Home & Continue Watching ─────────────────────────────────────────────

    suspend fun fetchContinue(limit: Int = 30): MusicApiResult<List<ProgressRow>> =
        getJson("$base/api/player/continue?limit=$limit") { body ->
            json.decodeFromString<ContinueResponse>(body).items
        }

    suspend fun fetchVideoCatalog(): MusicApiResult<VideoCatalog> =
        getJson("$base/api/player/video-catalog") { body ->
            val root = json.parseToJsonElement(body).jsonObject
            VideoCatalogDecoder.decode(root)
        }

    // ── Movies & TV Detail ───────────────────────────────────────────────────

    suspend fun fetchMovieDetail(movieId: Int): MusicApiResult<MovieDetail> =
        getJson("$base/api/video/detail/movie/$movieId") { body ->
            json.decodeFromString<MovieDetail>(body)
        }

    suspend fun fetchShowDetail(showId: Int): MusicApiResult<ShowDetail> =
        getJson("$base/api/video/detail/show/$showId") { body ->
            json.decodeFromString<ShowDetail>(body)
        }

    // ── Music (Library v2 + Playlists + Lyrics) ──────────────────────────────

    suspend fun fetchArtists(search: String = "", limit: Int = 80): MusicApiResult<List<LibArtist>> {
        val url = "$base/api/library/v2/artists".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("sort", "name")
            .apply { if (search.isNotBlank()) addQueryParameter("search", search.trim()) }
            .build()
        return getJson(url.toString()) { body ->
            json.decodeFromString<ArtistsEnvelope>(body).artists
        }
    }

    suspend fun fetchArtistDetail(artistId: Int): MusicApiResult<LibArtistDetail> =
        getJson("$base/api/library/v2/artists/$artistId") { body ->
            requireNotNull(json.decodeFromString<ArtistDetailEnvelope>(body).artist) {
                "Artist not found"
            }
        }

    suspend fun fetchAlbums(search: String = "", limit: Int = 80): MusicApiResult<List<LibAlbum>> {
        val url = "$base/api/library/v2/albums".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("sort", "added")
            .apply { if (search.isNotBlank()) addQueryParameter("search", search.trim()) }
            .build()
        return getJson(url.toString()) { body ->
            json.decodeFromString<AlbumsEnvelope>(body).albums
        }
    }

    suspend fun fetchAlbumDetail(albumId: Int): MusicApiResult<LibAlbumDetail> =
        getJson("$base/api/library/v2/albums/$albumId") { body ->
            requireNotNull(json.decodeFromString<AlbumDetailEnvelope>(body).album) {
                "Album not found"
            }
        }

    suspend fun fetchSongs(search: String = "", limit: Int = 100): MusicApiResult<List<LibSong>> {
        val url = "$base/api/library/v2/songs".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("sort", "title")
            .apply { if (search.isNotBlank()) addQueryParameter("search", search.trim()) }
            .build()
        return getJson(url.toString()) { body ->
            json.decodeFromString<SongsEnvelope>(body).songs
        }
    }

    suspend fun fetchPlaylists(): MusicApiResult<List<MusicPlaylist>> =
        getJson("$base/api/player/playlists") { body ->
            val env = json.decodeFromString<PlayerPlaylistsEnvelope>(body)
            env.yours + env.mirrored
        }

    suspend fun fetchPlaylistDetail(playlistId: Int): MusicApiResult<PlaylistDetail> =
        getJson("$base/api/player/playlists/$playlistId") { body ->
            requireNotNull(json.decodeFromString<PlaylistDetailEnvelope>(body).playlist) {
                "Playlist not found"
            }
        }

    suspend fun fetchTrackLyrics(trackId: String): MusicApiResult<String?> =
        getJson("$base/api/player/lyrics/track/$trackId") { body ->
            val parsed = json.decodeFromString<LyricsResponse>(body)
            parsed.synced ?: parsed.plain ?: parsed.lyrics
        }

    // ── YouTube, Podcasts & Audiobooks ───────────────────────────────────────

    suspend fun fetchYoutubeOverview(): MusicApiResult<YtOverviewResponse> =
        getJson("$base/api/player/youtube") { body ->
            json.decodeFromString<YtOverviewResponse>(body)
        }

    suspend fun fetchYoutubeChannel(channelId: String): MusicApiResult<YtChannelDetailResponse> =
        getJson("$base/api/player/youtube/channel/$channelId") { body ->
            json.decodeFromString<YtChannelDetailResponse>(body)
        }

    suspend fun fetchPodcastSubs(): MusicApiResult<List<PodcastSub>> =
        getJson("$base/api/podcasts/watchlist") { body ->
            json.decodeFromString<PodcastsWatchlistEnvelope>(body).podcasts
        }

    suspend fun fetchPodcastShow(feedUrl: String): MusicApiResult<PodcastShow> {
        val url = "$base/api/podcasts/show".toHttpUrl().newBuilder()
            .addQueryParameter("url", feedUrl)
            .build()
        return getJson(url.toString()) { body ->
            json.decodeFromString<PodcastShow>(body)
        }
    }

    suspend fun fetchAudiobooks(): MusicApiResult<List<OwnedBook>> =
        getJson("$base/api/audiobooks/library") { body ->
            json.decodeFromString<AudiobooksLibraryEnvelope>(body).books
        }

    // ── Guide, Watchlist, Presence & Settings ────────────────────────────────

    suspend fun fetchGuideChannels(): MusicApiResult<List<GuideChannel>> =
        getJson("$base/api/player/guide/channels") { body ->
            val root = json.parseToJsonElement(body).jsonObject
            val arr = (root["channels"] as? JsonArray) ?: JsonArray(emptyList())
            arr.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.content ?: id
                GuideChannel(
                    id = id,
                    name = name,
                    number = obj["number"]?.jsonPrimitive?.intOrNull,
                    kind = obj["kind"]?.jsonPrimitive?.content ?: "video",
                    description = obj["description"]?.jsonPrimitive?.content,
                    currentTitle = obj["current_title"]?.jsonPrimitive?.content,
                    currentItemType = obj["current_item_type"]?.jsonPrimitive?.content,
                    currentItemId = obj["current_item_id"]?.jsonPrimitive?.content,
                    nextTitle = obj["next_title"]?.jsonPrimitive?.content,
                )
            }
        }

    suspend fun fetchMusicChannels(): MusicApiResult<List<MusicChannel>> =
        getJson("$base/api/player/music-channels") { body ->
            json.decodeFromString<MusicChannelsEnvelope>(body).channels
        }

    suspend fun fetchGuideFavorites(): MusicApiResult<Set<String>> =
        getJson("$base/api/player/guide/favorites") { body ->
            json.decodeFromString<GuideFavoritesEnvelope>(body).channels.toSet()
        }

    suspend fun fetchWatchlist(): MusicApiResult<List<WatchlistItem>> =
        getJson("$base/api/player/watchlist") { body ->
            json.decodeFromString<WatchlistEnvelope>(body).items
        }

    suspend fun addToWatchlist(
        itemType: String,
        itemId: String,
        meta: ProgressMeta? = null,
    ): MusicApiResult<Unit> = withContext(Dispatchers.IO) {
        val payload = WatchlistPutRequest(itemType, itemId, meta)
        val req = Request.Builder()
            .url("$base/api/player/watchlist")
            .put(json.encodeToString(WatchlistPutRequest.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(req) { }
    }

    suspend fun removeFromWatchlist(itemType: String, itemId: String): MusicApiResult<Unit> =
        withContext(Dispatchers.IO) {
            val url = "$base/api/player/watchlist".toHttpUrl().newBuilder()
                .addQueryParameter("item_type", itemType)
                .addQueryParameter("item_id", itemId)
                .build()
            val req = Request.Builder().url(url).delete().build()
            execute(req) { }
        }

    suspend fun fetchPresence(): MusicApiResult<PresenceResponse> =
        getJson("$base/api/player/presence") { body ->
            json.decodeFromString<PresenceResponse>(body)
        }

    suspend fun fetchWatchTogether(): MusicApiResult<List<TogetherTitle>> =
        getJson("$base/api/player/presence/together") { body ->
            json.decodeFromString<TogetherEnvelope>(body).titles
        }

    suspend fun sendPresence(
        itemType: String,
        itemId: String,
        title: String,
        subtitle: String? = null,
        artwork: String? = null,
        positionSeconds: Double = 0.0,
        durationSeconds: Double? = null,
        paused: Boolean = false,
    ): MusicApiResult<Unit> = withContext(Dispatchers.IO) {
        val payload = PresenceHeartbeatRequest(
            itemType = itemType,
            itemId = itemId,
            title = title,
            subtitle = subtitle,
            artwork = artwork,
            positionSeconds = positionSeconds,
            durationSeconds = durationSeconds,
            paused = paused,
        )
        val req = Request.Builder()
            .url("$base/api/player/presence")
            .post(json.encodeToString(PresenceHeartbeatRequest.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(req) { }
    }

    suspend fun clearPresence(): MusicApiResult<Unit> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/api/player/presence").delete().build()
        execute(req) { }
    }

    suspend fun saveProgress(
        itemType: String,
        itemId: String,
        positionSeconds: Double,
        durationSeconds: Double? = null,
        finished: Boolean = false,
    ): MusicApiResult<Unit> = withContext(Dispatchers.IO) {
        val payload = PlaybackProgressRequest(
            itemType = itemType,
            itemId = itemId,
            positionSeconds = positionSeconds,
            durationSeconds = durationSeconds,
            finished = finished,
        )
        val req = Request.Builder()
            .url("$base/api/player/progress")
            .put(json.encodeToString(PlaybackProgressRequest.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(req) { }
    }

    suspend fun fetchSettings(): MusicApiResult<PlayerSettings> =
        getJson("$base/api/player/settings") { body ->
            json.decodeFromString<PlayerSettingsEnvelope>(body).settings
        }

    suspend fun saveSettings(settings: PlayerSettings): MusicApiResult<PlayerSettings> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$base/api/player/settings")
                .put(json.encodeToString(PlayerSettings.serializer(), settings).toRequestBody(JSON_MEDIA_TYPE))
                .build()
            execute(req) { body ->
                json.decodeFromString<PlayerSettingsEnvelope>(body).settings
            }
        }

    // ── Home Layout (Shared / Per-Device Customizable Dashboard) ─────────────

    suspend fun fetchHomeLayout(device: String = "phone"): MusicApiResult<HomeLayoutResponse> =
        getJson("$base/api/player/home-layout?device=$device") { body ->
            json.decodeFromString<HomeLayoutResponse>(body)
        }

    suspend fun saveHomeLayout(
        layout: HomeLayout,
        device: String = "phone",
        shared: Boolean = true,
    ): MusicApiResult<HomeLayoutResponse> = withContext(Dispatchers.IO) {
        val payload = HomeLayoutPutRequest(layout = layout, device = if (shared) null else device, shared = shared)
        val req = Request.Builder()
            .url("$base/api/player/home-layout")
            .put(json.encodeToString(HomeLayoutPutRequest.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(req) { body ->
            runCatching { json.decodeFromString<HomeLayoutResponse>(body) }
                .getOrDefault(HomeLayoutResponse(layout = layout, shared = shared))
        }
    }

    suspend fun resetHomeLayout(device: String = "phone"): MusicApiResult<Unit> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url("$base/api/player/home-layout?device=$device").delete().build()
            execute(req) { }
        }

    // ── Extras, Person, Notes, Guide Favorites & Discover Playlists ──────────

    suspend fun fetchTitleExtras(kind: String, id: Int): MusicApiResult<TitleExtras> =
        getJson("$base/api/video/detail/$kind/$id/extras") { body ->
            json.decodeFromString<TitleExtras>(body)
        }

    suspend fun fetchPerson(tmdbId: Int): MusicApiResult<PersonDetail> =
        getJson("$base/api/video/person/$tmdbId") { body ->
            json.decodeFromString<PersonDetail>(body)
        }

    suspend fun fetchNotes(itemType: String, itemId: String): MusicApiResult<List<PlayerNote>> =
        getJson("$base/api/player/notes/$itemType/$itemId") { body ->
            json.decodeFromString<NotesEnvelope>(body).notes
        }

    suspend fun addNote(itemType: String, itemId: String, text: String): MusicApiResult<Unit> =
        withContext(Dispatchers.IO) {
            val payload = NoteCreateRequest(body = text)
            val req = Request.Builder()
                .url("$base/api/player/notes/$itemType/$itemId")
                .post(json.encodeToString(NoteCreateRequest.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
                .build()
            execute(req) { }
        }

    suspend fun deleteNote(noteId: Int): MusicApiResult<Unit> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/api/player/note/$noteId").delete().build()
        execute(req) { }
    }

    suspend fun saveGuideFavorites(channels: List<String>): MusicApiResult<Unit> =
        withContext(Dispatchers.IO) {
            val payload = GuideFavoritesPutRequest(channels = channels)
            val req = Request.Builder()
                .url("$base/api/player/guide/favorites")
                .put(json.encodeToString(GuideFavoritesPutRequest.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
                .build()
            execute(req) { }
        }

    suspend fun fetchDiscoverPlaylists(): MusicApiResult<List<DiscoverPlaylistSummary>> =
        getJson("$base/api/player/discover-playlists") { body ->
            json.decodeFromString<DiscoverPlaylistsEnvelope>(body).playlists
        }

    suspend fun removeProgress(itemType: String, itemId: String): MusicApiResult<Unit> =
        withContext(Dispatchers.IO) {
            val url = "$base/api/player/progress".toHttpUrl().newBuilder()
                .addQueryParameter("item_type", itemType)
                .addQueryParameter("item_id", itemId)
                .build()
            execute(Request.Builder().url(url).delete().build()) { }
        }

    suspend fun fetchLogos(items: List<String>): MusicApiResult<Map<String, String>> {
        if (items.isEmpty()) return MusicApiResult.Success(emptyMap())
        val url = "$base/api/player/logos".toHttpUrl().newBuilder()
            .addQueryParameter("items", items.take(12).joinToString(","))
            .build()
        return getJson(url.toString()) { body ->
            val root = json.parseToJsonElement(body).jsonObject
            val map = (root["logos"] as? JsonObject) ?: return@getJson emptyMap()
            map.mapNotNull { (k, v) ->
                val p = v as? kotlinx.serialization.json.JsonPrimitive ?: return@mapNotNull null
                if (p is kotlinx.serialization.json.JsonNull) return@mapNotNull null
                p.content.takeIf { it.isNotBlank() }?.let { k to it }
            }.toMap()
        }
    }

    suspend fun fetchDeviceProfiles(): MusicApiResult<List<DeviceProfileInfo>> =
        getJson("$base/api/devices/me/profiles") { body ->
            val root = json.parseToJsonElement(body).jsonObject
            val arr = (root["profiles"] as? kotlinx.serialization.json.JsonArray) ?: return@getJson emptyList()
            arr.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val id = (obj["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                    ?: return@mapNotNull null
                val name = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: return@mapNotNull null
                val avatarColor = (obj["avatar_color"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
                val avatarUrl = (obj["avatar_url"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
                val isAdmin = (obj["is_admin"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content?.toBooleanStrictOrNull() ?: false
                DeviceProfileInfo(id = id, name = name, avatarColor = avatarColor, avatarUrl = avatarUrl, isAdmin = isAdmin)
            }
        }

    suspend fun pickDeviceProfile(profileId: Int): MusicApiResult<Unit> =
        withContext(Dispatchers.IO) {
            val payload = """{"profile_id":$profileId}"""
            val req = Request.Builder()
                .url("$base/api/devices/me/profile")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            execute(req) { }
        }

    private suspend fun <T> getJson(url: String, parser: (String) -> T): MusicApiResult<T> =
        withContext(Dispatchers.IO) {
            execute(Request.Builder().url(url).get().build(), parser)
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
