package net.ssync.player

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String = "Unknown Artist",
    val album: String = "",
    @SerialName("duration_ms")
    val durationMs: Long = 0L,
    val format: String? = null,
    @SerialName("artwork_url")
    val artworkUrl: String? = null,
)

@Serializable
data class MusicPlaylist(
    val id: String,
    val name: String,
    @SerialName("track_count")
    val trackCount: Int = 0,
)

@Serializable
data class TrackListResponse(
    val tracks: List<MusicTrack> = emptyList(),
)

@Serializable
data class PlaylistListResponse(
    val playlists: List<MusicPlaylist> = emptyList(),
)

@Serializable
data class PlaybackProgressRequest(
    @SerialName("track_id")
    val trackId: String,
    @SerialName("position_ms")
    val positionMs: Long,
    val completed: Boolean = false,
)

sealed interface MusicApiResult<out T> {
    data class Success<T>(val data: T) : MusicApiResult<T>
    data object DeviceRevoked : MusicApiResult<Nothing>
    data class Error(val message: String) : MusicApiResult<Nothing>
}
