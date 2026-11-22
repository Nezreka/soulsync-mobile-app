package net.ssync.network

import kotlinx.coroutines.test.runTest
import net.ssync.connect.CandidateAddress
import net.ssync.connect.CandidateScope
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CandidateAddressRacerTest {

    private lateinit var lanServer: MockWebServer
    private lateinit var wanServer: MockWebServer

    @Before
    fun setUp() {
        lanServer = MockWebServer().apply { start() }
        wanServer = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        lanServer.shutdown()
        wanServer.shutdown()
    }

    @Test
    fun prioritizesLanOverWanWhenBothReachable() = runTest {
        lanServer.enqueue(MockResponse().setResponseCode(200))
        wanServer.enqueue(MockResponse().setResponseCode(200))

        val racer = CandidateAddressRacer(OkHttpClient())
        val candidates = listOf(
            CandidateAddress(url = wanServer.url("/").toString().trimEnd('/'), scope = CandidateScope.WAN),
            CandidateAddress(url = lanServer.url("/").toString().trimEnd('/'), scope = CandidateScope.LAN),
        )

        val winner = racer.selectBestBaseUrl(candidates)
        assertEquals(lanServer.url("/").toString().trimEnd('/'), winner)
        assertEquals(0, wanServer.requestCount)
    }

    @Test
    fun fallsBackToWanWhenLanFails() = runTest {
        lanServer.enqueue(MockResponse().setResponseCode(503))
        wanServer.enqueue(MockResponse().setResponseCode(200))

        val racer = CandidateAddressRacer(OkHttpClient())
        val candidates = listOf(
            CandidateAddress(url = lanServer.url("/").toString().trimEnd('/'), scope = CandidateScope.LAN),
            CandidateAddress(url = wanServer.url("/").toString().trimEnd('/'), scope = CandidateScope.WAN),
        )

        val winner = racer.selectBestBaseUrl(candidates)
        assertEquals(wanServer.url("/").toString().trimEnd('/'), winner)
    }
}
