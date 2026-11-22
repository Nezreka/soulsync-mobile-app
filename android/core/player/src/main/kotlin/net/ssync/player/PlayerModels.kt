package net.ssync.player

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

// ── Progress & Continue Watching ──────────────────────────────────────────────

@Serializable
data class ProgressMeta(
    val title: String? = null,
    val subtitle: String? = null,
    val artwork: String? = null,
    val backdrop: String? = null,
    val href: String? = null,
    @SerialName("parent_id")
    val parentId: String? = null,
    @SerialName("parent_title")
    val parentTitle: String? = null,
)

@Serializable
data class ProgressRow(
    @SerialName("item_type")
    val itemType: String,
    @SerialName("item_id")
    val itemId: String,
    @SerialName("position_seconds")
    val positionSeconds: Double = 0.0,
    @SerialName("duration_seconds")
    val durationSeconds: Double? = null,
    val finished: Boolean = false,
    val meta: ProgressMeta? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null,
) {
    val progressFraction: Float
        get() {
            val dur = durationSeconds ?: return 0f
            if (dur <= 0.0) return 0f
            return (positionSeconds / dur).toFloat().coerceIn(0f, 1f)
        }
}

@Serializable
internal data class ContinueResponse(
    val items: List<ProgressRow> = emptyList(),
)

// ── Video Catalog (Movies, TV, Recent Episodes, YouTube) ─────────────────────

@Serializable
data class CatalogMovie(
    val id: Int,
    val title: String,
    val year: Int? = null,
    @SerialName("runtime_seconds")
    val runtimeSeconds: Int? = null,
    @SerialName("content_rating")
    val contentRating: String? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
    @SerialName("added_at")
    val addedAt: String? = null,
    @SerialName("has_poster")
    val hasPoster: Boolean = false,
    @SerialName("has_backdrop")
    val hasBackdrop: Boolean = false,
    val studio: String? = null,
    @SerialName("tmdb_id")
    val tmdbId: Int? = null,
    @SerialName("collection_id")
    val collectionId: Int? = null,
    val collection: String? = null,
)

@Serializable
data class CatalogShow(
    val id: Int,
    val title: String,
    val year: Int? = null,
    val network: String? = null,
    @SerialName("content_rating")
    val contentRating: String? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
    @SerialName("added_at")
    val addedAt: String? = null,
    @SerialName("has_poster")
    val hasPoster: Boolean = false,
    @SerialName("has_backdrop")
    val hasBackdrop: Boolean = false,
    @SerialName("episode_count")
    val episodeCount: Int = 0,
    @SerialName("tmdb_id")
    val tmdbId: Int? = null,
)

@Serializable
data class CatalogRecentEpisode(
    val id: Int,
    @SerialName("show_id")
    val showId: Int,
    @SerialName("show_title")
    val showTitle: String = "",
    @SerialName("season_number")
    val seasonNumber: Int = 1,
    @SerialName("episode_number")
    val episodeNumber: Int = 1,
    val title: String? = null,
    @SerialName("has_still")
    val hasStill: Boolean = false,
    @SerialName("added_at")
    val addedAt: String? = null,
    @SerialName("runtime_seconds")
    val runtimeSeconds: Int? = null,
)

@Serializable
data class CatalogYoutube(
    val id: String,
    @SerialName("youtube_id")
    val youtubeId: String = id,
    val title: String = "",
    @SerialName("duration_seconds")
    val durationSeconds: Int? = null,
    @SerialName("published_at")
    val publishedAt: String? = null,
    @SerialName("thumbnail_url")
    val thumbnailUrl: String? = null,
    @SerialName("channel_id")
    val channelId: String? = null,
    @SerialName("channel_title")
    val channelTitle: String? = null,
    @SerialName("added_at")
    val addedAt: String? = null,
)

@Serializable
data class VideoCatalogKinds(
    val movies: Boolean = true,
    val shows: Boolean = true,
    val youtube: Boolean = true,
)

@Serializable
data class VideoCatalog(
    val movies: List<CatalogMovie> = emptyList(),
    val shows: List<CatalogShow> = emptyList(),
    @SerialName("recent_episodes")
    val recentEpisodes: List<CatalogRecentEpisode> = emptyList(),
    val youtube: List<CatalogYoutube> = emptyList(),
    val kinds: VideoCatalogKinds = VideoCatalogKinds(),
)

// ── Movie & TV Detail ────────────────────────────────────────────────────────

@Serializable
data class CastMember(
    val name: String,
    val character: String? = null,
    val photo: String? = null,
    @SerialName("tmdb_id")
    val tmdbId: Int? = null,
)

@Serializable
data class MovieDetail(
    val id: Int,
    val title: String,
    val year: Int? = null,
    val overview: String? = null,
    val tagline: String? = null,
    @SerialName("content_rating")
    val contentRating: String? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
    @SerialName("runtime_minutes")
    val runtimeMinutes: Int? = null,
    @SerialName("has_poster")
    val hasPoster: Boolean = false,
    @SerialName("has_backdrop")
    val hasBackdrop: Boolean = false,
    val studio: String? = null,
    val cast: List<CastMember> = emptyList(),
    val watched: Boolean? = null,
)

@Serializable
data class DetailEpisode(
    val id: Int,
    @SerialName("episode_number")
    val episodeNumber: Int,
    val title: String? = null,
    val overview: String? = null,
    @SerialName("air_date")
    val airDate: String? = null,
    @SerialName("runtime_minutes")
    val runtimeMinutes: Int? = null,
    @SerialName("has_still")
    val hasStill: Boolean = false,
    val owned: Boolean = true,
    val rating: Double? = null,
    val watched: Boolean? = null,
    val resolution: String? = null,
)

@Serializable
data class DetailSeason(
    val id: Int = 0,
    @SerialName("season_number")
    val seasonNumber: Int,
    val title: String? = null,
    @SerialName("has_poster")
    val hasPoster: Boolean = false,
    @SerialName("episode_total")
    val episodeTotal: Int = 0,
    @SerialName("episode_owned")
    val episodeOwned: Int = 0,
    val episodes: List<DetailEpisode> = emptyList(),
)

@Serializable
data class ShowDetail(
    val id: Int,
    val title: String,
    val year: Int? = null,
    val overview: String? = null,
    val tagline: String? = null,
    @SerialName("content_rating")
    val contentRating: String? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
    val network: String? = null,
    @SerialName("has_poster")
    val hasPoster: Boolean = false,
    @SerialName("has_backdrop")
    val hasBackdrop: Boolean = false,
    val cast: List<CastMember> = emptyList(),
    val seasons: List<DetailSeason> = emptyList(),
)

// ── Music (Library v2 & Playlists & Lyrics) ──────────────────────────────────

@Serializable
data class LibArtist(
    val id: Int,
    val name: String,
    @SerialName("image_url")
    val imageUrl: String? = null,
    @SerialName("remote_image_url")
    val remoteImageUrl: String? = null,
    val genres: List<String> = emptyList(),
    @SerialName("album_count")
    val albumCount: Int = 0,
    @SerialName("track_count")
    val trackCount: Int = 0,
)

@Serializable
data class LibAlbum(
    val id: Int,
    val title: String,
    @SerialName("album_type")
    val albumType: String? = null,
    val year: Int? = null,
    @SerialName("image_url")
    val imageUrl: String? = null,
    @SerialName("remote_image_url")
    val remoteImageUrl: String? = null,
    @SerialName("artist_id")
    val artistId: Int? = null,
    @SerialName("artist_name")
    val artistName: String? = null,
    @SerialName("track_count")
    val trackCount: Int = 0,
    @SerialName("tracks_present")
    val tracksPresent: Int = 0,
)

@Serializable
data class LibArtistAbout(
    val bio: String? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class LibArtistDetail(
    val id: Int,
    val name: String,
    @SerialName("image_url")
    val imageUrl: String? = null,
    @SerialName("remote_image_url")
    val remoteImageUrl: String? = null,
    val summary: String? = null,
    val about: LibArtistAbout? = null,
    val genres: List<String> = emptyList(),
    val albums: List<LibAlbum> = emptyList(),
    val eps: List<LibAlbum> = emptyList(),
    val singles: List<LibAlbum> = emptyList(),
)

@Serializable
data class LibTrackFile(
    val format: String? = null,
    val bitrate: Int? = null,
    @SerialName("quality_tier")
    val qualityTier: String? = null,
)

@Serializable
data class LibTrack(
    val id: Int? = null,
    val title: String,
    @SerialName("track_number")
    val trackNumber: Int? = null,
    @SerialName("disc_number")
    val discNumber: Int? = null,
    val duration: Long? = null,
    val explicit: Boolean = false,
    @SerialName("is_missing")
    val isMissing: Boolean = false,
    val file: LibTrackFile? = null,
)

@Serializable
data class LibArtistRef(
    val id: Int? = null,
    val name: String = "",
)

@Serializable
data class LibAlbumDetail(
    val id: Int,
    val title: String,
    val year: Int? = null,
    @SerialName("image_url")
    val imageUrl: String? = null,
    val genres: List<String> = emptyList(),
    @SerialName("primary_artist")
    val primaryArtist: LibArtistRef? = null,
    val tracks: List<LibTrack> = emptyList(),
)

@Serializable
data class LibSong(
    val id: Int,
    val title: String,
    @SerialName("artist_name")
    val artistName: String? = null,
    @SerialName("album_title")
    val albumTitle: String? = null,
    @SerialName("album_id")
    val albumId: Int? = null,
    val duration: Long? = null,
)

@Serializable
data class PlaylistTrackItem(
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    @SerialName("track_id")
    val trackId: Int? = null,
    @SerialName("album_id")
    val albumId: Int? = null,
    @SerialName("duration_ms")
    val durationMs: Long? = null,
)

@Serializable
data class PlaylistDetail(
    val id: Int,
    val name: String,
    val description: String? = null,
    val kind: String = "yours",
    @SerialName("image_url")
    val imageUrl: String? = null,
    val tracks: List<PlaylistTrackItem> = emptyList(),
)

@Serializable
data class LyricsResponse(
    val synced: String? = null,
    val plain: String? = null,
    val lyrics: String? = null,
)

// ── YouTube, Podcasts & Audiobooks ───────────────────────────────────────────

@Serializable
data class YtChannel(
    val id: String,
    val title: String,
    val handle: String? = null,
    val avatar: String? = null,
    val banner: String? = null,
    val followed: Boolean = false,
    @SerialName("downloaded_count")
    val downloadedCount: Int = 0,
    val description: String? = null,
)

@Serializable
data class YtVideo(
    val id: String,
    val title: String,
    @SerialName("channel_id")
    val channelId: String? = null,
    @SerialName("channel_title")
    val channelTitle: String? = null,
    val thumbnail: String? = null,
    @SerialName("duration_seconds")
    val durationSeconds: Int? = null,
    @SerialName("published_at")
    val publishedAt: String? = null,
    val downloaded: Boolean = false,
)

@Serializable
data class YtOverviewResponse(
    val channels: List<YtChannel> = emptyList(),
    val downloads: List<YtVideo> = emptyList(),
    val latest: List<YtVideo> = emptyList(),
    @SerialName("videos")
    val legacyVideos: List<YtVideo> = emptyList(),
) {
    val allVideos: List<YtVideo>
        get() = (downloads + latest + legacyVideos).distinctBy { it.id }

    val downloadedVideos: List<YtVideo>
        get() = downloads.ifEmpty { legacyVideos.filter { it.downloaded }.ifEmpty { legacyVideos } }
}

@Serializable
data class YtChannelDetailResponse(
    val channel: YtChannel? = null,
    val videos: List<YtVideo> = emptyList(),
)

@Serializable
data class PodcastSub(
    val id: Int,
    @SerialName("feed_url")
    val feedUrl: String,
    val title: String,
    val author: String? = null,
    @SerialName("artwork_url")
    val artworkUrl: String? = null,
    @SerialName("episode_count")
    val episodeCount: Int? = null,
    val description: String? = null,
)

@Serializable
data class PodcastEpisode(
    val guid: String? = null,
    val title: String,
    @SerialName("enclosure_url")
    val enclosureUrl: String? = null,
    @SerialName("pub_date")
    val pubDate: String? = null,
    @SerialName("duration_seconds")
    val durationSeconds: Int? = null,
    val description: String? = null,
    @SerialName("artwork_url")
    val artworkUrl: String? = null,
    val downloaded: Boolean = false,
)

@Serializable
data class PodcastShow(
    val title: String = "",
    val author: String? = null,
    val description: String? = null,
    @SerialName("artwork_url")
    val artworkUrl: String? = null,
    @SerialName("feed_url")
    val feedUrl: String? = null,
    val episodes: List<PodcastEpisode> = emptyList(),
)

@Serializable
data class OwnedBook(
    val asin: String,
    val title: String,
    val author: String? = null,
    val narrator: String? = null,
    @SerialName("series_title")
    val seriesTitle: String? = null,
    @SerialName("runtime_minutes")
    val runtimeMinutes: Int? = null,
    @SerialName("cover_url")
    val coverUrl: String? = null,
)

// ── Watchlist, Presence (Who's Watching), Guide & Settings ───────────────────

@Serializable
data class WatchlistItem(
    @SerialName("item_type")
    val itemType: String,
    @SerialName("item_id")
    val itemId: String,
    val meta: ProgressMeta? = null,
    @SerialName("added_at")
    val addedAt: String? = null,
)

@Serializable
data class WatchingSession(
    @SerialName("profile_id")
    val profileId: Int = 0,
    @SerialName("profile_name")
    val profileName: String = "",
    @SerialName("avatar_color")
    val avatarColor: String? = null,
    val you: Boolean = false,
    @SerialName("item_type")
    val itemType: String = "",
    @SerialName("item_id")
    val itemId: String = "",
    val title: String = "",
    val subtitle: String? = null,
    val artwork: String? = null,
    val paused: Boolean = false,
    @SerialName("position_seconds")
    val positionSeconds: Double = 0.0,
    @SerialName("duration_seconds")
    val durationSeconds: Double? = null,
)

@Serializable
data class PresenceResponse(
    val sessions: List<WatchingSession> = emptyList(),
    val sharing: Boolean = true,
)

@Serializable
data class TogetherTitle(
    @SerialName("item_type")
    val itemType: String = "",
    @SerialName("item_id")
    val itemId: String = "",
    val title: String = "",
    val artwork: String? = null,
    val profiles: List<String> = emptyList(),
)

@Serializable
data class GuideChannel(
    val id: String,
    val name: String,
    val number: Int? = null,
    val kind: String = "video",
    val description: String? = null,
    val currentTitle: String? = null,
    val currentItemType: String? = null,
    val currentItemId: String? = null,
    val nextTitle: String? = null,
)

@Serializable
data class MusicChannel(
    val id: String,
    val name: String,
    val description: String? = null,
    @SerialName("track_count")
    val trackCount: Int = 0,
    val tracks: List<Int> = emptyList(),
)

@Serializable
data class PlayerSettings(
    @SerialName("share_activity")
    val shareActivity: Boolean = true,
    @SerialName("autoplay_next")
    val autoplayNext: Boolean = true,
    @SerialName("still_watching_after")
    val stillWatchingAfter: Int = 3,
)

data class DeviceProfileInfo(
    val id: Int,
    val name: String,
    val avatarColor: String? = null,
    val avatarUrl: String? = null,
    val isAdmin: Boolean = false,
)

@Serializable
data class SubtitleCandidate(
    @SerialName("candidate_id")
    val candidateId: String = "",
    val provider: String = "",
    val title: String = "",
    val score: Double = 0.0,
    @SerialName("hash_match")
    val hashMatch: Boolean = false,
    @SerialName("download_count")
    val downloadCount: Int = 0,
    val language: String = "en",
    val hi: Boolean = false,
    val forced: Boolean = false,
)

@Serializable
internal data class SubtitleSearchEnvelope(
    val candidates: List<SubtitleCandidate> = emptyList(),
)

@Serializable
internal data class SubtitleDownloadRequest(
    val kind: String,
    @SerialName("item_id")
    val itemId: Int,
    val lang: String,
    @SerialName("candidate_id")
    val candidateId: String,
)

data class VideoRequestModes(
    val movie: String = "none", // "download" | "ask" | "none"
    val show: String = "none",
)

sealed class VideoRequestResult {
    data object Got : VideoRequestResult()
    data class Asked(val already: Boolean) : VideoRequestResult()
    data object OnTheWay : VideoRequestResult()
    data object Owned : VideoRequestResult()
    data class Error(val message: String) : VideoRequestResult()
}

sealed class MusicTrackRequestResult {
    data object Wishlist : MusicTrackRequestResult()
    data object Requested : MusicTrackRequestResult()
    data object Owned : MusicTrackRequestResult()
    data class Error(val message: String) : MusicTrackRequestResult()
}

@Serializable
internal data class VideoRequestPayload(
    val kind: String,
    @SerialName("tmdb_id")
    val tmdbId: Int,
    val title: String,
    val year: String? = null,
    @SerialName("poster_url")
    val posterUrl: String? = null,
    val get: Boolean = false,
)

@Serializable
internal data class MusicTrackRequestPayload(
    val title: String,
    val artist: String,
    val album: String? = null,
    @SerialName("album_id")
    val albumId: Int? = null,
    @SerialName("artist_id")
    val artistId: Int? = null,
    @SerialName("track_id")
    val trackId: Int? = null,
    @SerialName("track_number")
    val trackNumber: Int? = null,
    @SerialName("disc_number")
    val discNumber: Int? = null,
    @SerialName("duration_ms")
    val durationMs: Long? = null,
    val year: Int? = null,
    @SerialName("image_url")
    val imageUrl: String? = null,
)

// ── Customizable Home Layout (1:1 with core/player/home_layout.py) ───────────

@Serializable
data class LayoutRow(
    val id: String,
    val style: String = "standard", // "standard" | "large" | "wide" | "ranked"
    val on: Boolean = true,
)

@Serializable
data class HomeLayout(
    val version: Int = 1,
    val billboard: Boolean = true,
    val rows: List<LayoutRow> = defaultRows(),
) {
    companion object {
        fun defaultRows(): List<LayoutRow> = listOf(
            LayoutRow("continue", "wide", true),
            LayoutRow("watchlist", "standard", true),
            LayoutRow("top-rated", "ranked", true),
            LayoutRow("recent-movies", "standard", true),
            LayoutRow("recent-shows", "standard", true),
            LayoutRow("recent-music", "standard", true),
            LayoutRow("youtube", "wide", true),
            LayoutRow("podcasts", "standard", true),
            LayoutRow("audiobooks", "standard", true),
        )
    }
}

@Serializable
data class RowDef(
    val id: String,
    val label: String,
    val hint: String = "",
    val styles: List<String> = listOf("standard", "large", "wide", "ranked"),
    val style: String = "standard",
    val on: Boolean = true,
)

@Serializable
data class LayoutCatalog(
    val version: Int = 1,
    val rows: List<RowDef> = emptyList(),
)

@Serializable
data class HomeLayoutResponse(
    val layout: HomeLayout = HomeLayout(),
    val catalog: LayoutCatalog = LayoutCatalog(),
    val shared: Boolean = true,
)

// ── Extras, Person, Notes & Discover Playlists ───────────────────────────────

@Serializable
data class TrailerInfo(
    val key: String,
    val name: String? = null,
)

@Serializable
data class ExtraTitle(
    val title: String,
    @SerialName("tmdb_id")
    val tmdbId: Int = 0,
    val kind: String = "movie",
    val poster: String? = null,
    val year: JsonPrimitive? = null,
    @SerialName("library_id")
    val libraryId: Int? = null,
) {
    val yearText: String?
        get() = year?.content?.takeIf { it.isNotBlank() && it != "null" }
}

@Serializable
data class TitleExtras(
    val trailer: TrailerInfo? = null,
    val keywords: List<String> = emptyList(),
    val recommendations: List<ExtraTitle> = emptyList(),
    val similar: List<ExtraTitle> = emptyList(),
)

@Serializable
data class PersonCredit(
    val kind: String = "movie",
    @SerialName("tmdb_id")
    val tmdbId: Int = 0,
    val title: String = "",
    val role: String? = null,
    val department: String? = null,
    val year: JsonPrimitive? = null,
    val poster: String? = null,
    @SerialName("library_id")
    val libraryId: Int? = null,
) {
    val yearText: String?
        get() = year?.content?.takeIf { it.isNotBlank() && it != "null" }
}

@Serializable
data class PersonDetail(
    val name: String,
    val biography: String? = null,
    val birthday: String? = null,
    @SerialName("place_of_birth")
    val placeOfBirth: String? = null,
    val photo: String? = null,
    val credits: List<PersonCredit> = emptyList(),
)

@Serializable
data class PlayerNote(
    val id: Int,
    val body: String,
    @SerialName("created_at")
    val createdAt: String? = null,
)

@Serializable
data class DiscoverPlaylistSummary(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    @SerialName("track_count")
    val trackCount: Int = 0,
    @SerialName("cover_url")
    val coverUrl: String? = null,
)

@Serializable
internal data class NotesEnvelope(val notes: List<PlayerNote> = emptyList())

@Serializable
internal data class NoteCreateRequest(val body: String)

@Serializable
internal data class GuideFavoritesPutRequest(val channels: List<String>)

@Serializable
internal data class HomeLayoutPutRequest(
    val layout: HomeLayout,
    val device: String? = null,
    val shared: Boolean = true,
)

@Serializable
internal data class DiscoverPlaylistsEnvelope(
    val playlists: List<DiscoverPlaylistSummary> = emptyList(),
)

// ── Internal Envelopes ───────────────────────────────────────────────────────

@Serializable
internal data class ArtistsEnvelope(val artists: List<LibArtist> = emptyList())

@Serializable
internal data class ArtistDetailEnvelope(val artist: LibArtistDetail? = null)

@Serializable
internal data class AlbumsEnvelope(val albums: List<LibAlbum> = emptyList())

@Serializable
internal data class AlbumDetailEnvelope(val album: LibAlbumDetail? = null)

@Serializable
internal data class SongsEnvelope(val songs: List<LibSong> = emptyList())

@Serializable
internal data class PlaylistDetailEnvelope(val playlist: PlaylistDetail? = null)

@Serializable
internal data class PodcastsWatchlistEnvelope(val podcasts: List<PodcastSub> = emptyList())

@Serializable
internal data class AudiobooksLibraryEnvelope(val books: List<OwnedBook> = emptyList())

@Serializable
internal data class WatchlistEnvelope(val items: List<WatchlistItem> = emptyList())

@Serializable
internal data class TogetherEnvelope(val titles: List<TogetherTitle> = emptyList())

@Serializable
internal data class GuideFavoritesEnvelope(val channels: List<String> = emptyList())

@Serializable
internal data class MusicChannelsEnvelope(val channels: List<MusicChannel> = emptyList())

@Serializable
internal data class PlayerSettingsEnvelope(val settings: PlayerSettings = PlayerSettings())

@Serializable
internal data class PresenceHeartbeatRequest(
    @SerialName("item_type")
    val itemType: String,
    @SerialName("item_id")
    val itemId: String,
    val title: String,
    val subtitle: String? = null,
    val artwork: String? = null,
    @SerialName("position_seconds")
    val positionSeconds: Double,
    @SerialName("duration_seconds")
    val durationSeconds: Double? = null,
    val paused: Boolean = false,
)

@Serializable
internal data class WatchlistPutRequest(
    @SerialName("item_type")
    val itemType: String,
    @SerialName("item_id")
    val itemId: String,
    val meta: ProgressMeta? = null,
)

/**
 * Helper to decode SoulSync's compact `GET /api/player/video-catalog` wire format
 * (1:1 port of `webui/src/routes/player/-lib/catalog.ts` — handles both positional
 * array-table rows indexed by `fields.movie`/`fields.show` + `genres` dictionary
 * AND standard JSON object lists).
 */
internal object VideoCatalogDecoder {
    fun decode(root: JsonObject): VideoCatalog {
        val fieldsObj = root["fields"] as? JsonObject
        val movieFields = (fieldsObj?.get("movie") as? JsonArray)
            ?.mapIndexedNotNull { idx, el -> el.strOrNull()?.let { it to idx } }
            ?.toMap()
            ?: emptyMap()
        val showFields = (fieldsObj?.get("show") as? JsonArray)
            ?.mapIndexedNotNull { idx, el -> el.strOrNull()?.let { it to idx } }
            ?.toMap()
            ?: emptyMap()
        val genreMap = (root["genres"] as? JsonObject)
            ?.mapNotNull { (k, v) -> v.strOrNull()?.let { k to it } }
            ?.toMap()
            ?: emptyMap()

        val movies = parseMovies(root["movies"], movieFields, genreMap)
        val shows = parseShows(root["shows"], showFields, genreMap)
        val recent = parseRecentEpisodes(root["recent_episodes"])
        val youtube = parseYoutube(root["youtube"])
        val kindsObj = root["kinds"] as? JsonObject
        val kinds = VideoCatalogKinds(
            movies = kindsObj?.get("movies")?.boolOrNull() ?: movies.isNotEmpty(),
            shows = kindsObj?.get("shows")?.boolOrNull() ?: shows.isNotEmpty(),
            youtube = kindsObj?.get("youtube")?.boolOrNull() ?: youtube.isNotEmpty(),
        )
        return VideoCatalog(movies, shows, recent, youtube, kinds)
    }

    private fun JsonElement?.strOrNull(): String? {
        val p = this as? kotlinx.serialization.json.JsonPrimitive ?: return null
        if (p is kotlinx.serialization.json.JsonNull) return null
        return p.content.takeIf { it.isNotEmpty() }
    }

    private fun JsonElement?.intOrNullSafe(): Int? {
        val p = this as? kotlinx.serialization.json.JsonPrimitive ?: return null
        if (p is kotlinx.serialization.json.JsonNull) return null
        return p.intOrNull ?: p.doubleOrNull?.toInt()
    }

    private fun JsonElement?.doubleOrNullSafe(): Double? {
        val p = this as? kotlinx.serialization.json.JsonPrimitive ?: return null
        if (p is kotlinx.serialization.json.JsonNull) return null
        return p.doubleOrNull
    }

    private fun JsonElement?.boolOrNull(): Boolean? {
        val p = this as? kotlinx.serialization.json.JsonPrimitive ?: return null
        if (p is kotlinx.serialization.json.JsonNull) return null
        return p.content.toBooleanStrictOrNull() ?: (p.intOrNull?.let { it != 0 })
    }

    private fun parseGenreIds(el: JsonElement?, genreMap: Map<String, String>): List<String> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { g ->
            val key = g.strOrNull() ?: return@mapNotNull null
            genreMap[key] ?: key.takeIf { it.toIntOrNull() == null }
        }
    }

    private fun parseMovies(
        el: JsonElement?,
        fields: Map<String, Int>,
        genreMap: Map<String, String>,
    ): List<CatalogMovie> {
        val arr = el as? JsonArray ?: return emptyList()
        fun JsonArray.col(name: String): JsonElement? = fields[name]?.let { getOrNull(it) }
        return arr.mapNotNull { item ->
            when (item) {
                is JsonArray -> CatalogMovie(
                    id = item.col("id").intOrNullSafe() ?: return@mapNotNull null,
                    title = item.col("title").strOrNull() ?: return@mapNotNull null,
                    year = item.col("year").intOrNullSafe(),
                    runtimeSeconds = item.col("runtime_seconds").intOrNullSafe(),
                    contentRating = item.col("content_rating").strOrNull(),
                    rating = item.col("rating").doubleOrNullSafe(),
                    genres = parseGenreIds(item.col("genre_ids") ?: item.col("genres"), genreMap),
                    addedAt = item.col("added_at").strOrNull(),
                    hasPoster = item.col("has_poster").boolOrNull() ?: true,
                    hasBackdrop = item.col("has_backdrop").boolOrNull() ?: false,
                    studio = item.col("studio").strOrNull(),
                    tmdbId = item.col("tmdb_id").intOrNullSafe(),
                    collectionId = item.col("collection_id").intOrNullSafe(),
                    collection = item.col("collection").strOrNull(),
                )
                is JsonObject -> CatalogMovie(
                    id = item["id"].intOrNullSafe() ?: return@mapNotNull null,
                    title = item["title"].strOrNull() ?: return@mapNotNull null,
                    year = item["year"].intOrNullSafe(),
                    runtimeSeconds = item["runtime_seconds"].intOrNullSafe(),
                    contentRating = item["content_rating"].strOrNull(),
                    rating = item["rating"].doubleOrNullSafe(),
                    genres = parseGenreIds(item["genres"] ?: item["genre_ids"], genreMap),
                    addedAt = item["added_at"].strOrNull(),
                    hasPoster = item["has_poster"].boolOrNull() ?: true,
                    hasBackdrop = item["has_backdrop"].boolOrNull() ?: false,
                    studio = item["studio"].strOrNull(),
                    tmdbId = item["tmdb_id"].intOrNullSafe(),
                    collectionId = item["collection_id"].intOrNullSafe(),
                    collection = item["collection"].strOrNull(),
                )
                else -> null
            }
        }
    }

    private fun parseShows(
        el: JsonElement?,
        fields: Map<String, Int>,
        genreMap: Map<String, String>,
    ): List<CatalogShow> {
        val arr = el as? JsonArray ?: return emptyList()
        fun JsonArray.col(name: String): JsonElement? = fields[name]?.let { getOrNull(it) }
        return arr.mapNotNull { item ->
            when (item) {
                is JsonArray -> {
                    val epsCount = item.col("episode_count").intOrNullSafe()
                        ?: (item.col("episodes") as? JsonArray)?.size
                        ?: 0
                    CatalogShow(
                        id = item.col("id").intOrNullSafe() ?: return@mapNotNull null,
                        title = item.col("title").strOrNull() ?: return@mapNotNull null,
                        year = item.col("year").intOrNullSafe(),
                        network = item.col("network").strOrNull(),
                        contentRating = item.col("content_rating").strOrNull(),
                        rating = item.col("rating").doubleOrNullSafe(),
                        genres = parseGenreIds(item.col("genre_ids") ?: item.col("genres"), genreMap),
                        addedAt = item.col("added_at").strOrNull(),
                        hasPoster = item.col("has_poster").boolOrNull() ?: true,
                        hasBackdrop = item.col("has_backdrop").boolOrNull() ?: false,
                        episodeCount = epsCount,
                        tmdbId = item.col("tmdb_id").intOrNullSafe(),
                    )
                }
                is JsonObject -> CatalogShow(
                    id = item["id"].intOrNullSafe() ?: return@mapNotNull null,
                    title = item["title"].strOrNull() ?: return@mapNotNull null,
                    year = item["year"].intOrNullSafe(),
                    network = item["network"].strOrNull(),
                    contentRating = item["content_rating"].strOrNull(),
                    rating = item["rating"].doubleOrNullSafe(),
                    genres = parseGenreIds(item["genres"] ?: item["genre_ids"], genreMap),
                    addedAt = item["added_at"].strOrNull(),
                    hasPoster = item["has_poster"].boolOrNull() ?: true,
                    hasBackdrop = item["has_backdrop"].boolOrNull() ?: false,
                    episodeCount = item["episode_count"].intOrNullSafe() ?: 0,
                    tmdbId = item["tmdb_id"].intOrNullSafe(),
                )
                else -> null
            }
        }
    }

    private fun parseRecentEpisodes(el: JsonElement?): List<CatalogRecentEpisode> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            CatalogRecentEpisode(
                id = obj["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                showId = obj["show_id"]?.jsonPrimitive?.intOrNull ?: 0,
                showTitle = obj["show_title"]?.jsonPrimitive?.content ?: "",
                seasonNumber = obj["season_number"]?.jsonPrimitive?.intOrNull ?: 1,
                episodeNumber = obj["episode_number"]?.jsonPrimitive?.intOrNull ?: 1,
                title = obj["title"]?.jsonPrimitive?.content,
                hasStill = obj["has_still"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                addedAt = obj["added_at"]?.jsonPrimitive?.content,
                runtimeSeconds = obj["runtime_seconds"]?.jsonPrimitive?.intOrNull,
            )
        }
    }

    private fun parseYoutube(el: JsonElement?): List<CatalogYoutube> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.content ?: obj["youtube_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            CatalogYoutube(
                id = id,
                youtubeId = obj["youtube_id"]?.jsonPrimitive?.content ?: id,
                title = obj["title"]?.jsonPrimitive?.content ?: "",
                durationSeconds = obj["duration_seconds"]?.jsonPrimitive?.intOrNull,
                publishedAt = obj["published_at"]?.jsonPrimitive?.content,
                thumbnailUrl = obj["thumbnail_url"]?.jsonPrimitive?.content,
                channelId = obj["channel_id"]?.jsonPrimitive?.content,
                channelTitle = obj["channel_title"]?.jsonPrimitive?.content,
                addedAt = obj["added_at"]?.jsonPrimitive?.content,
            )
        }
    }
}
