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
        assertEquals(PairingMessages.UNKNOWN_CODE, (outcome as LookupOutcome.Unreachable).userMessage)
        assertEquals(LookupOutcome.Reason.UNKNOWN_CODE, outcome.reason)

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

    // #2328: each failure says what actually went wrong

    private suspend fun lookupWith(response: MockResponse): LookupOutcome.Unreachable {
        server.enqueue(response)
        val client = RendezvousClient(baseUrl = server.url("/").toString())
        val outcome = client.lookup(PairingCode.parse("K7M9P2X4Q8RTW3ZA").getOrThrow())
        assertTrue(outcome is LookupOutcome.Unreachable, "got $outcome")
        return outcome as LookupOutcome.Unreachable
    }

    @Test
    fun `the relay's own unknown code answer, byte for byte, reads as an unknown code`() = runTest {
        // what connect.ssync.net returned for an unknown code on Nov 27 2026
        val out = lookupWith(
            MockResponse().setResponseCode(404)
                .setBody("""{"error": {"code": "unknown_or_expired_code", "message": "unknown or expired code"}}"""),
        )
        assertEquals(LookupOutcome.Reason.UNKNOWN_CODE, out.reason)
        assertTrue(out.userMessage.contains("Settings, Devices"))
        assertTrue(out.userMessage.contains("isn't connected for pairing"))
    }

    @Test
    fun `a relay error without our envelope is a relay problem`() = runTest {
        val out = lookupWith(MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"))
        assertEquals(LookupOutcome.Reason.RELAY_ERROR, out.reason)
        assertEquals(PairingMessages.RELAY_ERROR, out.userMessage)
    }

    @Test
    fun `an error code the app doesn't know is a relay problem`() = runTest {
        val out = lookupWith(
            MockResponse().setResponseCode(504)
                .setBody("""{"error":{"code":"server_timeout","message":"no answer"}}"""),
        )
        assertEquals(LookupOutcome.Reason.RELAY_ERROR, out.reason)
    }

    @Test
    fun `an answer that won't open is a bad answer`() = runTest {
        val out = lookupWith(MockResponse().setResponseCode(200).setBody("""{"sealed":"abcd"}"""))
        assertEquals(LookupOutcome.Reason.BAD_ANSWER, out.reason)
        assertEquals(PairingMessages.BAD_ANSWER, out.userMessage)
    }

    @Test
    fun `no relay at all is the phone's internet`() = runTest {
        val url = server.url("/").toString()
        server.shutdown()
        val client = RendezvousClient(baseUrl = url)
        val outcome = client.lookup(PairingCode.parse("K7M9P2X4Q8RTW3ZA").getOrThrow())
        assertTrue(outcome is LookupOutcome.Unreachable)
        assertEquals(LookupOutcome.Reason.NO_INTERNET, (outcome as LookupOutcome.Unreachable).reason)
        assertEquals(PairingMessages.NO_INTERNET, outcome.userMessage)
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
