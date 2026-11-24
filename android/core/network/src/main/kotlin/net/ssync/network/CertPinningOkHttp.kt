package net.ssync.network

import net.ssync.connect.CryptoUtils
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Leaf TLS certificate SHA-256 fingerprint pinning for direct SoulSync connections
 * (DESIGN-CONNECT v0 Section 5).
 *
 * Pinning the exact leaf certificate fingerprint from the verified HPKE sealed answer
 * defeats MITM attacks even on hostile networks or with rogue CAs.
 */
object CertPinningOkHttp {

    fun createPinnedClient(
        expectedCertFingerprintSha256: String,
        deviceTokenProvider: (() -> String?)? = null,
        onUnauthorized: (() -> Unit)? = null,
        connectTimeoutSeconds: Long = 10,
        readTimeoutSeconds: Long = 30,
    ): OkHttpClient {
        val normalizedPin = expectedCertFingerprintSha256.lowercase().replace(":", "")
        require(normalizedPin.length == 64 && normalizedPin.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Expected 64-char lowercase hex SHA-256 leaf cert fingerprint"
        }

        val trustManager = LeafCertPinTrustManager(normalizedPin)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }

        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 24
        }

        val builder = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            // Leaf cert fingerprint pin is the cryptographic server identity binding,
            // allowing direct IP/LAN fallback without weakening MITM protection.
            .hostnameVerifier { _, session ->
                val peerLeaf = session.peerCertificates.firstOrNull() as? X509Certificate
                    ?: return@hostnameVerifier false
                val peerFp = CryptoUtils.sha256Hex(peerLeaf.encoded)
                CryptoUtils.constantTimeEquals(peerFp, normalizedPin)
            }
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)

        if (deviceTokenProvider != null) {
            builder.addInterceptor(BearerTokenInterceptor(deviceTokenProvider, onUnauthorized))
        }

        return builder.build()
    }
}

private class LeafCertPinTrustManager(
    private val expectedSha256Hex: String,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not supported")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("Server presented an empty certificate chain")
        val actualSha256 = CryptoUtils.sha256Hex(leaf.encoded)
        if (!CryptoUtils.constantTimeEquals(actualSha256, expectedSha256Hex)) {
            throw CertificateException("TLS leaf certificate SHA-256 fingerprint mismatch")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private class BearerTokenInterceptor(
    private val tokenProvider: () -> String?,
    private val onUnauthorized: (() -> Unit)?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = tokenProvider()
        val request = if (!token.isNullOrBlank() && original.header("Authorization") == null) {
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            original
        }

        val response = chain.proceed(request)
        if (response.code == 401) {
            onUnauthorized?.invoke()
        }
        return response
    }
}
