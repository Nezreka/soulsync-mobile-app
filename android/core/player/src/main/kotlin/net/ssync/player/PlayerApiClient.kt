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

    @Volatile private var cachedVideoCatalog: Pair<Long, VideoCatalog>? = null
    @Volatile private var cachedMusicChannels: Pair<Long, List<MusicChannel>>? = null

    fun streamUrl(itemType: String, itemId: String): String =
        "$base/api/player/stream/$itemType/$itemId"

    // ── Home & Continue Watching ─────────────────────────────────────────────

    suspend fun fetchContinue(limit: Int = 30): MusicApiResult<List<ProgressRow>> =
        getJson("$base/api/player/continue?limit=$limit") { body ->
            json.decodeFromString<ContinueResponse>(body).items
        }

    suspend fun fetchVideoCatalog(forceRefresh: Boolean = false): MusicApiResult<VideoCatalog> {
        val now = System.currentTimeMillis()
        val hit = cachedVideoCatalog
        if (!forceRefresh && hit != null && now - hit.first < 5 * 60_000L) {
            return MusicApiResult.Success(hit.second)
        }
        val res = getJson("$base/api/player/video-catalog") { body ->
            val root = json.parseToJsonElement(body).jsonObject
            VideoCatalogDecoder.decode(root)
        }
        if (res is MusicApiResult.Success) {
            cachedVideoCatalog = now to res.data
        }
        return res
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

    suspend fun fetchArtists(search: String = "", limit: Int = 80, page: Int = 1): MusicApiResult<List<LibArtist>> {
        val url = "$base/api/library/v2/artists".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
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

    suspend fun fetchAlbums(search: String = "", limit: Int = 80, page: Int = 1): MusicApiResult<List<LibAlbum>> {
        val url = "$base/api/library/v2/albums".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
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

    suspend fun fetchSongs(search: String = "", limit: Int = 100, page: Int = 1): MusicApiResult<List<LibSong>> {
        val url = "$base/api/library/v2/songs".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
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

    suspend fun fetchSavedGuideChannels(): MusicApiResult<List<SavedGuideChannel>> =
        getJson("$base/api/player/guide/channels") { body ->
            val root = json.parseToJsonElement(body).jsonObject
            val out = mutableListOf<SavedGuideChannel>()
            fun parseScope(arrKey: String, defaultScope: String) {
                val arr = root[arrKey] as? JsonArray ?: return
                for (el in arr) {
                    val obj = el as? JsonObject ?: continue
                    val id = obj["id"]?.jsonPrimitive?.content ?: continue
                    val scope = obj["scope"]?.jsonPrimitive?.content ?: defaultScope
                    val specEl = obj["spec"] as? JsonObject ?: continue
                    val spec = runCatching {
                        json.decodeFromJsonElement(GuideChannelSpec.serializer(), specEl)
                    }.getOrNull() ?: continue
                    out += SavedGuideChannel(id = id, scope = scope, spec = spec)
                }
            }
            parseScope("server", "server")
            parseScope("mine", "mine")
            out
        }

    suspend fun fetchGuideLineup(): MusicApiResult<List<GuideLineupEntry>> =
        getJson("$base/api/player/guide/lineup") { body ->
            val root = json.parseToJsonElement(body).jsonObject
            val arr = root["entries"] as? JsonArray ?: return@getJson emptyList()
            arr.mapNotNull { el ->
                runCatching { json.decodeFromJsonElement(GuideLineupEntry.serializer(), el) }.getOrNull()
            }
        }

    suspend fun fetchMusicChannels(forceRefresh: Boolean = false): MusicApiResult<List<MusicChannel>> {
        val now = System.currentTimeMillis()
        val hit = cachedMusicChannels
        if (!forceRefresh && hit != null && now - hit.first < 10 * 60_000L) {
            return MusicApiResult.Success(hit.second)
        }
        val res = getJson("$base/api/player/music-channels") { body ->
            val root = json.parseToJsonElement(body).jsonObject
            val arr = root["channels"] as? JsonArray ?: return@getJson emptyList()
            arr.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.content ?: id
                val kind = obj["kind"]?.jsonPrimitive?.content ?: "genre"
                val count = obj["count"]?.jsonPrimitive?.intOrNull
                    ?: obj["track_count"]?.jsonPrimitive?.intOrNull
                    ?: 0
                val rawTracks = obj["tracks"] as? JsonArray ?: JsonArray(emptyList())
                val parsedItems = mutableListOf<MusicChannelTrack>()
                val parsedIds = mutableListOf<Int>()
                for (t in rawTracks) {
                    when (t) {
                        is JsonArray -> {
                            val tid = t.getOrNull(0)?.jsonPrimitive?.intOrNull ?: continue
                            val title = t.getOrNull(1)?.jsonPrimitive?.content ?: "Track"
                            val artist = t.getOrNull(2)?.jsonPrimitive?.content ?: ""
                            val secs = t.getOrNull(3)?.jsonPrimitive?.intOrNull ?: 210
                            val albumId = t.getOrNull(4)?.jsonPrimitive?.intOrNull
                            parsedIds += tid
                            parsedItems += MusicChannelTrack(tid, title, artist, secs, albumId)
                        }
                        else -> {
                            t.jsonPrimitive.intOrNull?.let { parsedIds += it }
                        }
                    }
                }
                MusicChannel(
                    id = id,
                    name = name,
                    kind = kind,
                    count = if (count > 0) count else parsedIds.size,
                    description = obj["description"]?.jsonPrimitive?.content,
                    trackCount = if (count > 0) count else parsedIds.size,
                    tracks = parsedIds,
                    trackItems = parsedItems,
                )
            }
        }
        if (res is MusicApiResult.Success) {
            cachedMusicChannels = now to res.data
        }
        return res
    }

    suspend fun fetchGuideFavorites(): MusicApiResult<Set<String>> =
        getJson("$base/api/player/guide/favorites") { body ->
            json.decodeFromString<GuideFavoritesEnvelope>(body).channels.toSet()
        }

    suspend fun fetchGuideFavoritesList(): MusicApiResult<List<String>> =
        getJson("$base/api/player/guide/favorites") { body ->
            json.decodeFromString<GuideFavoritesEnvelope>(body).channels
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

    // ── Watch Parties (Watch Together + Theater Waiting Rooms) ──────────

    private fun encodeSeg(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    suspend fun fetchWatchParties(): MusicApiResult<List<WatchParty>> =
        getJson("$base/api/player/parties") { body ->
            json.decodeFromString<WatchPartiesEnvelope>(body).parties
        }

    suspend fun fetchWatchParty(partyId: String): MusicApiResult<WatchParty> =
        getJson("$base/api/player/parties/${encodeSeg(partyId)}") { body ->
            json.decodeFromString<WatchPartyEnvelope>(body).party
        }

    suspend fun createWatchParty(
        title: String,
        itemType: String,
        itemId: String,
        meta: ProgressMeta? = null,
        showId: Int? = null,
        scheduledForSec: Long? = null,
        controlMode: String = "host",
        lobbyMusic: Boolean = true,
    ): MusicApiResult<WatchParty> = withContext(Dispatchers.IO) {
        val bodyObj = kotlinx.serialization.json.buildJsonObject {
            put("title", kotlinx.serialization.json.JsonPrimitive(title))
            put("item_type", kotlinx.serialization.json.JsonPrimitive(itemType))
            put("item_id", kotlinx.serialization.json.JsonPrimitive(itemId))
            if (meta != null) put("meta", json.encodeToJsonElement(ProgressMeta.serializer(), meta))
            if (showId != null) put("show_id", kotlinx.serialization.json.JsonPrimitive(showId))
            if (scheduledForSec != null) put("scheduled_for", kotlinx.serialization.json.JsonPrimitive(scheduledForSec))
            put("control_mode", kotlinx.serialization.json.JsonPrimitive(controlMode))
            put("lobby_music", kotlinx.serialization.json.JsonPrimitive(lobbyMusic))
        }
        val req = Request.Builder()
            .url("$base/api/player/parties")
            .post(bodyObj.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(req) { json.decodeFromString<WatchPartyEnvelope>(it).party }
    }

    suspend fun joinWatchParty(
        partyId: String,
        ready: Boolean? = null,
        rsvp: Boolean = false,
    ): MusicApiResult<WatchParty> = withContext(Dispatchers.IO) {
        val bodyObj = kotlinx.serialization.json.buildJsonObject {
            if (ready != null) put("ready", kotlinx.serialization.json.JsonPrimitive(ready))
            put("rsvp", kotlinx.serialization.json.JsonPrimitive(rsvp))
        }
        val req = Request.Builder()
            .url("$base/api/player/parties/${encodeSeg(partyId)}/join")
            .post(bodyObj.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(req) { json.decodeFromString<WatchPartyEnvelope>(it).party }
    }

    suspend fun updateWatchPartyState(
        partyId: String,
        action: String? = null,
        position: Double? = null,
        shuffleLobbyMusic: Boolean = false,
    ): MusicApiResult<WatchParty> = withContext(Dispatchers.IO) {
        val bodyObj = kotlinx.serialization.json.buildJsonObject {
            if (action != null) put("action", kotlinx.serialization.json.JsonPrimitive(action))
            if (position != null) put("position", kotlinx.serialization.json.JsonPrimitive(position))
            if (shuffleLobbyMusic) put("shuffle_lobby_music", kotlinx.serialization.json.JsonPrimitive(true))
        }
        val req = Request.Builder()
            .url("$base/api/player/parties/${encodeSeg(partyId)}/state")
            .post(bodyObj.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(req) { json.decodeFromString<WatchPartyEnvelope>(it).party }
    }

    suspend fun postWatchPartyChat(
        partyId: String,
        kind: String,
        bodyText: String,
    ): MusicApiResult<WatchPartyMessage> = withContext(Dispatchers.IO) {
        val bodyObj = kotlinx.serialization.json.buildJsonObject {
            put("kind", kotlinx.serialization.json.JsonPrimitive(kind))
            put("body", kotlinx.serialization.json.JsonPrimitive(bodyText))
        }
        val req = Request.Builder()
            .url("$base/api/player/parties/${encodeSeg(partyId)}/chat")
            .post(bodyObj.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(req) { json.decodeFromString<WatchPartyMessageEnvelope>(it).message }
    }

    suspend fun deleteWatchParty(partyId: String): MusicApiResult<Unit> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$base/api/player/parties/${encodeSeg(partyId)}")
            .delete()
            .build()
        execute(req) { }
    }

    private val presenceSessionId: String = "android-" + java.util.UUID.randomUUID().toString().take(12)

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
            session = presenceSessionId,
            itemType = itemType,
            itemId = itemId,
            position = positionSeconds.coerceAtLeast(0.0),
            duration = durationSeconds?.takeIf { it > 0.0 },
            playing = !paused,
            live = false,
            meta = ProgressMeta(
                title = title.takeIf { it.isNotBlank() },
                subtitle = subtitle?.takeIf { it.isNotBlank() },
                artwork = artwork?.takeIf { it.isNotBlank() },
                backdrop = artwork?.takeIf { it.isNotBlank() },
            ),
        )
        val req = Request.Builder()
            .url("$base/api/player/presence")
            .post(json.encodeToString(PresenceHeartbeatRequest.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(req) { }
    }

    suspend fun clearPresence(): MusicApiResult<Unit> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/api/player/presence?session=${encodeSeg(presenceSessionId)}").delete().build()
        execute(req) { }
    }

    suspend fun saveProgress(
        itemType: String,
        itemId: String,
        positionSeconds: Double,
        durationSeconds: Double? = null,
        finished: Boolean = false,
        meta: ProgressMeta? = null,
    ): MusicApiResult<Unit> = withContext(Dispatchers.IO) {
        val payload = PlaybackProgressRequest(
            itemType = itemType,
            itemId = itemId,
            positionSeconds = positionSeconds,
            durationSeconds = durationSeconds,
            finished = finished,
            meta = meta,
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

    suspend fun fetchHomeLayout(device: String = "android-phone"): MusicApiResult<HomeLayoutResponse> =
        getJson("$base/api/player/home-layout?device=$device") { body ->
            json.decodeFromString<HomeLayoutResponse>(body)
        }

    suspend fun saveHomeLayout(
        layout: HomeLayout,
        device: String = "android-phone",
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

    suspend fun fetchCollections(): MusicApiResult<List<StudioCollection>> =
        getJson("$base/api/player/collections") { body ->
            json.decodeFromString<StudioCollectionsEnvelope>(body).collections
        }

    suspend fun fetchEpisodeExtra(tmdbId: Int, season: Int, episode: Int): MusicApiResult<EpisodeExtra> =
        getJson("$base/api/video/episode/$tmdbId/$season/$episode") { body ->
            json.decodeFromString<EpisodeExtra>(body)
        }

    suspend fun submitVideoIssue(
        entityType: String,
        entityId: Int,
        category: String,
        title: String,
        description: String? = null,
    ): MusicApiResult<Unit> = withContext(Dispatchers.IO) {
        val payload = VideoIssueCreateRequest(
            entityType = entityType,
            entityId = entityId,
            category = category,
            title = title,
            description = description?.trim()?.takeIf { it.isNotEmpty() },
        )
        val req = Request.Builder()
            .url("$base/api/video/issues")
            .post(json.encodeToString(VideoIssueCreateRequest.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(req) { body ->
            val resp = runCatching { json.decodeFromString<VideoIssueResponse>(body) }.getOrNull()
            if (resp != null && !resp.success) {
                throw IllegalStateException(resp.error ?: "Could not submit issue")
            }
        }
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

    suspend fun fetchActiveDeviceProfile(): MusicApiResult<DeviceProfileInfo?> =
        getJson("$base/api/devices/me") { body ->
            val root = json.parseToJsonElement(body).jsonObject
            val prof = root["profile"] as? JsonObject ?: return@getJson null
            val id = (prof["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                ?: return@getJson null
            val name = (prof["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            val avatarColor = (prof["avatar_color"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
            val avatarUrl = (prof["avatar_url"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
            DeviceProfileInfo(id = id, name = name, avatarColor = avatarColor, avatarUrl = avatarUrl)
        }

    suspend fun fetchDeviceAddresses(): MusicApiResult<List<String>> =
        getJson("$base/api/devices/me") { body ->
            val root = json.parseToJsonElement(body).jsonObject
            val arr = (root["addresses"] as? kotlinx.serialization.json.JsonArray) ?: return@getJson emptyList()
            arr.mapNotNull { el ->
                (el as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                    ?.content
                    ?.takeIf { it.startsWith("https://") }
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

    suspend fun fetchSwitchProfiles(): MusicApiResult<List<DeviceProfileInfo>> =
        getJson("$base/api/devices/me/switch-profiles") { body ->
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
                val needs = (obj["needs"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
                val active = (obj["active"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content?.toBooleanStrictOrNull() ?: false
                DeviceProfileInfo(
                    id = id,
                    name = name,
                    avatarColor = avatarColor,
                    avatarUrl = avatarUrl,
                    needs = needs,
                    active = active,
                )
            }
        }

    suspend fun switchDeviceProfile(
        profileId: Int,
        pin: String? = null,
        password: String? = null,
    ): MusicApiResult<DeviceProfileInfo> = withContext(Dispatchers.IO) {
        val fields = buildList {
            add(""""profile_id":$profileId""")
            if (!pin.isNullOrBlank()) add(""""pin":${json.encodeToString(kotlinx.serialization.serializer<String>(), pin)}""")
            if (!password.isNullOrBlank()) add(""""password":${json.encodeToString(kotlinx.serialization.serializer<String>(), password)}""")
        }
        val req = Request.Builder()
            .url("$base/api/devices/me/switch-profile")
            .post("{${fields.joinToString(",")}}".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            pinnedAuthenticatedClient.newCall(req).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 401) return@withContext MusicApiResult.DeviceRevoked
                val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                if (response.isSuccessful && (root?.get("success") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true") {
                    val prof = root["profile"] as? JsonObject
                    val id = (prof?.get("id") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: profileId
                    val name = (prof?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                    val color = (prof?.get("avatar_color") as? kotlinx.serialization.json.JsonPrimitive)
                        ?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
                    val avatar = (prof?.get("avatar_url") as? kotlinx.serialization.json.JsonPrimitive)
                        ?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
                    MusicApiResult.Success(DeviceProfileInfo(id = id, name = name, avatarColor = color, avatarUrl = avatar, active = true))
                } else {
                    val msg = (root?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
                        ?: (root?.get("error") as? kotlinx.serialization.json.JsonPrimitive)?.content
                        ?: "Could not switch profile (HTTP ${response.code})"
                    MusicApiResult.Error(msg)
                }
            }
        } catch (e: Exception) {
            MusicApiResult.Error(e.message ?: "Network error")
        }
    }

    suspend fun createCastToken(
        itemType: String,
        itemId: String,
        part: String? = null,
    ): MusicApiResult<CastTokenResponse> = withContext(Dispatchers.IO) {
        val fields = buildList {
            add(""""item_type":${json.encodeToString(kotlinx.serialization.serializer<String>(), itemType)}""")
            add(""""item_id":${json.encodeToString(kotlinx.serialization.serializer<String>(), itemId)}""")
            if (!part.isNullOrBlank()) add(""""part":${json.encodeToString(kotlinx.serialization.serializer<String>(), part)}""")
        }
        val req = Request.Builder()
            .url("$base/api/player/cast-token")
            .post("{${fields.joinToString(",")}}".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(req) { body ->
            val root = json.parseToJsonElement(body).jsonObject
            CastTokenResponse(
                token = (root["token"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty(),
                streamPath = (root["stream_path"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty(),
                streamUrl = (root["stream_url"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" },
                artworkBaseUrl = (root["artwork_base_url"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" },
                contentType = (root["content_type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: "video/mp4",
                durationMs = (root["duration_ms"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                transcoded = (root["transcoded"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
                expiresIn = (root["expires_in"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                    ?: 21600,
            )
        }
    }

    suspend fun searchSubtitles(kind: String, itemId: Int, lang: String = "en"): MusicApiResult<List<SubtitleCandidate>> {
        val url = "$base/api/video/subtitles/search/$kind/$itemId".toHttpUrl().newBuilder()
            .addQueryParameter("lang", lang)
            .build()
        return getJson(url.toString()) { body ->
            json.decodeFromString<SubtitleSearchEnvelope>(body).candidates
        }
    }

    suspend fun downloadSubtitle(
        kind: String,
        itemId: Int,
        lang: String,
        candidateId: String,
    ): MusicApiResult<Unit> = withContext(Dispatchers.IO) {
        val payload = SubtitleDownloadRequest(
            kind = kind,
            itemId = itemId,
            lang = lang,
            candidateId = candidateId,
        )
        val req = Request.Builder()
            .url("$base/api/video/subtitles/manual-download")
            .post(json.encodeToString(SubtitleDownloadRequest.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(req) { }
    }

    suspend fun fetchVideoRequestOptions(): MusicApiResult<VideoRequestModes> =
        getJson("$base/api/video/requests/options") { body ->
            val root = json.parseToJsonElement(body).jsonObject
            val modes = root["modes"] as? JsonObject
            val movie = (modes?.get("movie") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "none"
            val show = (modes?.get("show") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "none"
            VideoRequestModes(movie = movie, show = show)
        }

    suspend fun requestVideoTitle(
        kind: String,
        tmdbId: Int,
        title: String,
        year: String? = null,
        posterUrl: String? = null,
        get: Boolean = false,
    ): MusicApiResult<VideoRequestResult> = withContext(Dispatchers.IO) {
        val payload = VideoRequestPayload(
            kind = kind,
            tmdbId = tmdbId,
            title = title,
            year = year,
            posterUrl = posterUrl,
            get = get,
        )
        val req = Request.Builder()
            .url("$base/api/video/requests")
            .post(json.encodeToString(VideoRequestPayload.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            pinnedAuthenticatedClient.newCall(req).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 401) return@withContext MusicApiResult.DeviceRevoked
                val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                val success = (root?.get("success") as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content?.toBooleanStrictOrNull() ?: false
                val got = (root?.get("got") as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content?.toBooleanStrictOrNull() ?: false
                val already = (root?.get("already") as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content?.toBooleanStrictOrNull() ?: false
                val onTheWay = (root?.get("on_the_way") as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content?.toBooleanStrictOrNull() ?: false
                val error = (root?.get("error") as? kotlinx.serialization.json.JsonPrimitive)?.content
                val message = (root?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
                val result = when {
                    success && got -> VideoRequestResult.Got
                    success -> VideoRequestResult.Asked(already = already)
                    onTheWay -> VideoRequestResult.OnTheWay
                    (error ?: "").contains("already in the library", ignoreCase = true) -> VideoRequestResult.Owned
                    else -> VideoRequestResult.Error(message ?: error ?: "Could not request title (HTTP ${response.code})")
                }
                MusicApiResult.Success(result)
            }
        } catch (e: Exception) {
            MusicApiResult.Error(e.message ?: "Network error")
        }
    }

    suspend fun requestMusicTrack(
        title: String,
        artist: String,
        album: String? = null,
        albumId: Int? = null,
        artistId: Int? = null,
        trackId: Int? = null,
        trackNumber: Int? = null,
        discNumber: Int? = null,
        durationMs: Long? = null,
        year: Int? = null,
        imageUrl: String? = null,
    ): MusicApiResult<MusicTrackRequestResult> = withContext(Dispatchers.IO) {
        val payload = MusicTrackRequestPayload(
            title = title,
            artist = artist,
            album = album,
            albumId = albumId,
            artistId = artistId,
            trackId = trackId,
            trackNumber = trackNumber,
            discNumber = discNumber,
            durationMs = durationMs,
            year = year,
            imageUrl = imageUrl,
        )
        val req = Request.Builder()
            .url("$base/api/player/music/request-track")
            .post(json.encodeToString(MusicTrackRequestPayload.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            pinnedAuthenticatedClient.newCall(req).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 401) return@withContext MusicApiResult.DeviceRevoked
                val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                val success = (root?.get("success") as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content?.toBooleanStrictOrNull() ?: false
                val skipped = (root?.get("skipped") as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content?.toBooleanStrictOrNull() ?: false
                val mode = (root?.get("mode") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "download"
                val error = (root?.get("error") as? kotlinx.serialization.json.JsonPrimitive)?.content
                val message = (root?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
                val result = when {
                    success && skipped -> MusicTrackRequestResult.Owned
                    success -> if (mode == "ask") MusicTrackRequestResult.Requested else MusicTrackRequestResult.Wishlist
                    else -> MusicTrackRequestResult.Error(message ?: error ?: "Could not request track (HTTP ${response.code})")
                }
                MusicApiResult.Success(result)
            }
        } catch (e: Exception) {
            MusicApiResult.Error(e.message ?: "Network error")
        }
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
