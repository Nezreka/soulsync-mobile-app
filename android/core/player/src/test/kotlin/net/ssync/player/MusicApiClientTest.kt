package net.ssync.player

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MusicApiClientTest {

    private lateinit var soulSyncServer: MockWebServer

    @Before
    fun setUp() {
        soulSyncServer = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        soulSyncServer.shutdown()
    }

    @Test
    fun parsesSoulSyncLibraryEnvelopeAndBuildsDirectStreamUrl() = runTest {
        soulSyncServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"tracks":[{"id":42,"title":"Midnight City","track_artist":"M83","duration":243.5,"file_path":"/music/M83/Midnight City.flac"}]},"error":null}""",
            ),
        )
        val baseUrl = soulSyncServer.url("/").toString().trimEnd('/')
        val client = MusicApiClient(baseUrl = baseUrl, pinnedAuthenticatedClient = OkHttpClient())

        val result = client.fetchRecentTracks() as MusicApiResult.Success
        assertEquals(1, result.data.size)
        val track = result.data.first()
        assertEquals("42", track.id)
        assertEquals("Midnight City", track.title)
        assertEquals("M83", track.artist)
        assertEquals(243500L, track.durationMs)
        assertEquals("flac", track.format)

        val recorded = soulSyncServer.takeRequest()
        assertTrue(recorded.path!!.startsWith("/api/library/tracks/recent"))

        val streamUrl = client.trackStreamUrl(track.id)
        assertEquals("$baseUrl/api/player/stream/track/42", streamUrl)
        assertFalse(streamUrl.contains("connect.ssync.net"))
        assertFalse(streamUrl.contains("metasync.ssync.net"))
    }

    @Test
    fun parsesPlayerPlaylistsYoursAndMirrored() = runTest {
        soulSyncServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"yours":[{"id":1,"name":"Liked Songs","kind":"yours","liked":true,"track_count":12}],"mirrored":[{"id":2,"name":"Synthwave","kind":"mirrored","liked":false,"track_count":30}]}""",
            ),
        )
        val client = MusicApiClient(
            baseUrl = soulSyncServer.url("/").toString().trimEnd('/'),
            pinnedAuthenticatedClient = OkHttpClient(),
        )

        val result = client.fetchPlaylists() as MusicApiResult.Success
        assertEquals(2, result.data.size)
        assertEquals("Liked Songs", result.data[0].name)
        assertEquals("Synthwave", result.data[1].name)
    }

    @Test
    fun reportProgressUsesPutWithSoulSyncSchema() = runTest {
        soulSyncServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        val client = MusicApiClient(
            baseUrl = soulSyncServer.url("/").toString().trimEnd('/'),
            pinnedAuthenticatedClient = OkHttpClient(),
        )

        val result = client.reportProgress(trackId = "42", positionMs = 30_000L, durationMs = 240_000L)
        assertTrue(result is MusicApiResult.Success)
        val req = soulSyncServer.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/player/progress", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"item_type\":\"track\""))
        assertTrue(body.contains("\"item_id\":\"42\""))
    }

    @Test
    fun revokedDeviceTokenReturnsDeviceRevokedCleanly() = runTest {
        soulSyncServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"success":false,"error":"device_token_invalid","message":"This device isn't paired with this server any more."}"""))
        val client = MusicApiClient(
            baseUrl = soulSyncServer.url("/").toString().trimEnd('/'),
            pinnedAuthenticatedClient = OkHttpClient(),
        )

        val result = client.fetchRecentTracks()
        assertTrue(result is MusicApiResult.DeviceRevoked)
    }
}
