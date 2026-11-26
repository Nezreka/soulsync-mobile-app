package net.ssync.player

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemInfoTest {

    // ── item types ──

    @Test fun `every playable kind maps to the server's kind`() {
        assertEquals("movie", ItemInfoTypes.serverType("movie"))
        assertEquals("episode", ItemInfoTypes.serverType("episode"))
        assertEquals("track", ItemInfoTypes.serverType("track"))
        assertEquals("audiobook", ItemInfoTypes.serverType("audiobook"))
        assertEquals("podcast_episode", ItemInfoTypes.serverType("podcast_episode"))
        assertEquals("podcast_episode", ItemInfoTypes.serverType("podcast"))
        assertEquals("home", ItemInfoTypes.serverType("home"))
        assertEquals("youtube", ItemInfoTypes.serverType("youtube"))
        assertEquals("track", ItemInfoTypes.serverType(" Track "))
    }

    @Test fun `groups have no info sheet`() {
        for (t in listOf("show", "season", "album", "artist", "playlist", "commercial", "")) {
            assertNull(t, ItemInfoTypes.serverType(t))
            assertFalse(ItemInfoTypes.supports(t))
        }
        assertTrue(ItemInfoTypes.supports("movie"))
    }

    // ── parsing ──

    @Test fun `reads the sections in order with their flags`() {
        val info = ItemInfoParser.parse(
            """{"success":true,"item_type":"movie","item_id":"12","title":"Alien","subtitle":"1979 · R · 1h 57m",
              "artwork":"/api/video/poster/movie/12?w=342",
              "sections":[
                {"id":"details","title":"Details","rows":[{"label":"Year","value":"1979"}]},
                {"id":"ids","title":"IDs","rows":[{"label":"TMDB","value":"348","mono":true,"copy":true}]},
                {"id":"file","title":"File · 4K HDR10","group":"4K HDR10","rows":[{"label":"Path","value":"/m/Alien.mkv","mono":true,"copy":true},{"label":"Size","value":"58.1 GB"}]}
              ]}""",
        )
        assertEquals("Alien", info.title)
        assertEquals("1979 · R · 1h 57m", info.subtitle)
        assertEquals("/api/video/poster/movie/12?w=342", info.artwork)
        assertEquals(listOf("Details", "IDs", "File · 4K HDR10"), info.sections.map { it.title })
        val year = info.sections[0].rows.single()
        assertFalse(year.mono)
        assertFalse(year.copy)
        val tmdb = info.sections[1].rows.single()
        assertEquals(ItemInfoRow("TMDB", "348", mono = true, copy = true), tmdb)
        assertEquals(listOf("Path", "Size"), info.sections[2].rows.map { it.label })
    }

    @Test fun `empty rows and sections are dropped, missing fields are blank`() {
        val info = ItemInfoParser.parse(
            """{"title":"x","artwork":null,"sections":[
                {"title":"Empty","rows":[]},
                {"title":"Some","rows":[{"label":"A","value":""},{"label":"B","value":null},{"label":"C","value":"3"},"junk"]},
                {"rows":[{"label":"D","value":4}]}
              ]}""",
        )
        assertNull(info.artwork)
        assertEquals("", info.subtitle)
        assertEquals(listOf("Some", ""), info.sections.map { it.title })
        assertEquals("C", info.sections[0].rows.single().label)
        assertEquals("4", info.sections[1].rows.single().value)
    }

    @Test fun `no sections is an empty answer, not a failure`() {
        assertTrue(ItemInfoParser.parse("""{"success":true,"title":"x"}""").sections.isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun `something that isn't json says so`() {
        ItemInfoParser.parse("<html>")
    }

    // ── the route ──

    @Test fun `asks the right url and shows the server's words on errors`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"success":true,"title":"Ep","sections":[]}"""))
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"success":false,"error":"not_found","message":"That isn't in your library."}"""),
        )
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"success":false,"error":"restricted"}"""))
        server.start()
        try {
            val client = ItemInfoApiClient(server.url("/").toString(), OkHttpClient())
            val ok = client.fetch("podcast_episode", "7:https://x.test/a b.mp3")
            assertEquals("Ep", (ok as MusicApiResult.Success).data.title)
            assertEquals(
                "/api/player/info/podcast_episode/7%3Ahttps%3A%2F%2Fx.test%2Fa%20b.mp3",
                server.takeRequest().path,
            )
            assertEquals(MusicApiResult.Error("That isn't in your library."), client.fetch("movie", "12"))
            assertEquals(MusicApiResult.Error("This profile can't see this."), client.fetch("track", "5"))
            // a kind with no sheet never reaches the server
            assertTrue(client.fetch("album", "3") is MusicApiResult.Error)
            assertEquals(3, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
