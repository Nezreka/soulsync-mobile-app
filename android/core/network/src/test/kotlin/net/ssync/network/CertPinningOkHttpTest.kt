package net.ssync.network

import net.ssync.connect.CryptoUtils
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import javax.net.ssl.SSLHandshakeException

class CertPinningOkHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var serverCert: HeldCertificate

    @Before
    fun setUp() {
        serverCert = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val handshakeCerts = HandshakeCertificates.Builder()
            .heldCertificate(serverCert)
            .build()

        server = MockWebServer()
        server.useHttps(handshakeCerts.sslSocketFactory(), false)
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun acceptsMatchingLeafCertFingerprint() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val expectedPin = CryptoUtils.sha256Hex(serverCert.certificate.encoded)
        val client = CertPinningOkHttp.createPinnedClient(expectedCertFingerprintSha256 = expectedPin)

        client.newCall(Request.Builder().url(server.url("/api/health")).build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("ok", resp.body?.string())
        }
    }

    @Test
    fun rejectsMismatchedLeafCertFingerprint() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("mitm"))
        val wrongPin = "ff".repeat(32)
        val client = CertPinningOkHttp.createPinnedClient(expectedCertFingerprintSha256 = wrongPin)

        try {
            client.newCall(Request.Builder().url(server.url("/api/health")).build()).execute().close()
            fail("Expected SSLHandshakeException on mismatched leaf certificate pin")
        } catch (_: SSLHandshakeException) {
            // Expected: MITM rejected
        }
    }
}
