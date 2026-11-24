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

data class PlayableDecision(
    val url: String,
    val needsTranscode: Boolean,
    val durationMs: Long = 0L,
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
    val episodes: List<kotlinx.serialization.json.JsonArray> = emptyList(),
)

data class CatalogShowEpisode(
    val id: Int,
    val season: Int,
    val episode: Int,
    val seconds: Int,
    val title: String?,
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

@Serializable
data class HomeDiskSnapshot(
    val baseUrl: String,
    val continueItems: List<ProgressRow> = emptyList(),
    val catalog: VideoCatalog = VideoCatalog(),
    val studioCollections: List<StudioCollection> = emptyList(),
    val homeLayout: HomeLayout = HomeLayout(),
    val layoutShared: Boolean = true,
) {
    companion object {
        private val codec = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            coerceInputValues = true
        }

        fun read(cacheDir: java.io.File, baseUrl: String): HomeDiskSnapshot? = runCatching {
            val file = java.io.File(cacheDir, "home_snapshot_v1.json")
            if (!file.exists()) return null
            val snap = codec.decodeFromString(serializer(), file.readText())
            if (snap.baseUrl == baseUrl && (snap.catalog.movies.isNotEmpty() || snap.catalog.shows.isNotEmpty())) snap else null
        }.getOrNull()

        fun write(cacheDir: java.io.File, snap: HomeDiskSnapshot) {
            runCatching {
                val tmp = java.io.File(cacheDir, "home_snapshot_v1.json.tmp")
                val target = java.io.File(cacheDir, "home_snapshot_v1.json")
                tmp.writeText(codec.encodeToString(serializer(), snap))
                tmp.renameTo(target)
            }
        }
    }
}

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
data class CrewMember(
    val name: String,
    val job: String = "",
    @SerialName("tmdb_id")
    val tmdbId: Int? = null,
    val photo: String? = null,
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
    @Serializable(with = LenientIntSerializer::class)
    val runtimeMinutes: Int? = null,
    @SerialName("has_poster")
    val hasPoster: Boolean = false,
    @SerialName("has_backdrop")
    val hasBackdrop: Boolean = false,
    val studio: String? = null,
    val cast: List<CastMember> = emptyList(),
    val crew: List<CrewMember> = emptyList(),
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
    @Serializable(with = LenientIntSerializer::class)
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
    val crew: List<CrewMember> = emptyList(),
    val seasons: List<DetailSeason> = emptyList(),
)

// ── Music (Library v2 & Playlists & Lyrics) ──────────────────────────────────

@Serializable
data class LibArtist(
    val id: Int,
    val name: String = "",
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
    val title: String = "",
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
    val name: String = "",
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
    @Serializable(with = LenientIntSerializer::class)
    val bitrate: Int? = null,
    @SerialName("quality_tier")
    val qualityTier: String? = null,
)

@Serializable
data class LibTrack(
    val id: Int? = null,
    val title: String = "",
    @SerialName("track_number")
    val trackNumber: Int? = null,
    @SerialName("disc_number")
    val discNumber: Int? = null,
    @Serializable(with = LenientLongSerializer::class)
    val duration: Long? = null,
    val explicit: Boolean = false,
    @SerialName("is_missing")
    val isMissing: Boolean = false,
    @SerialName("file_status")
    val fileStatus: String? = null,
    @SerialName("file_count")
    val fileCount: Int = 0,
    @SerialName("server_track_id")
    val serverTrackId: String? = null,
    val file: LibTrackFile? = null,
) {
    /** True only when this album track actually has a playable local/server file. */
    val isOwned: Boolean
        get() = id != null &&
            !isMissing &&
            !fileStatus.equals("missing", ignoreCase = true) &&
            (file != null || fileCount > 0 || !serverTrackId.isNullOrBlank())
}

@Serializable
data class LibArtistRef(
    val id: Int? = null,
    val name: String = "",
)

@Serializable
data class LibAlbumDetail(
    val id: Int,
    val title: String = "",
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
    val title: String = "",
    @SerialName("artist_name")
    val artistName: String? = null,
    @SerialName("album_title")
    val albumTitle: String? = null,
    @SerialName("album_id")
    val albumId: Int? = null,
    @Serializable(with = LenientLongSerializer::class)
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
    val liked: Boolean = false,
    @SerialName("image_url")
    val imageUrl: String? = null,
    @SerialName("songs")
    val tracks: List<PlaylistTrackItem> = emptyList(),
)

@Serializable
data class SongRefPayload(
    val title: String,
    val artist: String,
    val album: String? = null,
    @SerialName("duration_ms")
    val durationMs: Long? = null,
    @SerialName("lib2_track_id")
    val lib2TrackId: Int? = null,
)

@Serializable
internal data class PlaylistTracksWriteRequest(
    val tracks: List<SongRefPayload>,
)

@Serializable
data class DiscoverPlaylistDetail(
    val key: String = "",
    val name: String = "",
    val blurb: String? = null,
    val songs: List<PlaylistTrackItem> = emptyList(),
)

@Serializable
internal data class DiscoverPlaylistDetailEnvelope(
    val playlist: DiscoverPlaylistDetail? = null,
)

@Serializable
data class CalendarEpisode(
    val id: kotlinx.serialization.json.JsonPrimitive? = null,
    @SerialName("show_id")
    val showId: Int? = null,
    @SerialName("show_title")
    val showTitle: String = "",
    @SerialName("show_tmdb_id")
    val showTmdbId: Int? = null,
    @SerialName("tmdb_id")
    val tmdbId: Int? = null,
    @SerialName("season_number")
    val seasonNumber: Int = 1,
    @SerialName("episode_number")
    val episodeNumber: Int = 1,
    val title: String? = null,
    @SerialName("air_date")
    val airDate: String = "",
    @SerialName("airs_time")
    val airsTime: String? = null,
    val network: String? = null,
    @SerialName("still_url")
    val stillUrl: String? = null,
    @SerialName("backdrop_url")
    val backdropUrl: String? = null,
    @SerialName("poster_url")
    val posterUrl: String? = null,
    @SerialName("has_still")
    val hasStill: kotlinx.serialization.json.JsonPrimitive? = null,
    @SerialName("show_has_backdrop")
    val showHasBackdrop: kotlinx.serialization.json.JsonPrimitive? = null,
    val overview: String? = null,
    @SerialName("runtime_minutes")
    @Serializable(with = LenientIntSerializer::class)
    val runtimeMinutes: Int? = null,
    val rating: Double? = null,
    @SerialName("show_year")
    val showYear: Int? = null,
    @SerialName("show_status")
    val showStatus: String? = null,
    @SerialName("not_in_library")
    val notInLibrary: Boolean = false,
    val acq: String = "upcoming",
    val progress: Double? = null,
) {
    val resolvedTmdbId: Int? get() = showTmdbId ?: tmdbId
    val episodeIdInt: Int? get() = id?.content?.toIntOrNull()
    val hasStillBool: Boolean get() = hasStill?.content?.let { it == "true" || it == "1" } ?: false
    val showHasBackdropBool: Boolean get() = showHasBackdrop?.content?.let { it == "true" || it == "1" } ?: false
}

@Serializable
data class CalendarMovie(
    val id: Int? = null,
    @SerialName("movie_id")
    val movieId: Int? = null,
    @SerialName("tmdb_id")
    val tmdbId: Int = 0,
    val title: String = "",
    val year: Int? = null,
    val date: String? = null,
    val type: String? = null,
    @SerialName("release_date")
    val releaseDate: String? = null,
    @SerialName("event_date")
    val eventDate: String? = null,
    @SerialName("event_kind")
    val eventKind: String? = null,
    @SerialName("poster_url")
    val posterUrl: String? = null,
    @SerialName("library_id")
    val libraryId: Int? = null,
    val acq: String = "upcoming",
    val progress: Double? = null,
) {
    val effectiveDate: String get() = date ?: eventDate ?: releaseDate ?: ""
    val effectiveType: String get() = type ?: eventKind ?: "available"
    val effectiveLibraryId: Int? get() = movieId ?: libraryId
}

@Serializable
data class VideoCalendarResponse(
    val today: String = "",
    val start: String = "",
    val end: String = "",
    val days: Int = 14,
    val episodes: List<CalendarEpisode> = emptyList(),
    val movies: List<CalendarMovie> = emptyList(),
)

@Serializable
data class VideoCalendarFollowedEnvelope(
    val episodes: List<CalendarEpisode> = emptyList(),
)

@Serializable
data class PlayerDownloadRow(
    val id: Int = 0,
    @SerialName("profile_id")
    val profileId: Int = 0,
    @SerialName("profile_name")
    val profileName: String? = null,
    @SerialName("device_id")
    val deviceId: String = "",
    @SerialName("device_name")
    val deviceName: String = "",
    @SerialName("item_type")
    val itemType: String = "",
    @SerialName("item_id")
    val itemId: String = "",
    val title: String = "",
    val subtitle: String? = null,
    val artwork: String? = null,
    val quality: String = "original",
    @SerialName("size_bytes")
    val sizeBytes: Long = 0L,
    val status: String = "downloaded",
    @SerialName("downloaded_at")
    val downloadedAt: String = "",
    @SerialName("removed_at")
    val removedAt: String? = null,
)

@Serializable
data class MyDownloadsResponse(
    val success: Boolean = false,
    @SerialName("allow_downloads")
    val allowDownloads: Boolean = true,
    @SerialName("max_items")
    val maxItems: Int = 0,
    @SerialName("is_admin")
    val isAdmin: Boolean = false,
    val downloads: List<PlayerDownloadRow> = emptyList(),
)

@Serializable
data class AdminUserSummary(
    val id: Int = 0,
    val name: String = "",
    @SerialName("is_admin")
    val isAdmin: Boolean = false,
    @SerialName("avatar_color")
    val avatarColor: String? = null,
    @SerialName("allow_downloads")
    val allowDownloads: Boolean = true,
    @SerialName("max_items")
    val maxItems: Int = 0,
    @SerialName("active_downloads")
    val activeDownloads: Int = 0,
    @SerialName("removed_downloads")
    val removedDownloads: Int = 0,
    @SerialName("active_bytes")
    val activeBytes: Long = 0L,
    @SerialName("active_devices")
    val activeDevices: Int = 0,
    @SerialName("last_download_at")
    val lastDownloadAt: String? = null,
)

@Serializable
data class AdminDownloadTotals(
    @SerialName("active_downloads")
    val activeDownloads: Int = 0,
    @SerialName("removed_downloads")
    val removedDownloads: Int = 0,
    @SerialName("active_bytes")
    val activeBytes: Long = 0L,
)

@Serializable
data class AdminUsersOverview(
    val success: Boolean = false,
    val users: List<AdminUserSummary> = emptyList(),
    val downloads: List<PlayerDownloadRow> = emptyList(),
    val totals: AdminDownloadTotals = AdminDownloadTotals(),
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
    @Serializable(with = LenientIntSerializer::class)
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
    val addedAt: Double? = null,
)

@Serializable
data class SocialProfileRef(
    val name: String = "Someone",
    @SerialName("avatar_color")
    val avatarColor: String? = null,
    @SerialName("avatar_url")
    val avatarUrl: String? = null,
)

@Serializable
data class PresenceChannelRef(
    val id: String = "",
    val name: String = "",
    val number: Int? = null,
)

@Serializable
data class WatchingSession(
    @SerialName("profile_id")
    val profileId: Int = 0,
    val profile: SocialProfileRef? = null,
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
    val meta: ProgressMeta? = null,
    val playing: Boolean = true,
    val paused: Boolean = false,
    val live: Boolean = false,
    val channel: PresenceChannelRef? = null,
    val position: Double = 0.0,
    val duration: Double? = null,
    @SerialName("position_seconds")
    val positionSeconds: Double = 0.0,
    @SerialName("duration_seconds")
    val durationSeconds: Double? = null,
) {
    val resolvedName: String get() = profile?.name?.takeIf { it.isNotBlank() } ?: profileName.ifBlank { "Someone" }
    val resolvedTitle: String get() = meta?.title?.takeIf { it.isNotBlank() } ?: title.ifBlank { "Playing" }
    val resolvedSubtitle: String? get() = meta?.subtitle?.takeIf { it.isNotBlank() } ?: subtitle
    val resolvedArtwork: String? get() = meta?.backdrop ?: meta?.artwork ?: artwork
    val resolvedPosition: Double get() = if (position > 0.0) position else positionSeconds
    val resolvedDuration: Double? get() = duration ?: durationSeconds
    val isPlayingNow: Boolean get() = playing && !paused
}

@Serializable
data class PresenceResponse(
    val sessions: List<WatchingSession> = emptyList(),
    val sharing: Boolean = true,
)

@Serializable
data class TogetherPlace(
    @SerialName("item_type")
    val itemType: String = "",
    @SerialName("item_id")
    val itemId: String = "",
    val position: Double = 0.0,
    val duration: Double? = null,
    val finished: Boolean = false,
    val meta: ProgressMeta? = null,
    @SerialName("profile_id")
    val profileId: Int = 0,
    val profile: SocialProfileRef? = null,
)

@Serializable
data class TogetherTitle(
    val key: String = "",
    val mine: TogetherPlace? = null,
    val others: List<TogetherPlace> = emptyList(),
    @SerialName("item_type")
    val itemType: String = "",
    @SerialName("item_id")
    val itemId: String = "",
    val title: String = "",
    val artwork: String? = null,
    val profiles: List<String> = emptyList(),
)

// ── Watch Parties (Watch Together + Theater Waiting Rooms) ───────────────────

@Serializable
data class WatchPartyLobbyTrack(
    @SerialName("track_id")
    val trackId: String,
    val title: String = "Lobby Music",
    val artist: String = "",
    @SerialName("album_id")
    val albumId: Int? = null,
    val artwork: String? = null,
    @SerialName("duration_ms")
    val durationMs: Long? = null,
    @SerialName("live_position")
    val livePosition: Double = 0.0,
)

@Serializable
data class WatchPartyMember(
    @SerialName("profile_id")
    val profileId: Int,
    val profile: SocialProfileRef? = null,
    val status: String = "joined",
    val ready: Boolean = false,
    @SerialName("is_host")
    val isHost: Boolean = false,
    val you: Boolean = false,
)

@Serializable
data class WatchPartyMessage(
    val id: Int = 0,
    @SerialName("profile_id")
    val profileId: Int = 0,
    val profile: SocialProfileRef? = null,
    val kind: String = "chat", // "chat" | "reaction" | "system"
    val body: String = "",
    @SerialName("created_at")
    val createdAt: Double = 0.0,
    val you: Boolean = false,
)

@Serializable
data class WatchPartyPeeBreak(
    @SerialName("profile_id")
    val profileId: Int = 0,
    val profile: SocialProfileRef? = null,
    @SerialName("started_at")
    val startedAt: Double = 0.0,
    @SerialName("ends_at")
    val endsAt: Double = 0.0,
)

@Serializable
data class WatchParty(
    val id: String,
    @SerialName("host_profile_id")
    val hostProfileId: Int = 0,
    @SerialName("host_profile")
    val hostProfile: SocialProfileRef? = null,
    @SerialName("is_host")
    val isHost: Boolean = false,
    @SerialName("is_member")
    val isMember: Boolean = false,
    @SerialName("can_control")
    val canControl: Boolean = false,
    @SerialName("majority_ready")
    val majorityReady: Boolean = false,
    val title: String = "Watch Party",
    @SerialName("item_type")
    val itemType: String = "movie",
    @SerialName("item_id")
    val itemId: String = "",
    val meta: ProgressMeta? = null,
    @SerialName("pee_break")
    val peeBreak: WatchPartyPeeBreak? = null,
    @SerialName("resume_at")
    val resumeAt: Double? = null,
    @SerialName("pee_cooldown_remaining")
    val peeCooldownRemaining: Int = 0,
    @SerialName("show_id")
    val showId: Int? = null,
    val status: String = "lobby", // "scheduled" | "lobby" | "playing" | "paused" | "ended"
    @SerialName("scheduled_for")
    val scheduledFor: Double? = null,
    @SerialName("control_mode")
    val controlMode: String = "host",
    val position: Double = 0.0,
    @SerialName("live_position")
    val livePosition: Double = 0.0,
    @SerialName("server_now")
    val serverNow: Double = 0.0,
    @SerialName("lobby_track")
    val lobbyTrack: WatchPartyLobbyTrack? = null,
    val members: List<WatchPartyMember> = emptyList(),
    @SerialName("member_count")
    val memberCount: Int = 0,
    @SerialName("ready_count")
    val readyCount: Int = 0,
    val messages: List<WatchPartyMessage> = emptyList(),
)

@Serializable
internal data class WatchPartiesEnvelope(val parties: List<WatchParty> = emptyList())

@Serializable
internal data class WatchPartyEnvelope(val party: WatchParty)

@Serializable
internal data class WatchPartyMessageEnvelope(val message: WatchPartyMessage)

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
data class ChannelSpecPerson(
    val id: Int,
    val name: String = "",
)

@Serializable
data class GuideChannelSpec(
    val name: String = "",
    val kinds: List<String> = listOf("movies", "tv"),
    val genres: List<String> = emptyList(),
    val decades: List<Int> = emptyList(),
    val studios: List<String> = emptyList(),
    val networks: List<String> = emptyList(),
    @SerialName("youtube_channels")
    val youtubeChannels: List<String> = emptyList(),
    val collection: String? = null,
    @SerialName("min_score")
    val minScore: Double? = null,
    val kids: Boolean = false,
    @SerialName("added_days")
    val addedDays: Int? = null,
    val show: Int? = null,
    val people: List<ChannelSpecPerson> = emptyList(),
    val order: String = "shuffle",
)

@Serializable
data class SavedGuideChannel(
    val id: String,
    val scope: String = "mine", // "server" | "mine"
    val spec: GuideChannelSpec = GuideChannelSpec(),
)

@Serializable
data class GuideLineupEntry(
    @SerialName("channel_id")
    val channelId: String,
    val hidden: Boolean = false,
    val name: String? = null,
)

data class MusicChannelTrack(
    val id: Int,
    val title: String,
    val artist: String,
    val seconds: Int,
    val albumId: Int?,
)

@Serializable
data class MusicChannel(
    val id: String,
    val name: String,
    val kind: String = "genre", // "genre" | "vibe"
    val count: Int = 0,
    val description: String? = null,
    @SerialName("track_count")
    val trackCount: Int = 0,
    val tracks: List<Int> = emptyList(),
    @kotlinx.serialization.Transient
    val trackItems: List<MusicChannelTrack> = emptyList(),
)

@Serializable
data class PlayerSettings(
    @SerialName("share_activity")
    val shareActivity: Boolean = true,
    @SerialName("autoplay_next")
    val autoplayNext: Boolean = true,
    @SerialName("still_watching_after")
    val stillWatchingAfter: Int = 3,
    @SerialName("show_movie_ratings")
    val showMovieRatings: Boolean = false,
    @SerialName("show_tv_episode_count")
    val showTvEpisodeCount: Boolean = true,
    @SerialName("show_year_on_cards")
    val showYearOnCards: Boolean = true,
    @SerialName("accent_color")
    val accentColor: String = "emerald",
    @SerialName("commercials_enabled")
    val commercialsEnabled: Boolean = false,
    @SerialName("commercials_era")
    val commercialsEra: String = "auto",
    @SerialName("commercials_mode")
    val commercialsMode: String = "cable",
)

@Serializable
data class CommercialClip(
    val id: String,
    val title: String = "Retro Commercial",
    val era: String = "mix",
    val year: Int? = null,
    @SerialName("duration_seconds")
    val durationSeconds: Int = 30,
    val source: String = "archive",
    @SerialName("stream_path")
    val streamPath: String = "",
)

@Serializable
data class CommercialsConfig(
    val enabled: Boolean = false,
    val era: String = "auto",
    val mode: String = "cable",
    @SerialName("can_edit")
    val canEdit: Boolean = false,
    val clips: List<CommercialClip> = emptyList(),
    val status: CommercialsStatus? = null,
)

@Serializable
data class PlayerIssue(
    val id: Int = 0,
    @SerialName("profile_id")
    val profileId: Int = 0,
    @SerialName("profile_name")
    val profileName: String = "",
    @SerialName("item_type")
    val itemType: String = "",
    @SerialName("item_id")
    val itemId: String = "",
    val title: String = "",
    val subtitle: String = "",
    @SerialName("file_path")
    val filePath: String = "",
    val category: String = "playback",
    val note: String = "",
    @SerialName("position_sec")
    val positionSec: Int = 0,
    val status: String = "open",
    @SerialName("admin_note")
    val adminNote: String = "",
    @SerialName("created_at")
    val createdAt: Long = 0L,
    @SerialName("resolved_at")
    val resolvedAt: Long? = null,
)

@Serializable
data class PlayerIssuesResponse(
    val success: Boolean = false,
    @SerialName("can_manage")
    val canManage: Boolean = false,
    @SerialName("open_count")
    val openCount: Int = 0,
    val issues: List<PlayerIssue> = emptyList(),
)

@Serializable
internal data class PlayerIssueCreateRequest(
    @SerialName("item_type")
    val itemType: String,
    @SerialName("item_id")
    val itemId: String,
    val title: String,
    val subtitle: String = "",
    val category: String = "playback",
    val note: String = "",
    @SerialName("position_sec")
    val positionSec: Int = 0,
)

@Serializable
internal data class PlayerIssueUpdateRequest(
    val status: String? = null,
    @SerialName("admin_note")
    val adminNote: String? = null,
)

@Serializable
internal data class PlayerIssueEnvelope(
    val success: Boolean = false,
    val issue: PlayerIssue? = null,
)

@Serializable
internal data class CommercialsConfigPutRequest(
    val enabled: Boolean? = null,
    val era: String? = null,
    val mode: String? = null,
)

@Serializable
data class CommercialsStatus(
    val ready: Boolean = false,
    @SerialName("total_count")
    val totalCount: Int = 0,
    @SerialName("cached_count")
    val cachedCount: Int = 0,
    @SerialName("local_count")
    val localCount: Int = 0,
    @SerialName("cache_mb")
    val cacheMb: Double = 0.0,
    @SerialName("max_cache_mb")
    val maxCacheMb: Int = 500,
    val warming: Boolean = false,
    val error: String? = null,
)

@Serializable
internal data class CommercialBreakEnvelope(
    val clips: List<CommercialClip> = emptyList(),
)

@Serializable
data class CommercialScheduleItem(
    val id: String,
    val title: String = "",
    val year: Int? = null,
    @SerialName("duration_seconds")
    val durationSeconds: Int = 1800,
)

@Serializable
internal data class CommercialScheduleRequest(
    val items: List<CommercialScheduleItem>,
    val era: String? = null,
    val mode: String? = null,
    @SerialName("channel_id")
    val channelId: String? = null,
    @SerialName("channel_name")
    val channelName: String? = null,
    @SerialName("min_gap_seconds")
    val minGapSeconds: Int = 300,
)

@Serializable
internal data class CommercialScheduleEnvelope(
    val breaks: Map<String, List<CommercialClip>> = emptyMap(),
)

data class DeviceProfileInfo(
    val id: Int,
    val name: String,
    val avatarColor: String? = null,
    val avatarUrl: String? = null,
    val isAdmin: Boolean = false,
    val needs: String? = null, // "pin" | "password" | null
    val active: Boolean = false,
)

data class CastTokenResponse(
    val token: String,
    val streamPath: String,
    val streamUrl: String? = null,
    val artworkBaseUrl: String? = null,
    val contentType: String,
    val durationMs: Long = 0L,
    val transcoded: Boolean = false,
    val hls: Boolean = false,
    val expiresIn: Int = 21600,
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
    val season: Int? = null,
    val episode: Int? = null,
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
            LayoutRow("new-episodes", "wide", true),
            LayoutRow("live-guide", "wide", true),
            LayoutRow("seasonal", "standard", true),
            LayoutRow("collections", "standard", true),
            LayoutRow("top-rated", "ranked", true),
            LayoutRow("recent-movies", "standard", true),
            LayoutRow("recent-shows", "standard", true),
            LayoutRow("discovery-playlists", "standard", true),
            LayoutRow("recent-music", "standard", true),
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
    /** "device" (this screen's own), "all" (every screen's), or null (the default) */
    val scope: String? = null,
) {
    /** this screen follows the profile's shared rows, not its own */
    val shared: Boolean get() = scope != "device"
}

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
data class FranchiseCollection(
    val id: Int = 0,
    val name: String? = null,
    val poster: String? = null,
    val items: List<ExtraTitle> = emptyList(),
)

@Serializable
data class TitleExtras(
    val trailer: TrailerInfo? = null,
    val keywords: List<String> = emptyList(),
    val recommendations: List<ExtraTitle> = emptyList(),
    val similar: List<ExtraTitle> = emptyList(),
    val collection: FranchiseCollection? = null,
)

@Serializable
data class StudioCollection(
    val id: Int,
    val name: String,
    @SerialName("media_type")
    val mediaType: String = "movie",
    val summary: String? = null,
    val poster: String? = null,
    @SerialName("member_posters")
    val memberPosters: List<String> = emptyList(),
    val pinned: Boolean = false,
    val members: List<Int> = emptyList(),
)

@Serializable
internal data class StudioCollectionsEnvelope(
    val collections: List<StudioCollection> = emptyList(),
)

@Serializable
data class EpisodeExtra(
    @SerialName("guest_stars")
    val guestStars: List<CastMember> = emptyList(),
    val crew: List<CrewMember> = emptyList(),
    @SerialName("still_url")
    val stillUrl: String? = null,
    val rating: Double? = null,
    val overview: String? = null,
    @SerialName("runtime_minutes")
    @Serializable(with = LenientIntSerializer::class)
    val runtimeMinutes: Int? = null,
    @SerialName("air_date")
    val airDate: String? = null,
)

@Serializable
internal data class VideoIssueCreateRequest(
    @SerialName("entity_type")
    val entityType: String,
    @SerialName("entity_id")
    val entityId: Int,
    val category: String,
    val title: String,
    val description: String? = null,
)

@Serializable
internal data class VideoIssueResponse(
    val success: Boolean = false,
    val error: String? = null,
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
    /** epoch seconds (the server stores a REAL) */
    @SerialName("created_at")
    val createdAt: Double? = null,
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
    @SerialName("cover_tiles")
    val coverTiles: List<String> = emptyList(),
    val sectionLabel: String = "",
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
    /** this screen's id, or "*" for every screen of the profile */
    val device: String,
    /** saving for every screen drops this screen's own layout */
    @SerialName("from_device")
    val fromDevice: String? = null,
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

/** /api/podcasts/show answers {success, show}: the show is inside it */
@Serializable
internal data class PodcastShowEnvelope(val show: PodcastShow? = null, val error: String? = null)

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
    val session: String,
    @SerialName("item_type")
    val itemType: String,
    @SerialName("item_id")
    val itemId: String,
    val position: Double,
    val duration: Double? = null,
    val playing: Boolean = true,
    val live: Boolean = false,
    val channel: PresenceChannelRef? = null,
    val meta: ProgressMeta? = null,
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
                    val rawEps = (item.col("episodes") as? JsonArray)
                        ?.mapNotNull { it as? JsonArray }
                        .orEmpty()
                    val epsCount = item.col("episode_count").intOrNullSafe()
                        ?: rawEps.size
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
                        episodes = rawEps,
                    )
                }
                is JsonObject -> {
                    val rawEps = (item["episodes"] as? JsonArray)
                        ?.mapNotNull { it as? JsonArray }
                        .orEmpty()
                    CatalogShow(
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
                        episodeCount = item["episode_count"].intOrNullSafe() ?: rawEps.size,
                        tmdbId = item["tmdb_id"].intOrNullSafe(),
                        episodes = rawEps,
                    )
                }
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
