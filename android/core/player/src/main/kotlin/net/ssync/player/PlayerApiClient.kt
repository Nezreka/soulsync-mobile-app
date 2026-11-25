package net.ssync.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
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

/** the home layout id for this app on a phone (8-64 of [A-Za-z0-9_-], core/player/home_layout.py) */
/**
 * the id every Android device used to send for its "own" layout. kept only to
 * find a layout saved under it; a device's real id is made once per install
 * (PlayerDevicePrefs.layoutDeviceId).
 */
const val PHONE_LAYOUT_DEVICE = "android-phone"

private val APP_PRESENCE_SESSION: String = "android-" + java.util.UUID.randomUUID().toString().take(12)

class PlayerApiClient(
    val baseUrl: String,
    val pinnedAuthenticatedClient: OkHttpClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        isLenient = true
    },
) {
    private val base = baseUrl.trimEnd('/')
    val artwork = PlayerArtwork(base)

    @Volatile private var cachedVideoCatalog: Pair<Long, VideoCatalog>? = null
    @Volatile private var cachedMusicChannels: Pair<Long, List<MusicChannel>>? = null

    fun streamUrl(itemType: String, itemId: String): String =
        "$base/api/player/stream/$itemType/$itemId"

    suspend fun fetchPlayable(
        itemType: String,
        itemId: String,
        quality: String = "original",
    ): MusicApiResult<PlayableDecision> = getJson("$base/api/player/playable/$itemType/$itemId?q=$quality") { body ->
        val root = json.parseToJsonElement(body).jsonObject
        val url = (root["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val transcodeObj = root["transcode"] as? JsonObject
        val needsTranscode = transcodeObj != null
        val durationSec = (root["duration"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
        PlayableDecision(
            url = if (url.startsWith("/")) "$base$url" else url,
            needsTranscode = needsTranscode,
            durationMs = durationSec?.let { (it * 1000.0).toLong() } ?: 0L,
        )
    }

    fun transcodeUrl(
        itemType: String,
        itemId: String,
        startSec: Double = 0.0,
        screenId: String? = null,
        quality: String = "original",
    ): String = buildString {
        append("$base/api/player/transcode/$itemType/$itemId?q=$quality")
        if (startSec > 0.5) {
            append("&t=${String.format(java.util.Locale.US, "%.1f", startSec)}")
        }
        if (!screenId.isNullOrBlank()) {
            append("&ps=$screenId")
        }
    }

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
        val url = if (forceRefresh) "$base/api/player/video-catalog?refresh=1" else "$base/api/player/video-catalog"
        val res = getJson(url) { body ->
            val root = json.parseToJsonElement(body).jsonObject
            VideoCatalogDecoder.decode(root)
        }
        if (res is MusicApiResult.Success) {
            cachedVideoCatalog = now to res.data
        }
        return res
    }

    /**
     * the Live Guide's pool: the catalog as it was the first time anyone asked
     * today (core/player/guide_day.py). every device schedules from it, so a
     * download mid-day doesn't re-seed the shared timeline. browse screens keep
     * [fetchVideoCatalog]
     */
    suspend fun fetchGuideCatalog(): MusicApiResult<VideoCatalog> =
        getJson("$base/api/player/video-catalog?guide=1") { body ->
            val root = json.parseToJsonElement(body).jsonObject
            // the clock the guide's days are counted on: the server's, so this
            // device lays out the same guide as every other, wherever it is
            (root["clock"] as? JsonObject)?.let { clock ->
                GuideClock.set(
                    tz = clock["tz"]?.jsonPrimitive?.contentOrNull,
                    offsetMinutes = clock["offset_minutes"]?.jsonPrimitive?.intOrNull,
                )
            }
            VideoCatalogDecoder.decode(root)
        }

    /** what's new and when it airs: the same slots and channels on every device */
    suspend fun fetchGuidePremieres(): MusicApiResult<List<GuidePremiere>> =
        getJson("$base/api/player/guide/premieres") { body ->
            json.decodeFromString<GuidePremieresEnvelope>(body).premieres
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

    suspend fun fetchAlbums(
        search: String = "",
        limit: Int = 80,
        page: Int = 1,
        genre: String = "",
        sort: String = "added",
    ): MusicApiResult<List<LibAlbum>> {
        val url = "$base/api/library/v2/albums".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("sort", sort)
            .apply {
                if (search.isNotBlank()) addQueryParameter("search", search.trim())
                if (genre.isNotBlank()) addQueryParameter("genre", genre.trim())
            }
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

    suspend fun fetchDiscoverPlaylistDetail(key: String): MusicApiResult<DiscoverPlaylistDetail> =
        getJson("$base/api/player/discover-playlists/${encodeSeg(key)}") { body ->
            requireNotNull(json.decodeFromString<DiscoverPlaylistDetailEnvelope>(body).playlist) {
                "Discover playlist not found"
            }
        }

    suspend fun likeTrack(song: SongRefPayload): MusicApiResult<Unit> = withContext(Dispatchers.IO) {
        val payload = PlaylistTracksWriteRequest(listOf(song))
        val req = Request.Builder()
            .url("$base/api/user-playlists/liked")
            .post(json.encodeToString(PlaylistTracksWriteRequest.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(req) { }
    }

    suspend fun unlikeTrack(song: SongRefPayload): MusicApiResult<Unit> = withContext(Dispatchers.IO) {
        val payload = PlaylistTracksWriteRequest(listOf(song))
        val req = Request.Builder()
            .url("$base/api/user-playlists/liked/remove")
            .post(json.encodeToString(PlaylistTracksWriteRequest.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(req) { }
    }

    suspend fun addTrackToPlaylist(playlistId: Int, song: SongRefPayload): MusicApiResult<Unit> =
        withContext(Dispatchers.IO) {
            val payload = PlaylistTracksWriteRequest(listOf(song))
            val req = Request.Builder()
                .url("$base/api/user-playlists/$playlistId/tracks")
                .post(json.encodeToString(PlaylistTracksWriteRequest.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
                .build()
            execute(req) { }
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
            val env = json.decodeFromString<PodcastShowEnvelope>(body)
            env.show ?: throw IllegalStateException(env.error ?: "That podcast couldn't be loaded")
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
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id
                GuideChannel(
                    id = id,
                    name = name,
                    number = obj["number"]?.jsonPrimitive?.intOrNull,
                    kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: "video",
                    description = obj["description"]?.jsonPrimitive?.contentOrNull,
                    currentTitle = obj["current_title"]?.jsonPrimitive?.contentOrNull,
                    currentItemType = obj["current_item_type"]?.jsonPrimitive?.contentOrNull,
                    currentItemId = obj["current_item_id"]?.jsonPrimitive?.contentOrNull,
                    nextTitle = obj["next_title"]?.jsonPrimitive?.contentOrNull,
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
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                    val scope = obj["scope"]?.jsonPrimitive?.contentOrNull ?: defaultScope
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

    suspend fun createSavedGuideChannel(
        scope: String = "mine",
        spec: GuideChannelSpec,
    ): MusicApiResult<SavedGuideChannel> = withContext(Dispatchers.IO) {
        val bodyObj = kotlinx.serialization.json.buildJsonObject {
            put("scope", kotlinx.serialization.json.JsonPrimitive(scope))
            put("spec", json.encodeToJsonElement(GuideChannelSpec.serializer(), spec))
        }
        val req = Request.Builder()
            .url("$base/api/player/guide/channels")
            .post(bodyObj.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(req) { body ->
            val root = json.parseToJsonElement(body).jsonObject
            val chObj = root["channel"] as? JsonObject
                ?: throw IllegalStateException("Invalid channel response")
            val id = chObj["id"]?.jsonPrimitive?.contentOrNull ?: ""
            val sc = chObj["scope"]?.jsonPrimitive?.contentOrNull ?: scope
            val sp = chObj["spec"]?.let { json.decodeFromJsonElement(GuideChannelSpec.serializer(), it) } ?: spec
            SavedGuideChannel(id = id, scope = sc, spec = sp)
        }
    }

    suspend fun deleteSavedGuideChannel(channelId: String): MusicApiResult<Unit> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$base/api/player/guide/channels/${encodeSeg(channelId)}")
            .delete()
            .build()
        execute(req) { }
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
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id
                val kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: "genre"
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
                            val title = t.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: "Track"
                            val artist = t.getOrNull(2)?.jsonPrimitive?.contentOrNull ?: ""
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
                    description = obj["description"]?.jsonPrimitive?.contentOrNull,
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

    suspend fun leaveWatchParty(partyId: String): MusicApiResult<Unit> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$base/api/player/parties/${encodeSeg(partyId)}/leave")
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(req) { }
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

    // one id for the app's run, not per client: a new client (a wi-fi/mobile
    // switch, a profile change) left the old id's "watching" entry behind
    private val presenceSessionId: String get() = APP_PRESENCE_SESSION

    suspend fun sendPresence(
        itemType: String,
        itemId: String,
        title: String,
        subtitle: String? = null,
        artwork: String? = null,
        positionSeconds: Double = 0.0,
        durationSeconds: Double? = null,
        paused: Boolean = false,
        live: Boolean = false,
        channel: PresenceChannelRef? = null,
    ): MusicApiResult<Unit> = withContext(Dispatchers.IO) {
        val payload = PresenceHeartbeatRequest(
            session = presenceSessionId,
            itemType = itemType,
            itemId = itemId,
            position = positionSeconds.coerceAtLeast(0.0),
            duration = durationSeconds?.takeIf { it > 0.0 },
            playing = !paused,
            live = live,
            channel = channel,
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
        // the answer can carry an admin's order for this screen (stop, or a
        // message): handed to the app through ServerOrders
        execute(req) { body ->
            ServerOrders.parse(body)?.let(ServerOrders::post)
            Unit
        }
    }

    suspend fun clearPresence(): MusicApiResult<Unit> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/api/player/presence?session=${encodeSeg(presenceSessionId)}").delete().build()
        execute(req) { }
    }

    suspend fun markWatched(
        itemType: String,
        itemId: String,
    ): MusicApiResult<Unit> = withContext(Dispatchers.IO) {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("item_type", kotlinx.serialization.json.JsonPrimitive(itemType))
            put("item_id", kotlinx.serialization.json.JsonPrimitive(itemId))
        }
        val req = Request.Builder()
            .url("$base/api/player/watched")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
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
            json.decodeFromString<PlayerSettingsEnvelope>(body).let { it.settings.copy(server = it.server) }
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

    // ── Retro Commercial Breaks ──────────────────────────────────────────────

    /** [forGuide]: the clips the day's breaks are scheduled from, frozen for the day */
    suspend fun fetchCommercialsConfig(forGuide: Boolean = false): MusicApiResult<CommercialsConfig> =
        getJson("$base/api/player/commercials/config" + if (forGuide) "?guide=1" else "") { body ->
            json.decodeFromString<CommercialsConfig>(body)
        }

    suspend fun saveCommercialsConfig(
        enabled: Boolean? = null,
        era: String? = null,
        mode: String? = null,
    ): MusicApiResult<CommercialsConfig> = withContext(Dispatchers.IO) {
        // only the fields being changed: a null era read as "none" (refused)
        // and a null enabled as off on servers before the fix
        val payload = kotlinx.serialization.json.buildJsonObject {
            enabled?.let { put("enabled", kotlinx.serialization.json.JsonPrimitive(it)) }
            era?.let { put("era", kotlinx.serialization.json.JsonPrimitive(it)) }
            mode?.let { put("mode", kotlinx.serialization.json.JsonPrimitive(it)) }
        }
        val req = Request.Builder()
            .url("$base/api/player/commercials/config")
            .put(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(req) { body ->
            json.decodeFromString<CommercialsConfig>(body)
        }
    }

    suspend fun fetchCommercialsStatus(): MusicApiResult<CommercialsStatus> =
        getJson("$base/api/player/commercials/status") { body ->
            json.decodeFromString<CommercialsStatus>(body)
        }

    suspend fun warmCommercials(era: String = "mix"): MusicApiResult<CommercialsStatus> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$base/api/player/commercials/warm")
                .post("""{"era":"$era"}""".toRequestBody(JSON_MEDIA_TYPE))
                .build()
            execute(req) { body ->
                json.decodeFromString<CommercialsStatus>(body)
            }
        }

    suspend fun clearCommercialsCache(): MusicApiResult<CommercialsStatus> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$base/api/player/commercials/cache")
                .delete()
                .build()
            execute(req) { body ->
                json.decodeFromString<CommercialsStatus>(body)
            }
        }

    // ── Media Issue Reporting (all media types) ──────────────────────────────

    suspend fun fetchIssues(status: String? = null): MusicApiResult<PlayerIssuesResponse> {
        val url = if (!status.isNullOrBlank()) "$base/api/player/issues?status=$status" else "$base/api/player/issues"
        return getJson(url) { body ->
            json.decodeFromString<PlayerIssuesResponse>(body)
        }
    }

    suspend fun reportIssue(
        itemType: String,
        itemId: String,
        title: String,
        subtitle: String = "",
        category: String = "playback",
        note: String = "",
        positionSec: Int = 0,
    ): MusicApiResult<PlayerIssue?> = withContext(Dispatchers.IO) {
        val payload = PlayerIssueCreateRequest(
            itemType = itemType,
            itemId = itemId,
            title = title,
            subtitle = subtitle,
            category = category,
            note = note,
            positionSec = positionSec,
        )
        val req = Request.Builder()
            .url("$base/api/player/issues")
            .post(json.encodeToString(PlayerIssueCreateRequest.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(req) { body ->
            json.decodeFromString<PlayerIssueEnvelope>(body).issue
        }
    }

    suspend fun updateIssueStatus(
        issueId: Int,
        status: String,
        adminNote: String? = null,
    ): MusicApiResult<PlayerIssue?> = withContext(Dispatchers.IO) {
        val payload = PlayerIssueUpdateRequest(status = status, adminNote = adminNote)
        val req = Request.Builder()
            .url("$base/api/player/issues/$issueId")
            .put(json.encodeToString(PlayerIssueUpdateRequest.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(req) { body ->
            json.decodeFromString<PlayerIssueEnvelope>(body).issue
        }
    }

    suspend fun fetchCommercialSchedule(
        items: List<CommercialScheduleItem>,
        era: String? = null,
        mode: String? = null,
        channelId: String? = null,
        channelName: String? = null,
        minGapSeconds: Int = 300,
    ): MusicApiResult<Map<String, List<CommercialClip>>> = withContext(Dispatchers.IO) {
        val payload = CommercialScheduleRequest(
            items = items,
            era = era,
            mode = mode,
            channelId = channelId,
            channelName = channelName,
            minGapSeconds = minGapSeconds,
        )
        val req = Request.Builder()
            .url("$base/api/player/commercials/schedule")
            .post(json.encodeToString(CommercialScheduleRequest.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(req) { body ->
            json.decodeFromString<CommercialScheduleEnvelope>(body).breaks
        }
    }

    suspend fun fetchCommercialBreak(
        era: String? = null,
        mode: String? = null,
        channelId: String? = null,
        channelName: String? = null,
        year: Int? = null,
    ): MusicApiResult<List<CommercialClip>> {
        val url = "$base/api/player/commercials/break".toHttpUrl().newBuilder()
            .apply {
                if (!era.isNullOrBlank()) addQueryParameter("era", era)
                if (!mode.isNullOrBlank()) addQueryParameter("mode", mode)
                if (!channelId.isNullOrBlank()) addQueryParameter("channel_id", channelId)
                if (!channelName.isNullOrBlank()) addQueryParameter("channel_name", channelName)
                if (year != null && year in 1950..2029) addQueryParameter("year", year.toString())
            }
            .build()
        return getJson(url.toString()) { body ->
            json.decodeFromString<CommercialBreakEnvelope>(body).clips
        }
    }

    // ── Home Layout (Shared / Per-Device Customizable Dashboard) ─────────────

    suspend fun fetchHomeLayout(device: String = PHONE_LAYOUT_DEVICE): MusicApiResult<HomeLayoutResponse> =
        getJson("$base/api/player/home-layout?device=$device") { body ->
            json.decodeFromString<HomeLayoutResponse>(body)
        }

    /**
     * save Home's rows: [shared] for every screen of this profile (and this
     * screen's own rows go), else for this screen only ([device])
     */
    suspend fun saveHomeLayout(
        layout: HomeLayout,
        device: String = PHONE_LAYOUT_DEVICE,
        shared: Boolean = true,
    ): MusicApiResult<HomeLayoutResponse> = withContext(Dispatchers.IO) {
        val payload = if (shared) {
            HomeLayoutPutRequest(layout = layout, device = "*", fromDevice = device)
        } else {
            HomeLayoutPutRequest(layout = layout, device = device)
        }
        val req = Request.Builder()
            .url("$base/api/player/home-layout")
            .put(json.encodeToString(HomeLayoutPutRequest.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(req) { body ->
            runCatching { json.decodeFromString<HomeLayoutResponse>(body) }
                .getOrDefault(HomeLayoutResponse(layout = layout, scope = if (shared) "all" else "device"))
        }
    }

    /**
     * [device]: that screen follows the profile's shared rows again.
     * null: every screen of the profile goes back to the default.
     */
    suspend fun resetHomeLayout(device: String? = PHONE_LAYOUT_DEVICE): MusicApiResult<Unit> =
        withContext(Dispatchers.IO) {
            val url = if (device == null) "$base/api/player/home-layout" else "$base/api/player/home-layout?device=$device"
            val req = Request.Builder().url(url).delete().build()
            execute(req) { }
        }

    // ── Page layouts: the rows of Movies, TV, Music and YouTube ──────────────
    // the same contract as Home's, one per page (SoulSync core/player/page_layout.py).
    // [page] is "movies", "tv", "music", "youtube", or one library's "<page>:lib:<id>".

    suspend fun fetchPageLayout(page: String, device: String): MusicApiResult<HomeLayoutResponse> =
        getJson("$base/api/player/page-layout/${encodeSeg(page)}?device=$device") { body ->
            json.decodeFromString<HomeLayoutResponse>(body)
        }

    /** [shared]: for every screen of the profile (this screen's own rows go), else this screen only */
    suspend fun savePageLayout(
        page: String,
        layout: HomeLayout,
        device: String,
        shared: Boolean,
    ): MusicApiResult<HomeLayoutResponse> = withContext(Dispatchers.IO) {
        val payload = if (shared) {
            HomeLayoutPutRequest(layout = layout, device = "*", fromDevice = device)
        } else {
            HomeLayoutPutRequest(layout = layout, device = device)
        }
        val req = Request.Builder()
            .url("$base/api/player/page-layout/${encodeSeg(page)}")
            .put(json.encodeToString(HomeLayoutPutRequest.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(req) { body -> json.decodeFromString<HomeLayoutResponse>(body) }
    }

    /** [device]: that screen follows the shared rows again. null: the page's default, on every screen. */
    suspend fun resetPageLayout(page: String, device: String?): MusicApiResult<Unit> =
        withContext(Dispatchers.IO) {
            val url = "$base/api/player/page-layout/${encodeSeg(page)}" + (device?.let { "?device=$it" } ?: "")
            execute(Request.Builder().url(url).delete().build()) { }
        }

    // ── Discover: tmdb's picks, the rows the web player's Movies and TV pages draw ──

    /** picked from what this profile watches: titles it has and titles it doesn't */
    suspend fun fetchDiscoverForYou(): MusicApiResult<List<ExtraTitle>> =
        getJson("$base/api/video/discover/foryou") { body ->
            json.decodeFromString<ExtraItemsResponse>(body).items
        }

    /** "more like <a title you own>" rails */
    suspend fun fetchDiscoverMoreLike(): MusicApiResult<List<ExtraRail>> =
        getJson("$base/api/video/discover/morelike") { body ->
            json.decodeFromString<ExtraRailsResponse>(body).rails
        }

    /** a curated tmdb list by key: popular_movies, now_playing, upcoming_movies, top_movies, popular_shows, airing_today, on_the_air, top_shows */
    suspend fun fetchDiscoverList(key: String, pages: Int = 2): MusicApiResult<List<ExtraTitle>> =
        getJson("$base/api/video/discover/list?key=${encodeSeg(key)}&pages=$pages") { body ->
            json.decodeFromString<ExtraItemsResponse>(body).items
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

    suspend fun fetchVideoCalendar(
        start: String,
        days: Int = 14,
        scope: String = "all",
    ): MusicApiResult<VideoCalendarResponse> {
        val url = "$base/api/video/calendar".toHttpUrl().newBuilder()
            .addQueryParameter("start", start)
            .addQueryParameter("days", days.toString())
            .addQueryParameter("scope", scope)
            .build()
        return getJson(url.toString()) { body ->
            json.decodeFromString<VideoCalendarResponse>(body)
        }
    }

    suspend fun fetchVideoCalendarFollowed(
        start: String,
        days: Int = 14,
    ): MusicApiResult<List<CalendarEpisode>> {
        val url = "$base/api/video/calendar/followed".toHttpUrl().newBuilder()
            .addQueryParameter("start", start)
            .addQueryParameter("days", days.toString())
            .build()
        return getJson(url.toString()) { body ->
            json.decodeFromString<VideoCalendarFollowedEnvelope>(body).episodes
        }
    }

    // ── Offline Downloads Tracking & Admin User Management ───────────────────

    suspend fun fetchMyDownloads(includeRemoved: Boolean = false): MusicApiResult<MyDownloadsResponse> {
        val url = if (includeRemoved) "$base/api/player/downloads?include_removed=1" else "$base/api/player/downloads"
        return getJson(url) { body ->
            json.decodeFromString<MyDownloadsResponse>(body)
        }
    }

    suspend fun logOfflineDownload(
        itemType: String,
        itemId: String,
        title: String,
        subtitle: String? = null,
        artwork: String? = null,
        deviceId: String = "android",
        deviceName: String = android.os.Build.MODEL ?: "Android",
        quality: String = "original",
        sizeBytes: Long = 0L,
    ): MusicApiResult<Unit> = withContext(Dispatchers.IO) {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("item_type", kotlinx.serialization.json.JsonPrimitive(itemType))
            put("item_id", kotlinx.serialization.json.JsonPrimitive(itemId))
            put("title", kotlinx.serialization.json.JsonPrimitive(title))
            if (subtitle != null) put("subtitle", kotlinx.serialization.json.JsonPrimitive(subtitle))
            if (artwork != null) put("artwork", kotlinx.serialization.json.JsonPrimitive(artwork))
            put("device_id", kotlinx.serialization.json.JsonPrimitive(deviceId))
            put("device_name", kotlinx.serialization.json.JsonPrimitive(deviceName))
            put("quality", kotlinx.serialization.json.JsonPrimitive(quality))
            put("size_bytes", kotlinx.serialization.json.JsonPrimitive(sizeBytes))
        }
        val req = Request.Builder()
            .url("$base/api/player/downloads")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(req) { Unit }
    }

    suspend fun markOfflineDownloadRemoved(
        itemType: String,
        itemId: String,
        deviceId: String = "android",
    ): MusicApiResult<Unit> = withContext(Dispatchers.IO) {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("item_type", kotlinx.serialization.json.JsonPrimitive(itemType))
            put("item_id", kotlinx.serialization.json.JsonPrimitive(itemId))
            put("device_id", kotlinx.serialization.json.JsonPrimitive(deviceId))
        }
        val req = Request.Builder()
            .url("$base/api/player/downloads/removed")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(req) { Unit }
    }

    suspend fun fetchAdminUsers(
        profileId: Int? = null,
        itemType: String? = null,
        status: String? = null,
    ): MusicApiResult<AdminUsersOverview> {
        val b = "$base/api/player/admin/users".toHttpUrl().newBuilder()
        if (profileId != null) b.addQueryParameter("profile_id", profileId.toString())
        if (!itemType.isNullOrBlank()) b.addQueryParameter("item_type", itemType)
        if (!status.isNullOrBlank()) b.addQueryParameter("status", status)
        return getJson(b.build().toString()) { body ->
            json.decodeFromString<AdminUsersOverview>(body)
        }
    }

    suspend fun setAdminUserDownloadPerm(
        targetProfileId: Int,
        allowDownloads: Boolean,
        maxItems: Int = 0,
    ): MusicApiResult<Unit> = withContext(Dispatchers.IO) {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("allow_downloads", kotlinx.serialization.json.JsonPrimitive(allowDownloads))
            put("max_items", kotlinx.serialization.json.JsonPrimitive(maxItems))
        }
        val req = Request.Builder()
            .url("$base/api/player/admin/users/$targetProfileId/downloads")
            .put(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(req) { Unit }
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
            val root = json.parseToJsonElement(body).jsonObject
            val out = mutableListOf<DiscoverPlaylistSummary>()
            fun parseItem(obj: JsonObject, secLabel: String = ""): DiscoverPlaylistSummary? {
                val key = obj["key"]?.jsonPrimitive?.contentOrNull ?: return null
                val title = obj["name"]?.jsonPrimitive?.contentOrNull
                    ?: obj["title"]?.jsonPrimitive?.contentOrNull
                    ?: return null
                val subtitle = obj["blurb"]?.jsonPrimitive?.contentOrNull
                    ?: obj["subtitle"]?.jsonPrimitive?.contentOrNull
                val trackCount = obj["track_count"]?.jsonPrimitive?.intOrNull ?: 0
                val coverUrl = obj["cover_url"]?.jsonPrimitive?.contentOrNull
                val tiles = (obj["cover_tiles"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.content.takeIf { s -> s.isNotBlank() && s != "null" } }
                    .orEmpty()
                return DiscoverPlaylistSummary(
                    key = key,
                    title = title,
                    subtitle = subtitle,
                    trackCount = trackCount,
                    coverUrl = coverUrl ?: tiles.firstOrNull(),
                    coverTiles = tiles,
                    sectionLabel = secLabel,
                )
            }
            val sections = root["sections"] as? JsonArray
            if (sections != null) {
                for (sec in sections) {
                    val secObj = sec as? JsonObject ?: continue
                    val secLabel = secObj["label"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val pls = secObj["playlists"] as? JsonArray ?: continue
                    for (p in pls) {
                        (p as? JsonObject)?.let { parseItem(it, secLabel) }?.let { out += it }
                    }
                }
            } else {
                val pls = root["playlists"] as? JsonArray
                if (pls != null) {
                    for (p in pls) {
                        (p as? JsonObject)?.let(::parseItem)?.let { out += it }
                    }
                }
            }
            out
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
                if (response.code == 401 && net.ssync.network.isRevocation(body)) return@withContext MusicApiResult.DeviceRevoked
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
                hls = (root["hls"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
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
        season: Int? = null,
        episode: Int? = null,
        get: Boolean = false,
    ): MusicApiResult<VideoRequestResult> = withContext(Dispatchers.IO) {
        val payload = VideoRequestPayload(
            kind = kind,
            tmdbId = tmdbId,
            title = title,
            year = year,
            posterUrl = posterUrl,
            season = season,
            episode = episode,
            get = get,
        )
        val req = Request.Builder()
            .url("$base/api/video/requests")
            .post(json.encodeToString(VideoRequestPayload.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            pinnedAuthenticatedClient.newCall(req).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 401 && net.ssync.network.isRevocation(body)) return@withContext MusicApiResult.DeviceRevoked
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
                if (response.code == 401 && net.ssync.network.isRevocation(body)) return@withContext MusicApiResult.DeviceRevoked
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

    /**
     * Fetches real per-profile listening history & top plays from /api/stats/cached
     * (instant precomputed cache), falling back to live /api/stats/recent when needed.
     */
    suspend fun fetchListeningStats(range: String = "30d"): MusicApiResult<ListeningStatsSnapshot> {
        val res = getJson("$base/api/stats/cached?range=${encodeSeg(range)}") { body ->
            val root = json.parseToJsonElement(body).jsonObject
            fun parseArtists(el: JsonElement?): List<StatsTopArtist> =
                (el as? JsonArray)?.mapNotNull { item ->
                    val o = item as? JsonObject ?: return@mapNotNull null
                    val name = o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    StatsTopArtist(
                        id = o["id"]?.jsonPrimitive?.intOrNull,
                        name = name,
                        playCount = o["play_count"]?.jsonPrimitive?.intOrNull ?: o["plays"]?.jsonPrimitive?.intOrNull ?: 0,
                        imageUrl = o["image_url"]?.jsonPrimitive?.contentOrNull,
                    )
                }.orEmpty()

            fun parseAlbums(el: JsonElement?): List<StatsTopAlbum> =
                (el as? JsonArray)?.mapNotNull { item ->
                    val o = item as? JsonObject ?: return@mapNotNull null
                    val name = (o["name"] ?: o["album"] ?: o["title"])?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    StatsTopAlbum(
                        id = o["id"]?.jsonPrimitive?.intOrNull,
                        name = name,
                        artist = o["artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        artistId = o["artist_id"]?.jsonPrimitive?.intOrNull,
                        playCount = o["play_count"]?.jsonPrimitive?.intOrNull ?: o["plays"]?.jsonPrimitive?.intOrNull ?: 0,
                        imageUrl = o["image_url"]?.jsonPrimitive?.contentOrNull,
                    )
                }.orEmpty()

            fun parseTracks(el: JsonElement?): List<StatsTopTrack> =
                (el as? JsonArray)?.mapNotNull { item ->
                    val o = item as? JsonObject ?: return@mapNotNull null
                    val name = (o["name"] ?: o["title"])?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    StatsTopTrack(
                        id = o["id"]?.jsonPrimitive?.intOrNull,
                        name = name,
                        artist = o["artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        album = o["album"]?.jsonPrimitive?.contentOrNull,
                        artistId = o["artist_id"]?.jsonPrimitive?.intOrNull,
                        playCount = o["play_count"]?.jsonPrimitive?.intOrNull ?: o["plays"]?.jsonPrimitive?.intOrNull ?: 0,
                        imageUrl = o["image_url"]?.jsonPrimitive?.contentOrNull,
                    )
                }.orEmpty()

            fun parseRecent(el: JsonElement?): List<StatsRecentTrack> =
                (el as? JsonArray)?.mapNotNull { item ->
                    val o = item as? JsonObject ?: return@mapNotNull null
                    val title = (o["title"] ?: o["name"])?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    StatsRecentTrack(
                        title = title,
                        artist = o["artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        album = o["album"]?.jsonPrimitive?.contentOrNull,
                        playedAt = o["played_at"]?.jsonPrimitive?.contentOrNull,
                        imageUrl = o["image_url"]?.jsonPrimitive?.contentOrNull,
                        artistDbId = o["artist_db_id"]?.jsonPrimitive?.intOrNull,
                    )
                }.orEmpty()

            fun parseGenres(el: JsonElement?): List<StatsGenreCount> =
                (el as? JsonArray)?.mapNotNull { item ->
                    val o = item as? JsonObject ?: return@mapNotNull null
                    val g = (o["genre"] ?: o["name"])?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    StatsGenreCount(
                        genre = g,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: o["play_count"]?.jsonPrimitive?.intOrNull ?: o["plays"]?.jsonPrimitive?.intOrNull ?: 0,
                    )
                }.orEmpty()

            ListeningStatsSnapshot(
                topArtists = parseArtists(root["top_artists"]),
                topAlbums = parseAlbums(root["top_albums"]),
                topTracks = parseTracks(root["top_tracks"]),
                recent = parseRecent(root["recent"]),
                genres = parseGenres(root["genres"]),
            )
        }
        val cached = (res as? MusicApiResult.Success)?.data
        if (cached != null && (cached.topAlbums.isNotEmpty() || cached.topTracks.isNotEmpty() || cached.topArtists.isNotEmpty())) {
            return MusicApiResult.Success(cached)
        }
        // Cache miss or empty range -> fall back to live /api/stats/* endpoints!
        val liveArtists = (getJson("$base/api/stats/top-artists?range=${encodeSeg(range)}&limit=14") { body ->
            val root = json.parseToJsonElement(body).jsonObject
            (root["artists"] as? JsonArray)?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val name = o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                StatsTopArtist(
                    id = o["id"]?.jsonPrimitive?.intOrNull,
                    name = name,
                    playCount = o["play_count"]?.jsonPrimitive?.intOrNull ?: o["plays"]?.jsonPrimitive?.intOrNull ?: 0,
                    imageUrl = o["image_url"]?.jsonPrimitive?.contentOrNull,
                )
            }.orEmpty()
        } as? MusicApiResult.Success)?.data.orEmpty()

        val liveAlbums = (getJson("$base/api/stats/top-albums?range=${encodeSeg(range)}&limit=20") { body ->
            val root = json.parseToJsonElement(body).jsonObject
            (root["albums"] as? JsonArray)?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val name = (o["name"] ?: o["album"] ?: o["title"])?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                StatsTopAlbum(
                    id = o["id"]?.jsonPrimitive?.intOrNull,
                    name = name,
                    artist = o["artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    artistId = o["artist_id"]?.jsonPrimitive?.intOrNull,
                    playCount = o["play_count"]?.jsonPrimitive?.intOrNull ?: o["plays"]?.jsonPrimitive?.intOrNull ?: 0,
                    imageUrl = o["image_url"]?.jsonPrimitive?.contentOrNull,
                )
            }.orEmpty()
        } as? MusicApiResult.Success)?.data.orEmpty()

        val liveTracks = (getJson("$base/api/stats/top-tracks?range=${encodeSeg(range)}&limit=20") { body ->
            val root = json.parseToJsonElement(body).jsonObject
            (root["tracks"] as? JsonArray)?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val name = (o["name"] ?: o["title"])?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                StatsTopTrack(
                    id = o["id"]?.jsonPrimitive?.intOrNull,
                    name = name,
                    artist = o["artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    album = o["album"]?.jsonPrimitive?.contentOrNull,
                    artistId = o["artist_id"]?.jsonPrimitive?.intOrNull,
                    playCount = o["play_count"]?.jsonPrimitive?.intOrNull ?: o["plays"]?.jsonPrimitive?.intOrNull ?: 0,
                    imageUrl = o["image_url"]?.jsonPrimitive?.contentOrNull,
                )
            }.orEmpty()
        } as? MusicApiResult.Success)?.data.orEmpty()

        val liveGenres = (getJson("$base/api/stats/genres?range=${encodeSeg(range)}") { body ->
            val root = json.parseToJsonElement(body).jsonObject
            (root["genres"] as? JsonArray)?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val g = (o["genre"] ?: o["name"])?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                StatsGenreCount(
                    genre = g,
                    count = o["count"]?.jsonPrimitive?.intOrNull ?: o["play_count"]?.jsonPrimitive?.intOrNull ?: o["plays"]?.jsonPrimitive?.intOrNull ?: 0,
                )
            }.orEmpty()
        } as? MusicApiResult.Success)?.data.orEmpty()

        return MusicApiResult.Success(
            ListeningStatsSnapshot(
                topArtists = liveArtists.ifEmpty { cached?.topArtists.orEmpty() },
                topAlbums = liveAlbums.ifEmpty { cached?.topAlbums.orEmpty() },
                topTracks = liveTracks.ifEmpty { cached?.topTracks.orEmpty() },
                recent = cached?.recent.orEmpty(),
                genres = liveGenres.ifEmpty { cached?.genres.orEmpty() },
            ),
        )
    }

    suspend fun fetchRecentStatsTracks(limit: Int = 24): MusicApiResult<List<StatsRecentTrack>> =
        getJson("$base/api/stats/recent?limit=$limit") { body ->
            val root = json.parseToJsonElement(body).jsonObject
            (root["tracks"] as? JsonArray)?.mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val title = (o["title"] ?: o["name"])?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                StatsRecentTrack(
                    title = title,
                    artist = o["artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    album = o["album"]?.jsonPrimitive?.contentOrNull,
                    playedAt = o["played_at"]?.jsonPrimitive?.contentOrNull,
                    imageUrl = o["image_url"]?.jsonPrimitive?.contentOrNull,
                    artistDbId = o["artist_db_id"]?.jsonPrimitive?.intOrNull,
                )
            }.orEmpty()
        }

    suspend fun resolveStatsTrack(title: String, artist: String): MusicApiResult<ResolvedTrackInfo?> =
        withContext(Dispatchers.IO) {
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("title", kotlinx.serialization.json.JsonPrimitive(title))
                put("artist", kotlinx.serialization.json.JsonPrimitive(artist))
            }
            val req = Request.Builder()
                .url("$base/api/stats/resolve-track")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            execute(req) { body ->
                val root = json.parseToJsonElement(body).jsonObject
                val tObj = (root["track"] as? JsonObject) ?: root
                val id = (tObj["lib2_track_id"] ?: tObj["id"])?.jsonPrimitive?.intOrNull ?: return@execute null
                ResolvedTrackInfo(
                    id = id,
                    title = tObj["title"]?.jsonPrimitive?.contentOrNull ?: title,
                    artistName = tObj["artist_name"]?.jsonPrimitive?.contentOrNull ?: artist,
                    albumTitle = tObj["album_title"]?.jsonPrimitive?.contentOrNull,
                    imageUrl = tObj["image_url"]?.jsonPrimitive?.contentOrNull,
                    artistId = (tObj["lib2_artist_id"] ?: tObj["artist_id"])?.jsonPrimitive?.intOrNull,
                    albumId = tObj["album_id"]?.jsonPrimitive?.intOrNull,
                )
            }
        }

    suspend fun fetchArtistTopTracks(artistId: Int, limit: Int = 10): MusicApiResult<List<ArtistTopTrack>> =
        getJson("$base/api/player/music/artist/$artistId/top?limit=$limit") { body ->
            val root = json.parseToJsonElement(body).jsonObject
            (root["tracks"] as? JsonArray)?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = (o["track_id"] ?: o["id"])?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val title = (o["title"] ?: o["name"])?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ArtistTopTrack(
                    id = id,
                    title = title,
                    artist = o["artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    album = o["album"]?.jsonPrimitive?.contentOrNull,
                    albumId = o["album_id"]?.jsonPrimitive?.intOrNull,
                    durationMs = (o["duration_ms"] ?: o["duration"])?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                    playCount = (o["play_count"] ?: o["plays"])?.jsonPrimitive?.intOrNull ?: 0,
                    imageUrl = (o["image_url"] ?: o["artwork"] ?: o["thumb_url"])?.jsonPrimitive?.contentOrNull,
                )
            }.orEmpty()
        }

    suspend fun fetchArtistYourPlays(artistId: Int): MusicApiResult<ArtistListeningStats?> =
        getJson("$base/api/library/v2/artists/$artistId/your-plays") { body ->
            val root = json.parseToJsonElement(body).jsonObject
            val lObj = root["listening"] as? JsonObject ?: return@getJson null
            val months = (lObj["months"] as? JsonArray)?.mapNotNull { mEl ->
                val mo = mEl as? JsonObject ?: return@mapNotNull null
                ArtistListeningMonth(
                    month = mo["month"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    plays = mo["plays"]?.jsonPrimitive?.intOrNull ?: 0,
                )
            }.orEmpty()
            val topTracks = (lObj["top_tracks"] as? JsonArray)?.mapNotNull { tEl ->
                val to = tEl as? JsonObject ?: return@mapNotNull null
                ArtistListeningTopTrack(
                    title = to["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    album = to["album"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    playCount = to["play_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    trackId = to["track_id"]?.jsonPrimitive?.intOrNull,
                    albumId = to["album_id"]?.jsonPrimitive?.intOrNull,
                    thumbUrl = to["thumb_url"]?.jsonPrimitive?.contentOrNull,
                )
            }.orEmpty()
            ArtistListeningStats(
                playCount = lObj["play_count"]?.jsonPrimitive?.intOrNull ?: 0,
                plays30d = lObj["plays_30d"]?.jsonPrimitive?.intOrNull ?: 0,
                rank = lObj["rank"]?.jsonPrimitive?.intOrNull,
                artistsPlayed = lObj["artists_played"]?.jsonPrimitive?.intOrNull,
                firstPlayed = lObj["first_played"]?.jsonPrimitive?.contentOrNull,
                lastPlayed = lObj["last_played"]?.jsonPrimitive?.contentOrNull,
                months = months,
                topTracks = topTracks,
            )
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
                    response.code == 401 && net.ssync.network.isRevocation(body) -> MusicApiResult.DeviceRevoked
                    response.code == 401 -> MusicApiResult.Error("Not signed in to SoulSync. Pick your profile again.")
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
