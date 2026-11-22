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
    fun redeemFetchAndPickMatchesSoulSyncDevicesApi() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"success":true,"token":"dev-tok-123","device_id":7,"pick_seconds":900}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"success":true,"profiles":[{"id":3,"name":"Alex","needs":"pin","avatar_color":"#1db954"}]}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"success":true,"profile":{"id":3,"name":"Alex"}}"""),
        )

        val code = PairingCode.parse("K7M9P2X4Q8RTW3ZA").getOrThrow()
        val redeem = client.redeemCode(code, "Pixel 9") as AdmitResult.Success
        assertEquals("dev-tok-123", redeem.data.token)
        val redeemReq = server.takeRequest()
        assertEquals("/api/devices/redeem", redeemReq.path)
        assertTrue(redeemReq.body.readUtf8().contains("\"app\":\"music\""))

        val profiles = client.fetchProfilePickList(redeem.data.token) as AdmitResult.Success
        assertEquals(1, profiles.data.size)
        assertEquals("Alex", profiles.data.first().name)
        assertTrue(profiles.data.first().requiresPin)
        assertEquals("/api/devices/me/profiles", server.takeRequest().path)

        val picked = client.pickProfile(redeem.data.token, 3, pin = "1234") as AdmitResult.Success
        assertEquals("dev-tok-123", picked.data.deviceToken)
        assertEquals("3", picked.data.profileId)
        assertEquals("Alex", picked.data.profileName)
        assertEquals("/api/devices/me/profile", server.takeRequest().path)
    }

    @Test
    fun maps401And409ExpiredToUnprofiledTokenExpired() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(client.fetchProfilePickList("expired-tok") is AdmitResult.UnprofiledTokenExpired)

        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"success":false,"error":"device_pick_expired"}"""),
        )
        assertTrue(client.pickProfile("expired-tok", 3) is AdmitResult.UnprofiledTokenExpired)
    }

    @Test
    fun maps403ToInvalidCredentialsForPinRetry() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        val result = client.pickProfile("unprofiled-tok", 3, pin = "0000")
        assertTrue(result is AdmitResult.InvalidCredentials)
    }
}
