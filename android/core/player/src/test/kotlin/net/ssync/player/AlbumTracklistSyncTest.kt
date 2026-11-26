package net.ssync.player

import kotlinx.coroutines.test.runTest
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

class AlbumTracklistSyncTest {

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
    fun resolveFetchAsksTheServerAndParsesPendingStatus() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"album":{"id":5,"title":"Blue","tracks":[{"id":null,"title":null,"track_number":3,"is_missing":true}],"tracklist_sync":{"status":"pending"}}}""",
            ),
        )
        val res = client.fetchAlbumDetail(5, resolve = true) as MusicApiResult.Success
        assertEquals("/api/library/v2/albums/5?resolve=1", server.takeRequest().path)
        assertEquals("pending", res.data.tracklistSync?.status)
        assertTrue(res.data.isTracklistPending)
        assertEquals("", res.data.tracks.first().title)
    }

    @Test
    fun plainFetchSkipsResolveAndMissingSyncIsNotPending() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"album":{"id":5,"title":"Blue","tracks":[]}}"""))
        val res = client.fetchAlbumDetail(5) as MusicApiResult.Success
        assertEquals("/api/library/v2/albums/5", server.takeRequest().path)
        assertNull(res.data.tracklistSync)
        assertFalse(res.data.isTracklistPending)
    }

    @Test
    fun pollStopsOnceTheTracklistIsReady() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"album":{"id":5,"tracks":[],"tracklist_sync":{"status":"pending"}}}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"album":{"id":5,"tracks":[{"title":"River","track_number":1,"is_missing":true}],"tracklist_sync":{"status":"ready"}}}""",
            ),
        )
        val seen = mutableListOf<LibAlbumDetail>()
        client.pollAlbumTracklist(5, intervalMs = 10L) { seen += it }
        assertEquals(2, seen.size)
        assertEquals("River", seen.last().tracks.first().title)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun pollGivesUpAfterMaxWait() = runTest {
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"album":{"id":5,"tracks":[],"tracklist_sync":{"status":"pending"}}}"""))
        }
        var updates = 0
        client.pollAlbumTracklist(5, intervalMs = 10L, maxWaitMs = 30L) { updates++ }
        assertEquals(3, updates)
    }

    @Test
    fun readyAndFailedAreNotPending() {
        assertFalse(LibAlbumDetail(id = 1, tracklistSync = TracklistSync("ready")).isTracklistPending)
        assertFalse(LibAlbumDetail(id = 1, tracklistSync = TracklistSync("failed")).isTracklistPending)
        assertFalse(LibAlbumDetail(id = 1, tracklistSync = TracklistSync(null)).isTracklistPending)
    }

    @Test
    fun untitledTrackShowsTrackNumberElsePosition() {
        assertEquals("Track 4", LibTrack(title = "", trackNumber = 4, isMissing = true).displayTitle(9))
        assertEquals("Track 9", LibTrack(title = " ", isMissing = true).displayTitle(9))
        assertEquals("Track 2", LibTrack(title = "null", trackNumber = 2, isMissing = true).displayTitle(9))
        assertEquals("River", LibTrack(title = "River", trackNumber = 2, isMissing = true).displayTitle(9))
    }

    @Test
    fun onlyTitledMissingTracksCanBeRequested() {
        assertFalse(LibTrack(title = "", trackNumber = 4, isMissing = true).isRequestable)
        assertFalse(LibTrack(title = "NULL", isMissing = true).isRequestable)
        assertTrue(LibTrack(title = "River", isMissing = true).isRequestable)
        val owned = LibTrack(id = 3, title = "River", fileCount = 1)
        assertTrue(owned.isOwned)
        assertFalse(owned.isRequestable)
    }
}
