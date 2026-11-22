package net.ssync.connect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RendezvousAnswerVerifierTest {

    private val verifier = RendezvousAnswerVerifier()
    private val json = Json { encodeDefaults = true }

    private val serverSeed = CryptoUtils.fromHex(
        "d21bbb8e7905de16861496a49879ca96e25136e5d6d094f5b1baf85acc17c458",
    )
    private val serverPub = Ed25519PrivateKeyParameters(serverSeed, 0).generatePublicKey().encoded
    private val serverIdentity = ServerIdentity.fromEd25519PublicKey(serverPub)

    private val code = PairingCode.parse("K7M9P2X4Q8RTW3ZA").getOrThrow()
    private val otherCode = PairingCode.parse("A2B3C4D5E6F7G8H9").getOrThrow()
    private val appKeyPair = EphemeralX25519KeyPair.fromPrivateKey(
        CryptoUtils.fromHex("101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f"),
    )

    private fun buildSealed(
        payload: SealedAnswerPayload,
        signingSeed: ByteArray = serverSeed,
        hpkeInfoCodeHash: String = code.codeHash,
        corruptSignature: Boolean = false,
    ): ByteArray {
        val canonicalPayload = JsonSealedPayloadCodec.canonicalizeJson(json.encodeToJsonElement(payload))
        val sig = BouncyCastleEd25519Verifier.sign(signingSeed, canonicalPayload.toByteArray(Charsets.UTF_8))
        if (corruptSignature) {
            sig[0] = (sig[0].toInt() xor 0xFF).toByte()
        }
        val envelopeJson = """{"payload":$canonicalPayload,"signature":"${CryptoUtils.base64UrlEncodeNoPadding(sig)}"}"""
        val info = DefaultHpkeInfoBuilder.build(hpkeInfoCodeHash)
        return BouncyCastleRfc9180Hpke.seal(
            recipientPublicKey = appKeyPair.publicKey,
            plaintext = envelopeJson.toByteArray(Charsets.UTF_8),
            info = info,
        )
    }

    private fun validPayload(
        lookupId: String = serverIdentity.lookupId,
        codeHash: String = code.codeHash,
    ) = SealedAnswerPayload(
        v = 1,
        lookupId = lookupId,
        codeHash = codeHash,
        fingerprint = serverIdentity.fingerprint,
        serverPubkey = serverIdentity.publicKeyBase64Url,
        certFingerprintSha256 = "ab".repeat(32),
        addresses = listOf(
            "https://192-168-1-50.${serverIdentity.dnsId}.connect.ssync.net",
            "https://203-0-113-7.${serverIdentity.dnsId}.connect.ssync.net",
        ),
        serverName = "Home SoulSync",
    )

    @Test
    fun `accepts valid Option B sealed answer`() {
        val sealed = buildSealed(validPayload())
        val verified = verifier.verifyAndUnseal(appKeyPair.privateKey, sealed, code.codeHash)
        assertEquals(serverIdentity.lookupId, verified.lookupId)
        assertEquals(serverIdentity.dnsId, verified.dnsId)
        assertEquals("EE76-7F6E-8F86", verified.formattedFingerprint)
        assertEquals("ab".repeat(32), verified.certFingerprintSha256)
        assertEquals("Home SoulSync", verified.serverName)
        assertEquals(2, verified.candidateAddresses.size)
    }

    @Test
    fun `rejects cross-server replay when derived lookup_id mismatches payload lookup_id`() {
        val forgedLookupId = "00".repeat(32)
        val sealed = buildSealed(validPayload(lookupId = forgedLookupId))
        assertThrows(AnswerVerificationException::class.java) {
            verifier.verifyAndUnseal(appKeyPair.privateKey, sealed, code.codeHash)
        }
    }

    @Test
    fun `rejects cross-code replay when payload code_hash mismatches requested code_hash`() {
        val sealed = buildSealed(
            payload = validPayload(codeHash = otherCode.codeHash),
            hpkeInfoCodeHash = code.codeHash,
        )
        assertThrows(AnswerVerificationException::class.java) {
            verifier.verifyAndUnseal(appKeyPair.privateKey, sealed, code.codeHash)
        }
    }

    @Test
    fun `rejects invalid Ed25519 signature`() {
        val sealed = buildSealed(validPayload(), corruptSignature = true)
        assertThrows(AnswerVerificationException::class.java) {
            verifier.verifyAndUnseal(appKeyPair.privateKey, sealed, code.codeHash)
        }
    }

    @Test
    fun `rejects tampered HPKE ciphertext`() {
        val sealed = buildSealed(validPayload())
        sealed[sealed.lastIndex] = (sealed[sealed.lastIndex].toInt() xor 0x01).toByte()
        assertThrows(AnswerVerificationException::class.java) {
            verifier.verifyAndUnseal(appKeyPair.privateKey, sealed, code.codeHash)
        }
    }
}
