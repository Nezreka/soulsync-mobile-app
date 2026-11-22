package net.ssync.connect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Conformance Gate against Muse's published `conformance-vectors.json`
 * (DESIGN-CONNECT v0, Amended Nov 21, 2026).
 */
class ConformanceVectorsTest {

    private val vectors = Json.parseToJsonElement(
        requireNotNull(javaClass.getResourceAsStream("/conformance-vectors.json")) {
            "Missing conformance-vectors.json in test resources"
        }.bufferedReader(Charsets.UTF_8).readText(),
    ).jsonObject

    @Test
    fun `identity vector reproduces lookup_id, fingerprint, and base64url pubkey`() {
        val idObj = vectors["identity"]!!.jsonObject
        val identity = ServerIdentity.fromEd25519PublicKeyHex(idObj["public_key_hex"]!!.jsonPrimitive.content)

        assertEquals(idObj["public_key_b64url"]!!.jsonPrimitive.content, identity.publicKeyBase64Url)
        assertEquals(idObj["lookup_id"]!!.jsonPrimitive.content, identity.lookupId)
        assertEquals(idObj["fingerprint"]!!.jsonPrimitive.content, identity.fingerprint)
        assertEquals(identity.lookupId.take(32), identity.dnsId)
    }

    @Test
    fun `pairing code vector reproduces alphabet and code_hash`() {
        val codeObj = vectors["code"]!!.jsonObject
        assertEquals(codeObj["alphabet"]!!.jsonPrimitive.content, PairingCode.ALPHABET)
        val parsed = PairingCode.parse(codeObj["code"]!!.jsonPrimitive.content).getOrThrow()
        assertEquals(codeObj["code_hash"]!!.jsonPrimitive.content, parsed.codeHash)
    }

    @Test
    fun `RFC 9180 Appendix A_2_1 vector reproduces enc, ciphertext, and plaintext byte-for-byte`() {
        val v = vectors["hpke_rfc9180_a21"]!!.jsonObject
        val info = CryptoUtils.fromHex(v["info_hex"]!!.jsonPrimitive.content)
        val skEm = CryptoUtils.fromHex(v["sk_em_hex"]!!.jsonPrimitive.content)
        val pkRm = CryptoUtils.fromHex(v["pk_rm_hex"]!!.jsonPrimitive.content)
        val skRm = CryptoUtils.fromHex(v["sk_rm_hex"]!!.jsonPrimitive.content)
        val expectedEnc = CryptoUtils.fromHex(v["enc_hex"]!!.jsonPrimitive.content)
        val expectedCt = CryptoUtils.fromHex(v["ciphertext_hex"]!!.jsonPrimitive.content)
        val plaintext = v["plaintext"]!!.jsonPrimitive.content.toByteArray(Charsets.UTF_8)
        val aad = v["aad"]!!.jsonPrimitive.content.toByteArray(Charsets.UTF_8)

        val sealed = BouncyCastleRfc9180Hpke.seal(
            recipientPublicKey = pkRm,
            plaintext = plaintext,
            info = info,
            aad = aad,
            ephemeralPrivateKey = skEm,
        )
        assertArrayEquals(expectedEnc + expectedCt, sealed)

        val opened = BouncyCastleRfc9180Hpke.open(
            recipientPrivateKey = skRm,
            sealedBytes = expectedEnc + expectedCt,
            info = info,
            aad = aad,
        )
        assertArrayEquals(plaintext, opened)

        // Negative: wrong AAD must fail
        assertThrows(HpkeDecryptionException::class.java) {
            BouncyCastleRfc9180Hpke.open(skRm, expectedEnc + expectedCt, info, "Count-1".toByteArray())
        }
    }

    @Test
    fun `sealed_answer vector passes full Option B verification and rejects negative mutations`() {
        val sa = vectors["sealed_answer"]!!.jsonObject
        val appPriv = CryptoUtils.fromHex(sa["app_private_hex"]!!.jsonPrimitive.content)
        val appPubExpected = CryptoUtils.fromHex(sa["app_public_hex"]!!.jsonPrimitive.content)
        val codeHash = sa["code_hash"]!!.jsonPrimitive.content
        val expectedInfoHex = sa["hpke_info_hex"]!!.jsonPrimitive.content
        val sealedBytes = CryptoUtils.fromHex(sa["sealed_hex"]!!.jsonPrimitive.content)
        val expectedPayload = sa["expected_payload"]!!.jsonObject

        // Verify derived app public key
        val derivedKeyPair = EphemeralX25519KeyPair.fromPrivateKey(appPriv)
        assertArrayEquals(appPubExpected, derivedKeyPair.publicKey)

        // Verify exact HPKE info byte layout
        val builtInfo = DefaultHpkeInfoBuilder.build(codeHash)
        assertEquals(expectedInfoHex, CryptoUtils.toLowerHex(builtInfo))

        // Positive full Option B verification
        val verifier = RendezvousAnswerVerifier()
        val verified = verifier.verifyAndUnseal(appPriv, sealedBytes, codeHash)
        assertEquals(expectedPayload["lookup_id"]!!.jsonPrimitive.content, verified.lookupId)
        assertEquals(expectedPayload["fingerprint"]!!.jsonPrimitive.content, verified.fingerprint)
        assertEquals(expectedPayload["cert_fingerprint"]!!.jsonPrimitive.content, verified.certFingerprintSha256)
        assertEquals(
            expectedPayload["addresses"]!!.jsonArray.map { it.jsonPrimitive.content },
            verified.candidateAddresses.map { it.url },
        )

        // Negative 1: wrong code_hash (info binding fails)
        assertThrows(AnswerVerificationException::class.java) {
            verifier.verifyAndUnseal(appPriv, sealedBytes, "0".repeat(64))
        }

        // Negative 2: tampered ciphertext
        val tampered = sealedBytes.copyOf()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 0x80).toByte()
        assertThrows(AnswerVerificationException::class.java) {
            verifier.verifyAndUnseal(appPriv, tampered, codeHash)
        }
    }

    @Test
    fun `dns_labels vector passes all ipv4, ipv6, and malformed rejections`() {
        val dns = vectors["dns_labels"]!!.jsonObject

        for (item in dns["ipv4"]!!.jsonArray) {
            val obj = item.jsonObject
            val ip = obj["ip"]!!.jsonPrimitive.content
            val label = obj["label"]!!.jsonPrimitive.content
            assertEquals(label, IpLabelCodec.encodeIp(ip))
            assertEquals(ip, IpLabelCodec.decodeLabel(label).hostAddress)
        }

        for (item in dns["ipv6"]!!.jsonArray) {
            val obj = item.jsonObject
            val ip = obj["ip"]!!.jsonPrimitive.content
            val label = obj["label"]!!.jsonPrimitive.content
            assertEquals(label, IpLabelCodec.encodeIp(ip))
            val decoded = IpLabelCodec.decodeLabel(label)
            assertEquals(label, CryptoUtils.toLowerHex(decoded.address))
        }

        for (bad in dns["malformed_labels"]!!.jsonArray) {
            val label = bad.jsonPrimitive.content
            assertThrows(IllegalArgumentException::class.java, { IpLabelCodec.decodeLabel(label) }, "Must reject '$label'")
        }
        assertTrue(dns["malformed_labels"]!!.jsonArray.isNotEmpty())
    }
}
