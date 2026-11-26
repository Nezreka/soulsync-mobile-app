package net.ssync.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationRoutesTest {

    private fun dest(link: String?) = NotificationRoutes.destinationFor(link)

    @Test
    fun titlePages() {
        assertEquals(PlayerDestination.MovieDetail(42), dest("/player/movies/42"))
        assertEquals(PlayerDestination.ShowDetail(7), dest("/player/tv/7"))
        assertEquals(PlayerDestination.ShowDetail(7, initialSeason = 3), dest("/player/tv/7?season=3"))
        assertEquals(PlayerDestination.ShowDetail(7, initialSeason = 2, highlightEpisode = 5), dest("/player/tv/7?season=2&episode=5"))
        assertEquals(PlayerDestination.EpisodeDetail(7, 99), dest("/player/tv/7/episode/99"))
        assertEquals(PlayerDestination.PersonDetail(5, "Ana de Armas"), dest("/player/person/5?name=Ana%20de%20Armas"))
    }

    @Test
    fun music() {
        assertEquals(PlayerDestination.AlbumDetail(12), dest("/player/music/album/12"))
        assertEquals(PlayerDestination.AlbumDetail(12, highlightTrackId = 3), dest("/player/music/album/12?track=3"))
        assertEquals(PlayerDestination.ArtistDetail(4), dest("/player/music/artist/4"))
        assertEquals(PlayerDestination.PlaylistDetail(9), dest("/player/music/playlist/9"))
        assertEquals(PlayerDestination.Playlists, dest("/player/music/playlists"))
        assertEquals(PlayerDestination.Music, dest("/player/music/genre/Jazz"))
        assertEquals(PlayerDestination.Music, dest("/player/music"))
    }

    @Test
    fun hubsAndPlaces() {
        assertEquals(PlayerDestination.Home, dest("/player"))
        assertEquals(PlayerDestination.Home, dest("/player/"))
        assertEquals(PlayerDestination.Movies, dest("/player/movies"))
        assertEquals(PlayerDestination.Movies, dest("/player/movies/collection/10"))
        assertEquals(PlayerDestination.Tv, dest("/player/tv/network/HBO"))
        assertEquals(PlayerDestination.Watchlist, dest("/player/watchlist"))
        assertEquals(PlayerDestination.WatchingNow, dest("/player/watching?party=abc"))
        assertEquals(PlayerDestination.Downloads, dest("/player/downloads"))
        assertEquals(PlayerDestination.Settings, dest("/player/settings/you"))
        assertEquals(PlayerDestination.Notifications, dest("/player/notifications"))
        assertEquals(PlayerDestination.Calendar, dest("/player/calendar"))
        assertEquals(PlayerDestination.Guide, dest("/player/guide"))
    }

    @Test
    fun pagesThatNeedAnObjectLandOnTheirHub() {
        assertEquals(PlayerDestination.Podcasts, dest("/player/podcasts/17"))
        assertEquals(PlayerDestination.Audiobooks, dest("/player/audiobooks/B00ABC"))
        assertEquals(PlayerDestination.Home, dest("/player/home-videos/3"))
        assertEquals(PlayerDestination.Movies, dest("/player/title/movie/603"))
        assertEquals(PlayerDestination.Tv, dest("/player/title/tv/1399"))
    }

    @Test
    fun youtubeAndSearch() {
        assertEquals(PlayerDestination.YouTubeChannel("UC123", "Channel"), dest("/player/youtube/UC123"))
        assertEquals(PlayerDestination.YouTube, dest("/player/youtube"))
        assertEquals(PlayerDestination.SearchQuery("blade runner"), dest("/player/search?q=blade+runner"))
        assertEquals(PlayerDestination.Search, dest("/player/search"))
    }

    @Test
    fun fullAddressesAndFragmentsWork() {
        assertEquals(PlayerDestination.MovieDetail(42), dest("https://192-168-1-2.x.ssync.net:8008/player/movies/42"))
        assertEquals(PlayerDestination.MovieDetail(42), dest("/player/movies/42/#cast"))
    }

    @Test
    fun theTestNotificationOpensNotificationSettings() {
        val link = "/player/settings?section=notifications"
        assertEquals(PlayerDestination.Settings, dest(link))
        assertEquals(true, NotificationRoutes.opensNotificationSettings(link))
        assertEquals(true, NotificationRoutes.opensNotificationSettings("/player/settings/notifications"))
        assertEquals(false, NotificationRoutes.opensNotificationSettings("/player/settings"))
        assertEquals(false, NotificationRoutes.opensNotificationSettings("/player/movies/1?section=notifications"))
    }

    @Test
    fun theServersOwnLinks() {
        // a request outcome, an admin message and the digest all link to /player
        assertEquals(PlayerDestination.Home, dest("/player"))
        assertEquals(PlayerDestination.WatchingNow, dest("/player/watching?party=17"))
    }

    @Test
    fun nothingFitsGivesNull() {
        assertNull(dest(null))
        assertNull(dest(""))
        assertNull(dest("/settings"))
        assertNull(dest("/player/no-such-page"))
        assertNull(dest("/player/person/abc"))
        assertNull(dest("javascript:alert(1)"))
    }
}
