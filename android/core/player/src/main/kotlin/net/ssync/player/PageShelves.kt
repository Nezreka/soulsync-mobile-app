package net.ssync.player

import kotlin.random.Random

/**
 * The rows of the Movies and TV pages that are built from the library alone,
 * by the row ids of SoulSync's page layout contract
 * (core/player/page_layout.py). The rules are the web player's
 * (webui/src/routes/player/-ui/browse.tsx), number for number, so a row
 * means the same thing on a phone, a TV and in a browser.
 *
 * Pure: no network, no clock of its own ([year] and [dayKey] come in), so it
 * is unit tested (PageShelvesTest).
 */
data class Shelf<T>(
    /** unique on the page: the row id, or the row id plus what it is of */
    val key: String,
    val title: String,
    val items: List<T>,
    /** a filter the "see all" of this shelf applies in the library tab */
    val genre: String? = null,
    val decade: String? = null,
)

object PageShelves {
    const val SHELF_SIZE = 24
    private val FAMILY_MOVIE = setOf("G", "PG")
    private val FAMILY_SHOW = setOf("TV-Y", "TV-Y7", "TV-G", "TV-PG")

    /** a different handful each day, the same all day */
    private fun <T> todays(items: List<T>, salt: String, dayKey: String): List<T> =
        items.shuffled(Random("$salt|$dayKey".hashCode()))

    private fun topGenres(genres: List<List<String>>, n: Int): List<String> =
        genres.flatten().groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(n).map { it.key }

    /** the YouTube page's rows that are cut from the videos themselves, with the
     *  web player's numbers (webui/src/routes/player/youtube/index.tsx).
     *  [nowMs] is the clock; null for a row that isn't one of these. */
    fun youtube(rowId: String, videos: List<YtVideo>, nowMs: Long): Shelf<YtVideo>? {
        val unique = videos.distinctBy { it.id }
        fun length(v: YtVideo) = v.durationSeconds ?: 0
        val picked = when (rowId) {
            "this-week" -> "This week" to unique.filter { v ->
                val at = publishedMs(v.publishedAt)
                at != null && at >= nowMs - 7 * 86_400_000L
            }
            "quick" -> "Quick watches · under 10 minutes" to unique.filter { length(it) in 1..599 }
            "sit-down" -> "Sit-down watches · 10 to 30 minutes" to unique.filter { length(it) in 600..1799 }
            "long" -> "Long watches · 30 minutes or more" to unique.filter { length(it) >= 1800 }
            "most-viewed" -> "Most viewed" to unique.filter { (it.views ?: 0) > 0 }.sortedByDescending { it.views ?: 0 }
            else -> return null
        }
        return Shelf(rowId, picked.first, picked.second.take(SHELF_SIZE))
    }

    /** an upload date as the server sends it (a full timestamp or just the day) */
    private fun publishedMs(text: String?): Long? {
        val t = text?.trim().orEmpty()
        if (t.length < 10) return null
        return runCatching { java.time.Instant.parse(t).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching {
                java.time.LocalDate.parse(t.take(10)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            }.getOrNull()
    }

    private fun genreOfRow(rowId: String, kind: String): String? =
        rowId.removePrefix("genre:$kind:").takeIf { rowId.startsWith("genre:$kind:") && it.isNotBlank() }

    /**
     * The shelves of one movies-page row: usually one, several for "genres"
     * and "decades", none for a row that isn't built from the library (the
     * tmdb rows, continue, collections) or has nothing to show.
     */
    fun movies(
        rowId: String,
        movies: List<CatalogMovie>,
        year: Int,
        dayKey: String,
        /** added_at strictly before this (ISO text) has been on the shelf a year */
        yearAgoIso: String,
    ): List<Shelf<CatalogMovie>> {
        fun one(title: String, items: List<CatalogMovie>) =
            listOf(Shelf(rowId, title, items.take(SHELF_SIZE))).filter { it.items.isNotEmpty() }
        fun has(m: CatalogMovie, vararg g: String) = g.any { it in m.genres }
        fun minutes(m: CatalogMovie) = (m.runtimeSeconds ?: 0) / 60.0
        fun daily(items: List<CatalogMovie>) = todays(items, "movie-$rowId", dayKey)
        fun genreShelf(g: String, key: String) = Shelf(
            key, "$g in your library",
            todays(movies.filter { g in it.genres }, "movie-genre-$g", dayKey).take(SHELF_SIZE),
            genre = g,
        )
        val byRating = movies.sortedByDescending { it.rating ?: 0.0 }
        return when (rowId) {
            "recent" -> one("Recently added", movies.sortedByDescending { it.addedAt.orEmpty() })
            "top-rated" -> one("Top rated in your library", byRating.filter { (it.rating ?: 0.0) >= 7.5 })
            "top-10" -> one("Top 10 in your library", byRating.filter { (it.rating ?: 0.0) > 0 }.take(10))
            "rediscover" -> one(
                "Rediscover",
                todays(movies.filter { !it.addedAt.isNullOrEmpty() && it.addedAt!! < yearAgoIso }, "movie-rediscover", dayKey),
            )
            "genres" -> topGenres(movies.map { it.genres }, 3)
                .map { genreShelf(it, "genres:$it") }.filter { it.items.size >= 4 }
            "acclaimed" -> one("Critically acclaimed", byRating.filter { (it.rating ?: 0.0) >= 8 })
            "family" -> one("Family night", daily(movies.filter { it.contentRating in FAMILY_MOVIE }))
            "classics" -> one("Timeless classics", daily(movies.filter { (it.year ?: 0) in 1..(year - 30) }))
            "new-releases" -> one(
                "New releases",
                movies.filter { (it.year ?: 0) >= year - 1 }
                    .sortedWith(compareByDescending<CatalogMovie> { it.year ?: 0 }.thenByDescending { it.rating ?: 0.0 }),
            )
            "thrills" -> one("Edge of your seat", daily(movies.filter { has(it, "Thriller", "Horror", "Action") }))
            "feel-good" -> one("Feel-good", daily(movies.filter { has(it, "Comedy", "Romance", "Family") }))
            "quick-watches" -> one("Quick watches", daily(movies.filter { minutes(it) > 0 && minutes(it) < 100 }))
            "epics" -> one("Epic cinema", daily(movies.filter { minutes(it) >= 150 }))
            "decades" -> movies.filter { it.year != null }.groupBy { (it.year!! / 10) * 10 }.entries
                .sortedByDescending { it.value.size }.take(3).sortedByDescending { it.key }
                .map { (d, group) ->
                    Shelf(
                        "decades:$d", "From the ${if (d >= 2000) "$d" else "${d % 100}".padStart(2, '0')}s",
                        todays(group, "movie-decade-$d", dayKey).take(SHELF_SIZE), decade = "${d}s",
                    )
                }
            else -> genreOfRow(rowId, "movie")?.let { g -> listOf(genreShelf(g, rowId)).filter { it.items.isNotEmpty() } }
                ?: emptyList()
        }
    }

    /** the same for the TV page */
    fun shows(
        rowId: String,
        shows: List<CatalogShow>,
        year: Int,
        dayKey: String,
        yearAgoIso: String,
    ): List<Shelf<CatalogShow>> {
        fun one(title: String, items: List<CatalogShow>) =
            listOf(Shelf(rowId, title, items.take(SHELF_SIZE))).filter { it.items.isNotEmpty() }
        fun has(s: CatalogShow, vararg g: String) = g.any { it in s.genres }
        fun daily(items: List<CatalogShow>) = todays(items, "show-$rowId", dayKey)
        fun genreShelf(g: String, key: String) = Shelf(
            key, "$g in your library",
            todays(shows.filter { g in it.genres }, "show-genre-$g", dayKey).take(SHELF_SIZE),
            genre = g,
        )
        val byRating = shows.sortedByDescending { it.rating ?: 0.0 }
        return when (rowId) {
            "recent" -> one("Recently added", shows.sortedByDescending { it.addedAt.orEmpty() })
            "top-rated" -> one("Top rated in your library", byRating.filter { (it.rating ?: 0.0) >= 7.5 })
            "top-10" -> one("Top 10 in your library", byRating.filter { (it.rating ?: 0.0) > 0 }.take(10))
            "rediscover" -> one(
                "Rediscover",
                todays(shows.filter { !it.addedAt.isNullOrEmpty() && it.addedAt!! < yearAgoIso }, "show-rediscover", dayKey),
            )
            "genres" -> topGenres(shows.map { it.genres }, 3)
                .map { genreShelf(it, "genres:$it") }.filter { it.items.size >= 4 }
            "acclaimed" -> one("Critically acclaimed", byRating.filter { (it.rating ?: 0.0) >= 8 })
            "family" -> one("Family night", daily(shows.filter { it.contentRating in FAMILY_SHOW }))
            "classics" -> one("Timeless classics", daily(shows.filter { (it.year ?: 0) in 1..(year - 20) }))
            "new-series" -> one(
                "New series",
                shows.filter { (it.year ?: 0) >= year - 1 }
                    .sortedWith(compareByDescending<CatalogShow> { it.year ?: 0 }.thenByDescending { it.rating ?: 0.0 }),
            )
            "comfort" -> one("Comfort and comedy", daily(shows.filter { has(it, "Comedy", "Family", "Kids") }))
            "drama-scifi" -> one(
                "Drama and sci-fi",
                daily(shows.filter { has(it, "Drama", "Sci-Fi & Fantasy", "Science Fiction", "Fantasy") }),
            )
            "top-network" -> {
                val top = shows.filter { !it.network.isNullOrBlank() }.groupBy { it.network!! }.entries
                    .maxWithOrNull(compareBy<Map.Entry<String, List<CatalogShow>>> { it.value.size }.thenByDescending { it.key })
                if (top == null || top.value.size < 4) emptyList()
                else one("Best of ${top.key}", top.value.sortedByDescending { it.rating ?: 0.0 })
            }
            else -> genreOfRow(rowId, "show")?.let { g -> listOf(genreShelf(g, rowId)).filter { it.items.isNotEmpty() } }
                ?: emptyList()
        }
    }

    /** the tmdb list behind a curated row, per page; null for a row that isn't one */
    fun curatedList(kind: String, rowId: String): Pair<String, String>? = when (kind to rowId) {
        "movie" to "popular" -> "popular_movies" to "Popular right now"
        "movie" to "in-theaters" -> "now_playing" to "In theaters"
        "movie" to "coming-soon" -> "upcoming_movies" to "Coming soon"
        "movie" to "top-all-time" -> "top_movies" to "Top rated of all time"
        "show" to "popular" -> "popular_shows" to "Popular right now"
        "show" to "airing-today" -> "airing_today" to "Airing today"
        "show" to "on-the-air" -> "on_the_air" to "On the air this week"
        "show" to "top-all-time" -> "top_shows" to "Top rated of all time"
        else -> null
    }

    /** the rows a page shows before its layout has loaded: the server's defaults that are on */
    val DEFAULT_ROWS: Map<String, List<LayoutRow>> = mapOf(
        "movies" to listOf(
            "continue" to "wide", "recommended" to "standard", "recent" to "standard",
            "recommended-add" to "standard", "more-like" to "standard", "top-rated" to "standard",
            "genres" to "standard", "popular" to "standard", "in-theaters" to "standard",
            "coming-soon" to "standard", "top-all-time" to "standard", "rediscover" to "standard",
        ).map { LayoutRow(it.first, it.second, true) },
        "tv" to listOf(
            "continue" to "wide", "new-episodes" to "wide", "recommended" to "standard",
            "recent" to "standard", "recommended-add" to "standard", "more-like" to "standard",
            "top-rated" to "standard", "genres" to "standard", "popular" to "standard",
            "airing-today" to "standard", "on-the-air" to "standard", "top-all-time" to "standard",
            "rediscover" to "standard",
        ).map { LayoutRow(it.first, it.second, true) },
        "music" to listOf(
            "recently-played", "stations", "discovery", "heavy-rotation", "recent-albums", "artists", "playlists",
        ).map { LayoutRow(it, "standard", true) },
        "youtube" to listOf("continue", "latest", "channels", "downloaded", "by-channel")
            .map { LayoutRow(it, if (it == "channels") "standard" else "wide", true) },
    )
}
