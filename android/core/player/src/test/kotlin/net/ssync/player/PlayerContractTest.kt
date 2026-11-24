package net.ssync.player

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * the server's real response shapes, as SoulSync's routes write them
 * (api/podcasts.py, api/player.py home-layout, lib2 scans). each test is a
 * mismatch that once failed a whole screen or decoded to nothing.
 */
class PlayerContractTest {

    private lateinit var server: MockWebServer
    private lateinit var client: PlayerApiClient
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = PlayerApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            pinnedAuthenticatedClient = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun podcastShowIsReadFromInsideItsEnvelope() = runTest {
        // /api/podcasts/show answers {success, show: {...}}: read from the root
        // it decoded to an empty show (no title, no episodes) with no error
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"show":{"title":"Hardcore History","author":"Dan Carlin","episodes":[{"title":"Ep 1","guid":"g1","enclosure_url":"https://x/1.mp3"}]}}""",
            ),
        )
        val res = client.fetchPodcastShow("https://feed") as MusicApiResult.Success
        assertEquals("Hardcore History", res.data.title)
        assertEquals(1, res.data.episodes.size)
    }

    @Test
    fun homeLayoutScopeSaysWhetherThisScreenHasItsOwn() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"layout":{"rows":[]},"scope":"device"}"""))
        val mine = client.fetchHomeLayout() as MusicApiResult.Success
        assertFalse(mine.data.shared)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"layout":{"rows":[]},"scope":"all"}"""))
        assertTrue((client.fetchHomeLayout() as MusicApiResult.Success).data.shared)
    }

    @Test
    fun savingForThisScreenOnlySendsItsId() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"layout":{"rows":[]},"scope":"device"}"""))
        client.saveHomeLayout(HomeLayout(), shared = false)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"device\":\"$PHONE_LAYOUT_DEVICE\""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"layout":{"rows":[]},"scope":"all"}"""))
        client.saveHomeLayout(HomeLayout(), shared = true)
        val shared = server.takeRequest().body.readUtf8()
        assertTrue(shared, shared.contains("\"device\":\"*\""))
        assertTrue(shared, shared.contains("\"from_device\":\"$PHONE_LAYOUT_DEVICE\""))
    }

    @Test
    fun theCommercialsSwitchSendsOnlyWhatChanged() = runTest {
        // {"enabled":true,"era":null,"mode":null}: the server saved era "none",
        // refused it, and the switch never moved
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"enabled":true,"era":"auto","mode":"cable","can_edit":true}"""))
        client.saveCommercialsConfig(enabled = true)
        assertEquals("""{"enabled":true}""", server.takeRequest().body.readUtf8())
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"enabled":true,"era":"80s","mode":"cable","can_edit":true}"""))
        client.saveCommercialsConfig(era = "80s")
        assertEquals("""{"era":"80s"}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun premieresAreReadAsTheServerSendsThem() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"now":1,"premieres":[{"key":"episode:7","kind":"episode","id":7,"show_id":3,"title":"Silo","subtitle":"S2 E1","seconds":2700,"at":1795500000000,"homes":["network-apple-tv","type-tv"],"has_poster":true},{"key":"movie:9","kind":"movie","id":9,"show_id":null,"title":"Heat","subtitle":null,"seconds":10200,"at":1795503600000,"homes":["type-movies"],"has_poster":false}]}""",
            ),
        )
        val res = client.fetchGuidePremieres() as MusicApiResult.Success
        assertEquals(listOf("episode:7", "movie:9"), res.data.map { it.key })
        assertEquals(3, res.data[0].showId)
        assertEquals(1795500000000L, res.data[0].at)
        assertEquals(listOf("network-apple-tv", "type-tv"), res.data[0].homes)
        assertNull(res.data[1].subtitle)
        assertEquals("/api/player/guide/premieres", server.takeRequest().path)
    }

    @Test
    fun scannerWrittenNumbersDoNotFailTheList() {
        // sqlite keeps what a scanner wrote: a float in an INTEGER column, a
        // bitrate as "320kbps". one such row failed the whole album
        val t = json.decodeFromString<LibTrack>("""{"id":1,"title":"x","duration":215000.5}""")
        assertEquals(215000L, t.duration)
        val bad = json.decodeFromString<LibTrack>("""{"id":1,"title":"x","duration":"n/a"}""")
        assertNull(bad.duration)
        val f = json.decodeFromString<LibTrackFile>("""{"format":"mp3","bitrate":"320kbps"}""")
        assertEquals(320, f.bitrate)
        val n = json.decodeFromString<PlayerNote>("""{"id":3,"body":"hi","created_at":1732400000.25}""")
        assertEquals(1732400000.25, n.createdAt!!, 0.001)
    }

    @Test
    fun aPlain401IsNotAnUnpairing() = runTest {
        // "Not signed in" (a lapsed profile pick), an expired cast token: the
        // device stays paired. only the server's revocation codes unpair it
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"success":false,"error":"Not signed in"}"""))
        val res = client.fetchHomeLayout()
        assertTrue(res is MusicApiResult.Error)
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"success":false,"error":"device_pick_expired","message":"No profile was picked in time."}"""),
        )
        assertTrue(client.fetchHomeLayout() is MusicApiResult.DeviceRevoked)
    }
}
