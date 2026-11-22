package net.ssync.connect

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RendezvousClientTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `lookup sends ONLY code_hash and ephemeral_public_key and NEVER raw pairing code`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"error":{"code":"unknown_or_expired_code","message":"Not found"}}"""),
        )
        val client = RendezvousClient(baseUrl = server.url("/").toString())
        val code = PairingCode.parse("K7M9P2X4Q8RTW3ZA").getOrThrow()

        val outcome = client.lookup(code)
        assertTrue(outcome is LookupOutcome.Unreachable)
        assertEquals(
            "Couldn't reach that server with this code.",
            (outcome as LookupOutcome.Unreachable).userMessage,
        )

        val recorded = server.takeRequest()
        assertEquals("/v1/lookup", recorded.path)
        val body = recorded.body.readUtf8()
        assertFalse(body.contains("K7M9P2X4Q8RTW3ZA"), "Raw pairing code must NEVER be sent to connect.ssync.net")
        assertTrue(body.contains(code.codeHash), "Expected code_hash in lookup request")
        assertTrue(body.contains("reply_key"))
    }

    @Test
    fun `rate_limited error envelope preserves retry_after_seconds`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"error":{"code":"rate_limited","message":"Slow down","retry_after_seconds":45}}"""),
        )
        val client = RendezvousClient(baseUrl = server.url("/").toString())
        val code = PairingCode.parse("K7M9P2X4Q8RTW3ZA").getOrThrow()

        val outcome = client.lookup(code)
        assertTrue(outcome is LookupOutcome.RateLimited)
        assertEquals(45L, (outcome as LookupOutcome.RateLimited).retryAfterSeconds)
    }

    @Test
    fun `status endpoint parses unauthenticated reachability response`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true,"version":"0.1.0","time":"2026-11-21T20:00:00Z"}"""),
        )
        val client = RendezvousClient(baseUrl = server.url("/").toString())
        val status = client.getStatus().getOrThrow()
        assertTrue(status.ok)
        assertEquals("0.1.0", status.version)
    }
}
