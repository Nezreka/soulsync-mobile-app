package net.ssync.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLibrariesTest {

    private val movies = PlayerLibrary(1, "movie", "Movies", main = true)
    private val tv = PlayerLibrary(2, "show", "TV Shows", main = true)
    private val uhd = PlayerLibrary(5, "movie", "Movies 4K", ownPage = true)
    private val anime = PlayerLibrary(6, "show", "Anime", ownPage = true)
    private val home = PlayerLibrary(9, "home", "Home videos")
    private val libs = listOf(movies, tv, uhd, anime, home)

    private fun m(id: Int, lib: Int?, tmdb: Int? = null, title: String = "Film $id", year: Int? = 2020, added: String = "") =
        CatalogMovie(id = id, title = title, year = year, tmdbId = tmdb, libraryId = lib, addedAt = added)

    private fun s(id: Int, lib: Int?, tmdb: Int? = null, title: String = "Show $id", year: Int? = 2020, added: String = "") =
        CatalogShow(id = id, title = title, year = year, tmdbId = tmdb, libraryId = lib, addedAt = added)

    private fun p(type: String, id: String, parent: String? = null) =
        ProgressRow(itemType = type, itemId = id, meta = parent?.let { ProgressMeta(parentId = it) })

    // ── row ids ──

    @Test fun `parses the library row ids`() {
        assertEquals(LibraryRow.Recent(5), HomeLibraries.parse("lib-recent:5"))
        assertEquals(LibraryRow.Continue(12), HomeLibraries.parse("lib-continue:12"))
        assertEquals(LibraryRow.Memories, HomeLibraries.parse("home-memories"))
        assertNull(HomeLibraries.parse("recent-movies"))
        assertNull(HomeLibraries.parse("lib-recent:"))
        assertNull(HomeLibraries.parse("lib-recent:x"))
        assertNull(HomeLibraries.parse("lib-recent:5:6"))
        assertNull(HomeLibraries.parse("lib-other:5"))
        assertEquals("lib-recent:5", HomeLibraries.recentId(5))
        assertEquals("lib-continue:5", HomeLibraries.continueId(5))
    }

    @Test fun `titles use the library's current name`() {
        assertEquals("Recently added in Movies 4K", HomeLibraries.title("lib-recent:5", libs))
        assertEquals("Continue in Anime", HomeLibraries.title("lib-continue:6", libs))
        assertEquals("On this day", HomeLibraries.title("home-memories", libs))
        val renamed = libs.map { if (it.id == 5) it.copy(name = "UHD") else it }
        assertEquals("Recently added in UHD", HomeLibraries.title("lib-recent:5", renamed))
        assertNull(HomeLibraries.title("continue", libs))
    }

    @Test fun `rows for libraries the profile can't see are skipped`() {
        // hidden or removed: not sent in the catalog's libraries
        val seen = listOf(movies, tv)
        assertFalse(HomeLibraries.visible("lib-recent:5", seen))
        assertFalse(HomeLibraries.visible("lib-continue:6", seen))
        assertNull(HomeLibraries.title("lib-recent:5", seen))
        // no home library: no on this day
        assertFalse(HomeLibraries.visible("home-memories", seen))
        assertTrue(HomeLibraries.visible("home-memories", libs))
        // a home library isn't a title library
        assertFalse(HomeLibraries.visible("lib-recent:9", libs))
        // every other row is untouched
        assertTrue(HomeLibraries.visible("recent-movies", seen))
        assertTrue(HomeLibraries.visible("genre:movie:Drama", emptyList()))
    }

    @Test fun `the editor offers a row per library it can see`() {
        assertEquals(
            listOf("home-memories", "lib-recent:1", "lib-continue:1", "lib-recent:2", "lib-continue:2", "lib-recent:5", "lib-continue:5",
                "lib-recent:6", "lib-continue:6"),
            HomeLibraries.offered(libs),
        )
        assertEquals(listOf("lib-recent:1", "lib-continue:1"), HomeLibraries.offered(listOf(movies)))
        assertEquals(listOf("standard", "large", "wide", "ranked"), HomeLibraries.styles("lib-recent:1"))
        assertEquals("wide", HomeLibraries.defaultStyle("lib-continue:1"))
        assertEquals("wide", HomeLibraries.defaultStyle("home-memories"))
    }

    @Test fun `move steps past rows the editor doesn't show`() {
        val rows = listOf(LayoutRow("a"), LayoutRow("lib-recent:7"), LayoutRow("b"))
        val shown = { r: LayoutRow -> r.id != "lib-recent:7" }
        assertEquals(listOf("b", "lib-recent:7", "a"), HomeLibraries.move(rows, 2, up = true, shown).map { it.id })
        assertEquals(listOf("b", "lib-recent:7", "a"), HomeLibraries.move(rows, 0, up = false, shown).map { it.id })
        assertSame(rows, HomeLibraries.move(rows, 0, up = true, shown))
    }

    // ── library rows ──

    @Test fun `recently added keeps to the library, newest first`() {
        val list = listOf(m(1, null, added = "2026-01"), m(2, 5, added = "2026-03"), m(3, 1, added = "2026-02"), m(4, 5, added = "2026-04"))
        assertEquals(listOf(4, 2), HomeLibraries.recentMovies(5, list, libs).map { it.id })
        // a title in no library folder is in the main one
        assertEquals(listOf(3, 1), HomeLibraries.recentMovies(1, list, libs).map { it.id })
        val shows = listOf(s(1, 6, added = "b"), s(2, null, added = "c"))
        assertEquals(listOf(1), HomeLibraries.recentShows(6, shows, libs).map { it.id })
        assertEquals(listOf(2), HomeLibraries.recentShows(2, shows, libs).map { it.id })
    }

    @Test fun `continue keeps to the library, a movie by id and an episode by its show`() {
        val ms = listOf(m(1, null), m(2, 5))
        val ss = listOf(s(10, 6), s(11, null))
        val items = listOf(
            p("movie", "2"), p("episode", "100", parent = "10"), p("movie", "1"),
            p("episode", "101", parent = "11"), p("show", "10"), p("youtube", "abc"), p("movie", "99"),
        )
        assertEquals(listOf("2"), HomeLibraries.continueIn(uhd, items, ms, ss, libs).map { it.itemId })
        assertEquals(listOf("1"), HomeLibraries.continueIn(movies, items, ms, ss, libs).map { it.itemId })
        assertEquals(listOf("100", "10"), HomeLibraries.continueIn(anime, items, ms, ss, libs).map { it.itemId })
        assertEquals(listOf("101"), HomeLibraries.continueIn(tv, items, ms, ss, libs).map { it.itemId })
    }

    // ── one card per title ──

    @Test fun `a title in two libraries is one card opening the main copy, with a 4K chip`() {
        val list = listOf(m(2, 5, tmdb = 77, added = "2026-05"), m(1, null, tmdb = 77, added = "2026-01"), m(3, 1, tmdb = 88))
        val copies = TitleCopies.of(list, emptyList(), libs)
        // the 4K copy came first (newest), but the card is the main library's
        assertEquals(listOf(1, 3), copies.movies(list).map { it.id })
        assertEquals(1, copies.movieId(2))
        assertTrue(copies.movieHas4k(1))
        assertTrue(copies.movieHas4k(2))
        assertFalse(copies.movieHas4k(3))
        assertEquals(listOf("movie" to 1, "show" to 4), copies.cards(listOf("movie" to 2, "movie" to 1, "show" to 4)))
    }

    @Test fun `no tmdb id matches on title and year`() {
        val list = listOf(m(1, 1, title = "Heat ", year = 1995), m(2, 5, title = "heat", year = 1995), m(3, 5, title = "Heat", year = 2023))
        val copies = TitleCopies.of(list, emptyList(), libs)
        assertEquals(listOf(1, 3), copies.movies(list).map { it.id })
        assertTrue(copies.movieHas4k(1))
        assertFalse(copies.movieHas4k(3))
        // no tmdb id and no year: never matched
        val noYear = listOf(m(4, 1, title = "Home", year = null), m(5, 5, title = "Home", year = null))
        assertEquals(listOf(4, 5), TitleCopies.of(noYear, emptyList(), libs).movies(noYear).map { it.id })
    }

    @Test fun `a 4K chip only when another copy is 4K`() {
        // in TV Shows and Anime: one card, the main copy, no chip
        val shows = listOf(s(1, 6, tmdb = 5), s(2, null, tmdb = 5))
        val copies = TitleCopies.of(emptyList(), shows, libs)
        assertEquals(listOf(2), copies.shows(shows).map { it.id })
        assertFalse(copies.showHas4k(2))
        // only in 4K: its own card, no chip
        val only = listOf(m(1, 5, tmdb = 9))
        assertFalse(TitleCopies.of(only, emptyList(), libs).movieHas4k(1))
    }

    @Test fun `no main copy picks one that isn't 4K`() {
        val other = PlayerLibrary(7, "movie", "Kids", ownPage = true)
        val list = listOf(m(1, 5, tmdb = 3), m(2, 7, tmdb = 3))
        val copies = TitleCopies.of(list, emptyList(), libs + other)
        assertEquals(listOf(2), copies.movies(list).map { it.id })
        assertTrue(copies.movieHas4k(2))
    }

    @Test fun `one library of each kind leaves lists alone`() {
        val list = listOf(m(1, null, tmdb = 3), m(2, null, tmdb = 3))
        val copies = TitleCopies.of(list, emptyList(), listOf(movies, tv))
        assertSame(list, copies.movies(list))
        assertFalse(copies.movieHas4k(1))
    }

    @Test fun `the server's uhd flag wins over the name`() {
        assertTrue(TitleCopies.is4kLibrary(PlayerLibrary(1, name = "Films", uhd = true)))
        assertFalse(TitleCopies.is4kLibrary(PlayerLibrary(1, name = "Movies 4K", uhd = false)))
        assertFalse(TitleCopies.is4kLibrary(PlayerLibrary(1, kind = "home", name = "4K home")))
        val list = listOf(m(1, null, tmdb = 4), m(2, 8, tmdb = 4))
        val flagged = PlayerLibrary(8, "movie", "Remux", ownPage = true, uhd = true)
        assertTrue(TitleCopies.of(list, emptyList(), libs + flagged).movieHas4k(1))
    }

    @Test fun `the catalog reads the uhd flag`() {
        val cat = VideoCatalogDecoder.decode(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"libraries":[{"id":1,"kind":"movie","name":"Movies","main":true,"uhd":false},{"id":5,"kind":"movie","name":"Remux","uhd":true},
                  {"id":6,"kind":"movie","name":"Old"}]}""",
            ) as kotlinx.serialization.json.JsonObject,
        )
        assertEquals(listOf(false, true, null), cat.libraries.map { it.uhd })
    }

    @Test fun `memories ask for the viewer's day and leave out missing clips`() = kotlinx.coroutines.test.runTest {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"success":true,"videos":[{"id":1,"missing":true},{"id":2,"library":9}]}"""))
        server.start()
        try {
            val client = HomeVideosApiClient(server.url("/").toString(), okhttp3.OkHttpClient())
            val res = client.memories("2026-11-26") as MusicApiResult.Success
            assertEquals(listOf("2"), res.data.map { it.id })
            assertEquals("/api/player/home/memories?date=2026-11-26", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test fun `4K libraries by name`() {
        assertTrue(TitleCopies.is4kLibrary(PlayerLibrary(1, name = "Movies 4K")))
        assertTrue(TitleCopies.is4kLibrary(PlayerLibrary(1, name = "4k films")))
        assertTrue(TitleCopies.is4kLibrary(PlayerLibrary(1, name = "UHD")))
        assertTrue(TitleCopies.is4kLibrary(PlayerLibrary(1, name = "Movies (2160p)")))
        assertFalse(TitleCopies.is4kLibrary(PlayerLibrary(1, name = "Movies")))
        assertFalse(TitleCopies.is4kLibrary(PlayerLibrary(1, name = "Top4Kids")))
    }
}
