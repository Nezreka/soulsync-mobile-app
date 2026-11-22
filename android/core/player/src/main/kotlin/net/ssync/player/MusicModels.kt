package net.ssync.player

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/**
 * Matches SoulSync's `api/serializers.py:serialize_track` response shape.
 */
@Serializable
data class SoulSyncTrackDto(
    val id: JsonPrimitive,
    val title: String = "Untitled",
    @SerialName("track_artist")
    val trackArtist: String? = null,
    @SerialName("artist_name")
    val artistName: String? = null,
    val artist: String? = null,
    @SerialName("album_title")
    val albumTitle: String? = null,
    val album: String? = null,
    val duration: Double? = null,
    @SerialName("duration_ms")
    val durationMs: Long? = null,
    @SerialName("file_path")
    val filePath: String? = null,
    val format: String? = null,
    @SerialName("artwork_url")
    val artworkUrl: String? = null,
) {
    fun toDomain(): MusicTrack {
        val inferredFormat = format?.takeIf { it.isNotBlank() }
            ?: filePath?.substringAfterLast('.', "")?.takeIf { it.length in 2..5 }
        val resolvedDurationMs = durationMs
            ?: duration?.let { d -> if (d > 10_000.0) d.toLong() else (d * 1000.0).toLong() }
            ?: 0L
        return MusicTrack(
            id = id.content,
            title = title,
            artist = trackArtist ?: artistName ?: artist ?: "Unknown Artist",
            album = albumTitle ?: album ?: "",
            durationMs = resolvedDurationMs,
            format = inferredFormat,
            artworkUrl = artworkUrl,
        )
    }
}

data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String = "Unknown Artist",
    val album: String = "",
    val durationMs: Long = 0L,
    val format: String? = null,
    val artworkUrl: String? = null,
)

/**
 * Matches SoulSync's `core/player/playlists.py:summary` response shape.
 */
@Serializable
data class MusicPlaylist(
    val id: JsonPrimitive,
    val name: String,
    val kind: String = "yours",
    val liked: Boolean = false,
    @SerialName("track_count")
    val trackCount: Int = 0,
    @SerialName("image_url")
    val imageUrl: String? = null,
) {
    val idString: String get() = id.content
}

@Serializable
internal data class LibraryTracksEnvelope(
    val success: Boolean = true,
    val data: LibraryTracksData? = null,
)

@Serializable
internal data class LibraryTracksData(
    val tracks: List<SoulSyncTrackDto> = emptyList(),
)

/**
 * Matches `GET /api/player/playlists` -> `{"success": true, "yours": [...], "mirrored": [...]}`.
 */
@Serializable
internal data class PlayerPlaylistsEnvelope(
    val success: Boolean = true,
    val yours: List<MusicPlaylist> = emptyList(),
    val mirrored: List<MusicPlaylist> = emptyList(),
)

/**
 * Matches `PUT /api/player/progress` in `api/player.py`.
 */
@Serializable
internal data class PlaybackProgressRequest(
    @SerialName("item_type")
    val itemType: String,
    @SerialName("item_id")
    val itemId: String,
    @SerialName("position_seconds")
    val positionSeconds: Double,
    @SerialName("duration_seconds")
    val durationSeconds: Double? = null,
    val finished: Boolean,
    val meta: ProgressMeta? = null,
)

sealed interface MusicApiResult<out T> {
    data class Success<T>(val data: T) : MusicApiResult<T>
    data object DeviceRevoked : MusicApiResult<Nothing>
    data class Error(val message: String) : MusicApiResult<Nothing>
}
