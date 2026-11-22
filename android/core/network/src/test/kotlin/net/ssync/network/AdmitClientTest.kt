package net.ssync.network

import kotlinx.coroutines.test.runTest
import net.ssync.connect.PairingCode
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdmitClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AdmitClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = AdmitClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            pinnedClient = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun redeemFetchAndPickSucceeds() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"device_token":"unprofiled-tok","expires_in_seconds":900}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"profiles":[{"id":"p1","name":"Alex","requires_pin":true}]}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"device_token":"perm-tok","profile_id":"p1","profile_name":"Alex"}"""),
        )

        val code = PairingCode.parse("K7M9P2X4Q8RTW3ZA").getOrThrow()
        val redeem = client.redeemCode(code, "Pixel 9") as AdmitResult.Success
        assertEquals("unprofiled-tok", redeem.data.deviceToken)

        val profiles = client.fetchProfilePickList(redeem.data.deviceToken) as AdmitResult.Success
        assertEquals(1, profiles.data.size)
        assertEquals("Alex", profiles.data.first().name)

        val picked = client.pickProfile(redeem.data.deviceToken, "p1", pin = "1234") as AdmitResult.Success
        assertEquals("perm-tok", picked.data.deviceToken)
        assertEquals("p1", picked.data.profileId)
    }

    @Test
    fun maps401ToUnprofiledTokenExpired() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = client.fetchProfilePickList("expired-tok")
        assertTrue(result is AdmitResult.UnprofiledTokenExpired)
    }

    @Test
    fun maps403ToInvalidCredentialsForPinRetry() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        val result = client.pickProfile("unprofiled-tok", "p1", pin = "0000")
        assertTrue(result is AdmitResult.InvalidCredentials)
    }
}
