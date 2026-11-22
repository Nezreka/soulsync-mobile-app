package net.ssync.app.player

import net.ssync.player.OwnedBook
import net.ssync.player.PodcastSub

sealed interface PlayerDestination {
    data object Home : PlayerDestination
    data object Guide : PlayerDestination
    data object Movies : PlayerDestination
    data class MovieDetail(val movieId: Int) : PlayerDestination
    data object Tv : PlayerDestination
    data class ShowDetail(val showId: Int) : PlayerDestination
    data object Music : PlayerDestination
    data class ArtistDetail(val artistId: Int) : PlayerDestination
    data class AlbumDetail(val albumId: Int) : PlayerDestination
    data class PlaylistDetail(val playlistId: Int) : PlayerDestination
    data object YouTube : PlayerDestination
    data class YouTubeChannel(val channelId: String, val title: String) : PlayerDestination
    data object Podcasts : PlayerDestination
    data class PodcastDetail(val sub: PodcastSub) : PlayerDestination
    data object Audiobooks : PlayerDestination
    data class AudiobookDetail(val book: OwnedBook) : PlayerDestination
    data object Watchlist : PlayerDestination
    data object WatchingNow : PlayerDestination
    data object Search : PlayerDestination
    data object Settings : PlayerDestination
}

enum class BottomTab(val label: String) {
    HOME("Home"),
    GUIDE("Guide"),
    MOVIES("Movies"),
    TV("TV"),
    MUSIC("Music"),
    MORE("More"),
}
