package net.ssync.app.player

/**
 * a notification's link (a web /player/... path) to the app's screen for it.
 * pages the app opens from an object it doesn't have here (a podcast, a book,
 * a home videos library) land on their hub. null: no screen fits, so the
 * caller opens the notifications list
 */
object NotificationRoutes {

    fun destinationFor(link: String?): PlayerDestination? {
        val raw = link?.trim().orEmpty()
        if (raw.isEmpty()) return null
        // a full address works too: only its path counts
        val afterHost = if ("://" in raw) "/" + raw.substringAfter("://").substringAfter('/', "") else raw
        val noFragment = afterHost.substringBefore('#')
        val path = noFragment.substringBefore('?').trimEnd('/')
        val query = parseQuery(noFragment.substringAfter('?', ""))
        if (path != "/player" && !path.startsWith("/player/")) return null
        val seg = path.removePrefix("/player").split('/').filter { it.isNotEmpty() }.map(::decode)
        val first = seg.getOrNull(0) ?: return PlayerDestination.Home
        val second = seg.getOrNull(1)
        val id2 = second?.toIntOrNull()

        return when (first) {
            "home" -> PlayerDestination.Home
            "guide" -> PlayerDestination.Guide
            "calendar" -> PlayerDestination.Calendar
            "downloads" -> PlayerDestination.Downloads
            "admin-users" -> PlayerDestination.AdminUsers
            "settings" -> PlayerDestination.Settings
            "notifications" -> PlayerDestination.Notifications
            "watchlist" -> PlayerDestination.Watchlist
            "watching", "party", "watch-party" -> PlayerDestination.WatchingNow
            "search" -> query["q"]?.takeIf { it.isNotBlank() }?.let { PlayerDestination.SearchQuery(it) }
                ?: PlayerDestination.Search
            "movies" -> if (id2 != null) PlayerDestination.MovieDetail(id2) else PlayerDestination.Movies
            "tv" -> when {
                id2 == null -> PlayerDestination.Tv
                seg.getOrNull(2) == "episode" && seg.getOrNull(3)?.toIntOrNull() != null ->
                    PlayerDestination.EpisodeDetail(id2, seg[3].toInt())
                else -> PlayerDestination.ShowDetail(
                    showId = id2,
                    initialSeason = (seg.getOrNull(2)?.takeIf { seg.getOrNull(2) == "season" }?.let { seg.getOrNull(3) }
                        ?: query["season"])?.toIntOrNull(),
                    highlightEpisode = query["episode"]?.toIntOrNull(),
                )
            }
            "person" -> id2?.let { PlayerDestination.PersonDetail(it, query["name"].orEmpty()) }
            "music" -> when (second) {
                null -> PlayerDestination.Music
                "playlists" -> PlayerDestination.Playlists
                "playlist" -> seg.getOrNull(2)?.toIntOrNull()?.let { PlayerDestination.PlaylistDetail(it) }
                    ?: PlayerDestination.Playlists
                "artist" -> seg.getOrNull(2)?.toIntOrNull()?.let { PlayerDestination.ArtistDetail(it) }
                    ?: PlayerDestination.Music
                "album" -> seg.getOrNull(2)?.toIntOrNull()?.let {
                    PlayerDestination.AlbumDetail(it, highlightTrackId = query["track"]?.toIntOrNull())
                } ?: PlayerDestination.Music
                else -> PlayerDestination.Music
            }
            "youtube" -> second?.let { PlayerDestination.YouTubeChannel(it, query["title"] ?: "Channel") }
                ?: PlayerDestination.YouTube
            "podcasts" -> PlayerDestination.Podcasts
            "audiobooks" -> PlayerDestination.Audiobooks
            "home-videos" -> PlayerDestination.Home
            // a title not in the library: its hub, where it can be found or requested
            "title" -> when (second) {
                "movie" -> PlayerDestination.Movies
                "tv", "show" -> PlayerDestination.Tv
                else -> null
            }
            "collections" -> PlayerDestination.Movies
            else -> null
        }
    }

    /** the test notification's link: settings, open on Notifications */
    fun opensNotificationSettings(link: String?): Boolean {
        val raw = link?.trim().orEmpty()
        if (destinationFor(raw) != PlayerDestination.Settings) return false
        val q = parseQuery(raw.substringBefore('#').substringAfter('?', ""))
        return q["section"] == "notifications" || raw.substringBefore('?').trimEnd('/').endsWith("/notifications")
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val k = decode(part.substringBefore('='))
            val v = decode(part.substringAfter('=', ""))
            k to v
        }.toMap()

    private fun decode(s: String): String =
        runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
