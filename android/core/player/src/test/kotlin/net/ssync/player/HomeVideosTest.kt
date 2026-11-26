package net.ssync.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class HomeVideosTest {

    private val utc = ZoneId.of("UTC")
    private fun v(id: String, at: String? = null, title: String = "", dur: Double = 0.0) =
        HomeVideo(id = id, title = title, filmedAt = at, durationS = dur)

    // ── parsing ──

    @Test fun `overview reads every field`() {
        val o = HomeVideoParser.overview(
            """{"library":{"id":7,"name":"Family"},"counts":{"videos":412,"first_year":2009,"last_year":2026},
              "on_this_day":[{"id":5,"title":"First snow","filmed_at":"2019-11-25T09:00:00","duration_s":98.4,
                "album":"Winter","album_key":"winter","thumb_url":"/api/player/home/thumb/5",
                "progress":{"position_s":30,"watched":false},"missing":false}],
              "albums":[{"key":"trips/beach 2024","name":"trips/beach 2024","count":23,"first":"2024-07-01","last":"2024-07-09","covers":[1,2,3]}],
              "months":[{"month":"2026-10","count":14},{"month":"2026-11","count":6}]}""",
        )
        assertEquals("Family", o.libraryName)
        assertEquals(412, o.videos)
        assertEquals(2009, o.firstYear)
        assertEquals("5", o.onThisDay.single().id)
        assertEquals(30.0, o.onThisDay.single().positionS, 0.0)
        assertEquals(listOf("1", "2", "3"), o.albums.single().covers)
        // newest month first, whatever order they came in
        assertEquals(listOf("2026-11", "2026-10"), o.months.map { it.month })
    }

    @Test fun `overview reads who may edit, the sources and album paths`() {
        val o = HomeVideoParser.overview(
            """{"library":{"id":7,"name":"Home videos","in_guide":false},"counts":{"videos":2},
              "albums":[{"key":"r:trips/beach","name":"beach","path":"trips/beach","count":2,"covers":[]}],
              "months":[],"sources":["Plex “Home Videos”","/media/family"],"can_edit":true,"scanning":true}""",
        )
        assertEquals(true, o.canEdit)
        assertTrue(o.scanning)
        assertEquals("trips/beach", o.albums.single().path)
        assertEquals("from Plex “Home Videos” and /media/family", HomeVideoText.sourcesLine(o))
        assertEquals("from a, b and c", HomeVideoText.sourcesLine(HomeOverview(sources = listOf("a", "b", "c"))))
        assertNull(HomeVideoText.sourcesLine(HomeOverview()))
        // not said: the app falls back to the admin role
        assertNull(HomeVideoParser.overview("{}").canEdit)
    }

    @Test fun `detail reads the server's edited flags`() {
        val d = HomeVideoParser.detail(
            """{"success":true,"id":12,"title":"Trick or treating","auto_title":"Oct 31, 2026, 6:30 PM","file_name":"VID_1.mp4",
              "edited":{"title":true,"filmed_at":false,"thumb":true,"notes":false},"thumb_at_s":3.1,"can_edit":false,
              "frames":[{"at_s":1.2,"url":"/api/player/home/frame/12?at=1.2"}]}""",
        )!!
        assertTrue(d.video.userTitle)
        assertFalse(d.video.userFilmedAt)
        assertEquals("Oct 31, 2026, 6:30 PM", d.video.autoTitle)
        assertEquals("VID_1.mp4", d.video.fileName)
        assertEquals(false, d.video.canEdit)
        assertEquals(1.2, d.frames.single().atS, 0.0)
    }

    @Test fun `overview survives missing and odd fields`() {
        val o = HomeVideoParser.overview(
            """{"on_this_day":[{"title":"no id"},{"id":"9"}],"albums":[{"name":"no key"},{"key":"a"}],
              "months":[{"month":"bad","count":3},{"month":"2025-01","count":"4"},{"month":"2025-02","count":0}]}""",
        )
        assertEquals(listOf("9"), o.onThisDay.map { it.id })
        assertEquals("a", o.albums.single().name)
        assertEquals(listOf(HomeMonth("2025-01", 4)), o.months)
        // no counts: the months add up
        assertEquals(4, o.videos)
        assertNull(o.firstYear)
    }

    @Test fun `a video with only an id`() {
        val p = HomeVideoParser.page("""{"videos":[{"id":3,"duration_s":null,"progress":null,"missing":1}]}""")
        val x = p.videos.single()
        assertEquals("3", x.id)
        assertEquals("", x.title)
        assertNull(x.filmedAt)
        assertEquals(0.0, x.durationS, 0.0)
        assertTrue(x.missing)
        assertFalse(x.watched)
        // no total: the page's own size
        assertEquals(1, p.total)
    }

    @Test fun `detail reads frames and the edit fields`() {
        val d = HomeVideoParser.detail(
            """{"id":4,"title":"Trick or treating","user_title":"Trick or treating","notes":"grandma",
              "path":"/media/family/VID_20261031_183012.mp4","thumb_at_s":12.5,
              "frames":[{"at_s":6,"url":"/f/6"},{"url":"/f/x"},{"at_s":3}]}""",
        )!!
        assertEquals("VID_20261031_183012.mp4", d.video.fileName)
        assertTrue(d.video.userTitle)
        assertFalse(d.video.userFilmedAt)
        assertEquals(12.5, d.video.thumbAtS!!, 0.0)
        assertEquals("grandma", d.video.notes)
        assertEquals(listOf(6.0, 0.0), d.frames.map { it.atS })
        // under a "video" key works the same
        assertEquals("4", HomeVideoParser.detail("""{"video":{"id":4},"frames":[]}""")!!.video.id)
        assertNull(HomeVideoParser.detail("""{"frames":[]}"""))
    }

    // ── words and dates ──

    @Test fun `filmed keeps a camera's wall clock and moves an offset`() {
        assertEquals(LocalDateTime.of(2026, 10, 31, 18, 30, 12), HomeVideoText.filmed("2026-10-31T18:30:12", utc))
        assertEquals(LocalDateTime.of(2026, 10, 31, 18, 30, 12), HomeVideoText.filmed("2026-10-31 18:30:12.123456", utc)?.withNano(0))
        assertEquals(LocalDateTime.of(2026, 11, 1, 1, 30), HomeVideoText.filmed("2026-10-31T18:30:00-07:00", utc))
        assertEquals(LocalDateTime.of(2026, 10, 31, 18, 30), HomeVideoText.filmed("2026-10-31T18:30:00Z", utc))
        assertEquals(LocalDateTime.of(2024, 7, 1, 0, 0), HomeVideoText.filmed("2024-07-01", utc))
        assertNull(HomeVideoText.filmed("", utc))
        assertNull(HomeVideoText.filmed("soon", utc))
    }

    @Test fun `title falls back to the date`() {
        assertEquals("Thanksgiving", HomeVideoText.title(v("1", "2023-11-25T15:00:00", "Thanksgiving"), utc))
        assertEquals("Oct 31, 2026, 6:30 PM", HomeVideoText.title(v("1", "2026-10-31T18:30:12"), utc))
        assertEquals("Home video", HomeVideoText.title(v("1"), utc))
    }

    @Test fun `day, date and month`() {
        val x = v("1", "2026-11-02T08:00:00")
        assertEquals("Nov 2", HomeVideoText.day(x, utc))
        assertEquals("Nov 2, 2026", HomeVideoText.date(x, utc))
        assertEquals("2026-11", HomeVideoText.monthKey(x, utc))
        assertEquals("November 2026", HomeVideoText.monthTitle("2026-11"))
        assertEquals("Nov 2026", HomeVideoText.monthShort("2026-11"))
        assertEquals("Undated", HomeVideoText.monthTitle(""))
        assertEquals("", HomeVideoText.day(v("2"), utc))
        assertEquals("October 31, 2026 · 6:30 PM", HomeVideoText.dateTime(LocalDateTime.of(2026, 10, 31, 18, 30)))
        assertEquals("2026-10-31T18:30:00", HomeVideoText.isoLocal(LocalDateTime.of(2026, 10, 31, 18, 30, 0, 500)))
    }

    @Test fun `durations`() {
        assertEquals("", HomeVideoText.duration(0.0))
        assertEquals("0:01", HomeVideoText.duration(0.4))
        assertEquals("4:12", HomeVideoText.duration(252.9))
        assertEquals("1:02:07", HomeVideoText.duration(3727.0))
    }

    @Test fun `years ago`() {
        val now = LocalDate.of(2026, 11, 25)
        assertEquals("3 years ago", HomeVideoText.yearsAgo(v("1", "2023-11-25T10:00:00"), now, utc))
        assertEquals("1 year ago", HomeVideoText.yearsAgo(v("1", "2025-11-25T10:00:00"), now, utc))
        assertNull(HomeVideoText.yearsAgo(v("1", "2026-11-25T10:00:00"), now, utc))
        assertNull(HomeVideoText.yearsAgo(v("1"), now, utc))
        assertEquals("November 25", HomeVideoText.today(now))
    }

    @Test fun `library and album lines`() {
        assertEquals("412 videos · 2009 – 2026", HomeVideoText.libraryLine(HomeOverview(videos = 412, firstYear = 2009, lastYear = 2026)))
        assertEquals("1 video · 2026", HomeVideoText.libraryLine(HomeOverview(videos = 1, firstYear = 2026, lastYear = 2026)))
        assertEquals("1,204 videos", HomeVideoText.libraryLine(HomeOverview(videos = 1204)))
        assertEquals("23 videos · Jul 2024", HomeVideoText.albumLine(HomeAlbum("b", "Beach", 23, "2024-07-01", "2024-07-09"), utc))
        assertEquals("86 videos · 2010 – 2025", HomeVideoText.albumLine(HomeAlbum("h", "Holidays", 86, "2010-12-24", "2025-01-01"), utc))
        assertEquals("5 videos · 2020", HomeVideoText.albumLine(HomeAlbum("h", "x", 5, "2020-01-02", "2020-12-01"), utc))
        assertEquals("41 videos", HomeVideoText.albumLine(HomeAlbum("b", "Birthdays", 41), utc))
        assertEquals("Mar 2021", HomeVideoText.albumLine(HomeAlbum("b", "x", 0, null, "2021-03-04"), utc).substringAfter(" · "))
        assertEquals("beach 2024", HomeVideoText.albumName(HomeAlbum("k", "trips/beach 2024/")))
        assertEquals("Old tapes", HomeVideoText.albumName(HomeAlbum("k", "Old tapes")))
    }

    @Test fun `resume only when it was started and not finished`() {
        assertEquals(0L, HomeVideoText.resumeMs(HomeVideo("1", positionS = 3.0)))
        assertEquals(42_000L, HomeVideoText.resumeMs(HomeVideo("1", positionS = 42.0)))
        assertEquals(0L, HomeVideoText.resumeMs(HomeVideo("1", positionS = 42.0, watched = true)))
        assertEquals(0.5f, HomeVideo("1", durationS = 100.0, positionS = 50.0).progressFraction!!, 0.001f)
        assertNull(HomeVideo("1", durationS = 100.0, positionS = 50.0, watched = true).progressFraction)
    }

    // ── paging ──

    @Test fun `pages land where they belong in any order`() {
        val page = 3
        var slots: List<HomeVideo?> = emptyList()
        slots = HomePaging.merge(slots, 7, 3, listOf(v("d"), v("e"), v("f")))
        assertEquals(listOf(null, null, null, "d", "e", "f", null), slots.map { it?.id })
        assertEquals(0, HomePaging.pageFor(slots, 1, page))
        assertNull(HomePaging.pageFor(slots, 4, page))
        assertEquals(6, HomePaging.pageFor(slots, 6, page))
        assertNull(HomePaging.pageFor(slots, 7, page))
        slots = HomePaging.merge(slots, 7, 0, listOf(v("a"), v("b"), v("c")))
        slots = HomePaging.merge(slots, 7, 6, listOf(v("g")))
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g"), HomePaging.loaded(slots).map { it.id })
    }

    @Test fun `a moved clip isn't shown twice and the list follows the new total`() {
        var slots: List<HomeVideo?> = listOf(v("a"), v("b"), v("c"), v("d"))
        // "a" moved to the second page
        slots = HomePaging.merge(slots, 5, 2, listOf(v("a"), v("c"), v("e")))
        assertEquals(listOf(null, "b", "a", "c", "e"), slots.map { it?.id })
        // fewer now: it shrinks
        slots = HomePaging.merge(slots, 2, 0, listOf(v("b"), v("c")))
        assertEquals(listOf("b", "c"), slots.map { it?.id })
        // an empty answer is an empty list
        assertEquals(0, HomePaging.merge(emptyList(), 0, 0, emptyList()).size)
        // a page past a total the server got wrong still shows
        assertEquals(3, HomePaging.merge(emptyList(), 1, 0, listOf(v("x"), v("y"), v("z"))).size)
    }

    @Test fun `replace swaps a clip everywhere`() {
        val slots = listOf(v("a"), null, v("b", title = "old"))
        assertEquals("new", HomePaging.replace(slots, v("b", title = "new"))[2]?.title)
        assertTrue(HomePaging.replace(slots, v("z")) === slots)
    }

    // ── months ──

    @Test fun `group folds a list into months, newest first, undated last`() {
        val g = HomeMonths.group(
            listOf(v("1", "2026-10-31T10:00:00"), v("2", "2026-11-02T10:00:00"), v("3"), v("4", "2026-10-01T10:00:00")),
            utc,
        )
        assertEquals(listOf("2026-11", "2026-10", ""), g.map { it.first })
        assertEquals(listOf("1", "4"), g[1].second.map { it.id })
    }

    @Test fun `years for the jump list`() {
        val y = HomeMonths.years(listOf(HomeMonth("2025-01", 1), HomeMonth("2026-03", 2), HomeMonth("2026-11", 3)))
        assertEquals(listOf(2026, 2025), y.map { it.first })
        assertEquals(listOf("2026-11", "2026-03"), y[0].second.map { it.month })
    }

    @Test fun `timeline rows and where each month starts`() {
        val rows = HomeTimeline.rows(listOf(HomeMonth("2026-11", 4), HomeMonth("2026-10", 3), HomeMonth("2026-09", 0)), 3)
        assertEquals(
            listOf("h-2026-11", "r-2026-11-0", "r-2026-11-3", "h-2026-10", "r-2026-10-0"),
            rows.map { it.key },
        )
        val last = rows[2] as HomeTimelineRow.Cells
        assertEquals(3, last.start)
        assertEquals(4, last.end)
        assertEquals(mapOf("2026-11" to 0, "2026-10" to 3), HomeTimeline.headerIndex(rows))
        assertEquals("2026-10", HomeTimeline.monthAt(rows, 4))
        assertNull(HomeTimeline.monthAt(rows, 9))
        // keys are unique, so the list can keep its place
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }

    @Test fun `library list carries in_guide and home pages`() {
        val cat = VideoCatalogDecoder.decode(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"libraries":[{"id":1,"kind":"movie","name":"Movies","main":true},{"id":2,"kind":"home","name":"Family","main":true},
                  {"id":3,"kind":"show","name":"Kids","own_page":true,"in_guide":false}]}""",
            ) as kotlinx.serialization.json.JsonObject,
        )
        val byId = cat.libraries.associateBy { it.id }
        assertTrue(byId.getValue(1).inGuide)
        assertFalse(byId.getValue(2).inGuide)
        assertFalse(byId.getValue(3).inGuide)
        // a home library has its own page even as its kind's main one
        assertEquals(setOf(2, 3), cat.ownPageLibraryIds)
    }

    @Test fun `home clips are video downloads on their own shelf`() {
        assertTrue("home" in OFFLINE_VIDEO_KINDS)
        assertEquals(OfflineShelf.HOME, shelfOf("home"))
    }
}
