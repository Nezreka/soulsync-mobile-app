package net.ssync.connect

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.math.ec.rfc7748.X25519
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * Raw single-shot RFC 9180 HPKE Base Mode opener:
 * - KEM: DHKEM(X25519, HKDF-SHA256) (`0x0020`)
 * - KDF: HKDF-SHA256 (`0x0001`)
 * - AEAD: ChaCha20-Poly1305 (`0x0003`)
 *
 * Wire format: raw `enc (32 bytes) || ciphertext` with NO Tink keyset prefix.
 */
interface RawRfc9180HpkeOpener {
    fun open(
        recipientPrivateKey: ByteArray,
        sealedBytes: ByteArray,
        info: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray
}

interface Ed25519SignatureVerifier {
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}

class EphemeralX25519KeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray,
) {
    override fun toString(): String = "EphemeralX25519KeyPair(REDACTED)"

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): EphemeralX25519KeyPair {
            val priv = ByteArray(X25519.SCALAR_SIZE)
            val pub = ByteArray(X25519.POINT_SIZE)
            X25519.generatePrivateKey(random, priv)
            X25519.generatePublicKey(priv, 0, pub, 0)
            return EphemeralX25519KeyPair(priv, pub)
        }

        fun fromPrivateKey(privateKey: ByteArray): EphemeralX25519KeyPair {
            require(privateKey.size == X25519.SCALAR_SIZE) { "X25519 private key must be 32 bytes" }
            val priv = privateKey.copyOf()
            val pub = ByteArray(X25519.POINT_SIZE)
            X25519.generatePublicKey(priv, 0, pub, 0)
            return EphemeralX25519KeyPair(priv, pub)
        }
    }
}

/**
 * RFC 9180 Base Mode implementation backed by BouncyCastle's vetted
 * X25519, HMAC-SHA256, and ChaCha20-Poly1305 primitives.
 */
object BouncyCastleRfc9180Hpke : RawRfc9180HpkeOpener {
    private val HPKE_V1 = "HPKE-v1".toByteArray(Charsets.US_ASCII)
    private val KEM_SUITE_ID = byteArrayOf('K'.code.toByte(), 'E'.code.toByte(), 'M'.code.toByte(), 0x00, 0x20)
    private val HPKE_SUITE_ID = byteArrayOf(
        'H'.code.toByte(), 'P'.code.toByte(), 'K'.code.toByte(), 'E'.code.toByte(),
        0x00, 0x20, // KEM: DHKEM(X25519, HKDF-SHA256)
        0x00, 0x01, // KDF: HKDF-SHA256
        0x00, 0x03, // AEAD: ChaCha20Poly1305
    )
    private const val MODE_BASE: Byte = 0x00
    private const val N_ENC = 32
    private const val N_SECRET = 32
    private const val N_K = 32
    private const val N_N = 12
    private const val TAG_BITS = 128

    override fun open(
        recipientPrivateKey: ByteArray,
        sealedBytes: ByteArray,
        info: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        require(recipientPrivateKey.size == X25519.SCALAR_SIZE) { "Invalid X25519 private key length" }
        require(sealedBytes.size > N_ENC + 16) { "Sealed HPKE payload too short" }

        val enc = sealedBytes.copyOfRange(0, N_ENC)
        val ciphertext = sealedBytes.copyOfRange(N_ENC, sealedBytes.size)

        val pkR = ByteArray(X25519.POINT_SIZE)
        X25519.generatePublicKey(recipientPrivateKey, 0, pkR, 0)

        val dh = ByteArray(X25519.POINT_SIZE)
        val ok = X25519.calculateAgreement(recipientPrivateKey, 0, enc, 0, dh, 0)
        if (!ok) {
            throw HpkeDecryptionException("X25519 agreement rejected low-order point")
        }

        val sharedSecret = extractAndExpand(dh, enc + pkR)
        val (key, baseNonce) = keyScheduleBase(sharedSecret, info)

        return try {
            aeadOpen(key, baseNonce, aad, ciphertext)
        } catch (e: Exception) {
            throw HpkeDecryptionException("HPKE AEAD decryption failed", e)
        }
    }

    /**
     * Single-shot Base Mode seal (used for deterministic test vector verification & unit tests).
     */
    fun seal(
        recipientPublicKey: ByteArray,
        plaintext: ByteArray,
        info: ByteArray,
        aad: ByteArray = ByteArray(0),
        ephemeralPrivateKey: ByteArray? = null,
    ): ByteArray {
        require(recipientPublicKey.size == X25519.POINT_SIZE) { "Invalid X25519 public key length" }
        val eph = if (ephemeralPrivateKey != null) {
            EphemeralX25519KeyPair.fromPrivateKey(ephemeralPrivateKey)
        } else {
            EphemeralX25519KeyPair.generate()
        }
        val enc = eph.publicKey

        val dh = ByteArray(X25519.POINT_SIZE)
        val ok = X25519.calculateAgreement(eph.privateKey, 0, recipientPublicKey, 0, dh, 0)
        require(ok) { "X25519 agreement rejected low-order point" }

        val sharedSecret = extractAndExpand(dh, enc + recipientPublicKey)
        val (key, baseNonce) = keyScheduleBase(sharedSecret, info)
        val ciphertext = aeadSeal(key, baseNonce, aad, plaintext)
        return enc + ciphertext
    }

    private fun extractAndExpand(dh: ByteArray, kemContext: ByteArray): ByteArray {
        val eaePrk = labeledExtract(KEM_SUITE_ID, ByteArray(0), "eae_prk", dh)
        return labeledExpand(KEM_SUITE_ID, eaePrk, "shared_secret", kemContext, N_SECRET)
    }

    private fun keyScheduleBase(sharedSecret: ByteArray, info: ByteArray): Pair<ByteArray, ByteArray> {
        val pskIdHash = labeledExtract(HPKE_SUITE_ID, ByteArray(0), "psk_id_hash", ByteArray(0))
        val infoHash = labeledExtract(HPKE_SUITE_ID, ByteArray(0), "info_hash", info)
        val keyScheduleContext = byteArrayOf(MODE_BASE) + pskIdHash + infoHash

        val secret = labeledExtract(HPKE_SUITE_ID, sharedSecret, "secret", ByteArray(0))
        val key = labeledExpand(HPKE_SUITE_ID, secret, "key", keyScheduleContext, N_K)
        val baseNonce = labeledExpand(HPKE_SUITE_ID, secret, "base_nonce", keyScheduleContext, N_N)
        return key to baseNonce
    }

    private fun labeledExtract(suiteId: ByteArray, salt: ByteArray, label: String, ikm: ByteArray): ByteArray {
        val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val labeledIkm = concat(HPKE_V1, suiteId, label.toByteArray(Charsets.US_ASCII), ikm)
        return hmacSha256(actualSalt, labeledIkm)
    }

    private fun labeledExpand(
        suiteId: ByteArray,
        prk: ByteArray,
        label: String,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val lengthBytes = byteArrayOf(((length ushr 8) and 0xFF).toByte(), (length and 0xFF).toByte())
        val labeledInfo = concat(lengthBytes, HPKE_V1, suiteId, label.toByteArray(Charsets.US_ASCII), info)

        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            t = hmacSha256(prk, concat(t, labeledInfo, byteArrayOf(counter.toByte())))
            out.write(t)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val hmac = HMac(SHA256Digest())
        hmac.init(KeyParameter(key))
        hmac.update(data, 0, data.size)
        val out = ByteArray(hmac.macSize)
        hmac.doFinal(out, 0)
        return out
    }

    private fun aeadOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), TAG_BITS, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(ciphertext.size))
        val len = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
        val finalLen = cipher.doFinal(out, len)
        return out.copyOf(len + finalLen)
    }

    private fun aeadSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), TAG_BITS, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        val finalLen = cipher.doFinal(out, len)
        return out.copyOf(len + finalLen)
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val result = ByteArray(total)
        var offset = 0
        for (p in parts) {
            p.copyInto(result, offset)
            offset += p.size
        }
        return result
    }
}

object BouncyCastleEd25519Verifier : Ed25519SignatureVerifier {
    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        return try {
            val signer = Ed25519Signer()
            signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            signer.update(message, 0, message.size)
            signer.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    /** Helper for unit tests to sign messages with a 32-byte Ed25519 seed. */
    fun sign(privateSeed: ByteArray, message: ByteArray): ByteArray {
        require(privateSeed.size == 32) { "Ed25519 seed must be 32 bytes" }
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateSeed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }
}

class HpkeDecryptionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
