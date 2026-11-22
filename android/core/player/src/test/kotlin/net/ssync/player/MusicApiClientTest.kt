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
    fun fetchesLibraryAndBuildsDirectStreamUrlWithoutConnectOrMetaSync() = runTest {
        soulSyncServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"tracks":[{"id":"t-42","title":"Midnight City","artist":"M83","album":"Hurry Up","duration_ms":243000,"format":"FLAC"}]}""",
            ),
        )
        val baseUrl = soulSyncServer.url("/").toString().trimEnd('/')
        val client = MusicApiClient(baseUrl = baseUrl, pinnedAuthenticatedClient = OkHttpClient())

        val result = client.fetchLibraryTracks() as MusicApiResult.Success
        assertEquals(1, result.data.size)
        assertEquals("Midnight City", result.data.first().title)

        val streamUrl = client.trackStreamUrl("t-42")
        assertEquals("$baseUrl/api/player/stream/track/t-42", streamUrl)
        assertFalse(streamUrl.contains("connect.ssync.net"))
        assertFalse(streamUrl.contains("metasync.ssync.net"))
    }

    @Test
    fun revokedDeviceTokenReturnsDeviceRevokedCleanly() = runTest {
        soulSyncServer.enqueue(MockResponse().setResponseCode(401))
        val client = MusicApiClient(
            baseUrl = soulSyncServer.url("/").toString().trimEnd('/'),
            pinnedAuthenticatedClient = OkHttpClient(),
        )

        val result = client.fetchRecentTracks()
        assertTrue(result is MusicApiResult.DeviceRevoked)
    }
}
