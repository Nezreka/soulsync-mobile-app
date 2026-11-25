package net.ssync.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** the library-built rows of the Movies and TV pages mean what the web player's mean */
class PageShelvesTest {
    private fun movie(
        id: Int, year: Int? = 2010, minutes: Int = 110, rating: Double? = 7.0,
        genres: List<String> = listOf("Drama"), cert: String? = "R", added: String? = "2026-11-01T00:00:00",
    ) = CatalogMovie(
        id = id, title = "Film $id", year = year, runtimeSeconds = minutes * 60, contentRating = cert,
        rating = rating, genres = genres, addedAt = added,
    )

    private fun show(
        id: Int, year: Int? = 2015, rating: Double? = 7.0, genres: List<String> = listOf("Drama"),
        network: String? = null, cert: String? = "TV-MA", added: String? = "2026-11-01T00:00:00",
    ) = CatalogShow(
        id = id, title = "Show $id", year = year, network = network, contentRating = cert,
        rating = rating, genres = genres, addedAt = added,
    )

    private fun movieIds(row: String, movies: List<CatalogMovie>) =
        PageShelves.movies(row, movies, year = 2026, dayKey = "2026-11-25", yearAgoIso = "2025-11-25")
            .flatMap { it.items }.map { it.id }.toSet()

    @Test
    fun `the filter rows keep exactly what the web keeps`() {
        val movies = listOf(
            movie(1, minutes = 85), movie(2, minutes = 99), movie(3, minutes = 100),      // quick: under 100
            movie(4, minutes = 150), movie(5, minutes = 149),                              // epic: 150 and up
            movie(6, rating = 8.0), movie(7, rating = 7.9),                                // acclaimed: 8 and up
            movie(8, year = 1996), movie(9, year = 1997),                                  // classic: 30 years old
            movie(10, year = 2025), movie(11, year = 2024),                                // new: this year and last
            movie(12, cert = "PG"), movie(13, cert = "PG-13"),                             // family: G and PG
            movie(14, genres = listOf("Horror")), movie(15, genres = listOf("Romance")),
            movie(16, minutes = 0),                                                         // no runtime: in neither
        )
        assertEquals(setOf(1, 2), movieIds("quick-watches", movies))
        assertEquals(setOf(4), movieIds("epics", movies))
        assertEquals(setOf(6), movieIds("acclaimed", movies))
        assertEquals(setOf(8), movieIds("classics", movies))
        assertEquals(setOf(10), movieIds("new-releases", movies))
        assertEquals(setOf(12), movieIds("family", movies))
        assertEquals(setOf(14), movieIds("thrills", movies))
        assertEquals(setOf(15), movieIds("feel-good", movies))
    }

    @Test
    fun `top rated needs seven and a half, the top ten is ten, rediscover is a year old`() {
        val movies = (1..14).map { movie(it, rating = 6.0 + it * 0.2, added = if (it <= 3) "2024-01-01T00:00:00" else "2026-06-01T00:00:00") }
        assertEquals((8..14).toSet(), movieIds("top-rated", movies))                 // 7.6 and up
        val ten = PageShelves.movies("top-10", movies, 2026, "d", "2025-11-25").single().items
        assertEquals(10, ten.size)
        assertEquals(14, ten.first().id)
        assertEquals(setOf(1, 2, 3), movieIds("rediscover", movies))
    }

    @Test
    fun `genres gives a shelf for each of the three biggest with four or more, and a genre row is its own`() {
        val movies = (1..6).map { movie(it, genres = listOf("Drama")) } +
            (7..11).map { movie(it, genres = listOf("Comedy")) } +
            (12..14).map { movie(it, genres = listOf("Western")) } +
            listOf(movie(15, genres = listOf("Noir")))
        val shelves = PageShelves.movies("genres", movies, 2026, "d", "2025-11-25")
        assertEquals(listOf("Drama in your library", "Comedy in your library"), shelves.map { it.title })
        assertEquals(listOf("Drama", "Comedy"), shelves.map { it.genre })
        assertEquals(shelves.map { it.key }.toSet().size, shelves.size)
        assertEquals(setOf(15), movieIds("genre:movie:Noir", movies))
        assertTrue(movieIds("genre:show:Noir", movies).isEmpty())               // a show row is not a movies row
    }

    @Test
    fun `decades are the three biggest, newest first`() {
        val movies = (1..5).map { movie(it, year = 1994) } + (6..9).map { movie(it, year = 2012) } +
            (10..12).map { movie(it, year = 2003) } + listOf(movie(13, year = 1975))
        val shelves = PageShelves.movies("decades", movies, 2026, "d", "2025-11-25")
        assertEquals(listOf("From the 2010s", "From the 2000s", "From the 90s"), shelves.map { it.title })
        assertEquals(listOf("2010s", "2000s", "1990s"), shelves.map { it.decade })
    }

    @Test
    fun `a daily row is the same all day and never invents a title`() {
        val movies = (1..40).map { movie(it, minutes = 90) }
        val a = PageShelves.movies("quick-watches", movies, 2026, "2026-11-25", "x").single().items.map { it.id }
        val b = PageShelves.movies("quick-watches", movies, 2026, "2026-11-25", "x").single().items.map { it.id }
        val tomorrow = PageShelves.movies("quick-watches", movies, 2026, "2026-11-26", "x").single().items.map { it.id }
        assertEquals(a, b)
        assertEquals(PageShelves.SHELF_SIZE, a.size)
        assertTrue(a != tomorrow)
        assertTrue(a.all { it in 1..40 })
    }

    @Test
    fun `the tv rows`() {
        val shows = (1..4).map { show(it, network = "HBO", rating = 7.0 + it) } + listOf(
            show(5, network = "AMC"), show(6, year = 2006), show(7, year = 2007), show(8, year = 2026),
            show(9, cert = "TV-PG"), show(10, genres = listOf("Comedy")), show(11, genres = listOf("Sci-Fi & Fantasy")),
        )
        fun ids(row: String) = PageShelves.shows(row, shows, 2026, "d", "2025-11-25").flatMap { it.items }.map { it.id }
        val network = PageShelves.shows("top-network", shows, 2026, "d", "2025-11-25").single()
        assertEquals("Best of HBO", network.title)
        assertEquals(listOf(4, 3, 2, 1), network.items.map { it.id })
        assertEquals(setOf(6), ids("classics").toSet())                       // twenty years old
        assertEquals(listOf(8), ids("new-series"))
        assertEquals(listOf(9), ids("family"))
        assertEquals(listOf(10), ids("comfort"))
        assertEquals(setOf(11) + (1..9).toSet() - setOf(10), ids("drama-scifi").toSet())
        // a network with fewer than four shows is not a row
        assertTrue(PageShelves.shows("top-network", shows.drop(1), 2026, "d", "x").isEmpty())
    }

    @Test
    fun `rows that are not built from the library give nothing, and the curated lists are named`() {
        val movies = listOf(movie(1))
        for (row in listOf("continue", "recommended", "recommended-add", "more-like", "popular", "collections", "nope"))
            assertTrue(row, PageShelves.movies(row, movies, 2026, "d", "x").isEmpty())
        assertEquals("now_playing" to "In theaters", PageShelves.curatedList("movie", "in-theaters"))
        assertEquals("airing_today" to "Airing today", PageShelves.curatedList("show", "airing-today"))
        assertEquals(null, PageShelves.curatedList("show", "in-theaters"))
    }

    @Test
    fun `every page has default rows`() {
        assertEquals(setOf("movies", "tv", "music", "youtube"), PageShelves.DEFAULT_ROWS.keys)
        assertTrue(PageShelves.DEFAULT_ROWS.values.all { rows -> rows.isNotEmpty() && rows.all { it.on } })
    }
}
