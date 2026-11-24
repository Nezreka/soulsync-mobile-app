package net.ssync.player

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlayerApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: PlayerApiClient

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
    fun decodesVideoCatalogAndArtworkUrls() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"movies":[{"id":7,"title":"Blade Runner 2049","year":2017,"rating":8.0,"has_poster":true}],"shows":[{"id":12,"title":"Severance","year":2022,"episode_count":19,"has_poster":true}],"recent_episodes":[],"youtube":[{"id":"abc123","title":"Live Session","channel_title":"KEXP"}],"kinds":{"movies":true,"shows":true,"youtube":true}}""",
            ),
        )
        val res = client.fetchVideoCatalog() as MusicApiResult.Success
        assertEquals(1, res.data.movies.size)
        assertEquals("Blade Runner 2049", res.data.movies.first().title)
        assertEquals("Severance", res.data.shows.first().title)
        assertEquals("Live Session", res.data.youtube.first().title)
        assertTrue(client.artwork.moviePoster(7).endsWith("/api/video/poster/movie/7?w=342"))
        assertTrue(client.streamUrl("movie", "7").endsWith("/api/player/stream/movie/7"))
    }

    @Test
    fun fetchesWatchlistPresenceAndAudiobooks() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"items":[{"item_type":"movie","item_id":"7","meta":{"title":"Blade Runner 2049"}}]}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"sessions":[{"profile_id":2,"profile_name":"Sam","title":"Severance","you":false}],"sharing":true}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"books":[{"asin":"B001","title":"Project Hail Mary","author":"Andy Weir","runtime_minutes":970}]}"""),
        )

        val wl = client.fetchWatchlist() as MusicApiResult.Success
        assertEquals("Blade Runner 2049", wl.data.first().meta?.title)

        val presence = client.fetchPresence() as MusicApiResult.Success
        assertEquals(1, presence.data.sessions.size)
        assertEquals("Sam", presence.data.sessions.first().profileName)

        val books = client.fetchAudiobooks() as MusicApiResult.Success
        assertEquals("Project Hail Mary", books.data.first().title)
    }

    @Test
    fun revokedDeviceReturnsDeviceRevoked() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"success":false,"error":"device_token_invalid","message":"This device isn't paired with this server any more."}"""))
        assertTrue(client.fetchContinue() is MusicApiResult.DeviceRevoked)
    }
}
