package net.ssync.connect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerIdentityTest {

    @Test
    fun `derives lookup_id, 32-char dns_id, and 12-char fingerprint from ed25519 pubkey`() {
        val id = ServerIdentity.fromEd25519PublicKeyHex(
            "dd036eab4c5a0e25de3e1ff8ee724a87b5ff3de5c942dddb713cbb3e56236a90",
        )
        assertEquals(
            "ee767f6e8f8602fe277759e44a85cca92c1edcdfa62f789ac99a275b3c5234bd",
            id.lookupId,
        )
        assertEquals("ee767f6e8f8602fe277759e44a85cca9", id.dnsId)
        assertEquals(32, id.dnsId.length)
        assertEquals("ee767f6e8f86", id.fingerprint)
        assertEquals("EE76-7F6E-8F86", id.formattedFingerprint)
        assertEquals("3QNuq0xaDiXePh_47nJKh7X_PeXJQt3bcTy7PlYjapA", id.publicKeyBase64Url)
    }

    @Test
    fun `toString redacts full lookup_id`() {
        val id = ServerIdentity.fromEd25519PublicKeyHex(
            "dd036eab4c5a0e25de3e1ff8ee724a87b5ff3de5c942dddb713cbb3e56236a90",
        )
        val rendered = id.toString()
        assertFalse(rendered.contains(id.lookupId))
        assertTrue(rendered.contains("REDACTED"))
        assertTrue(rendered.contains("EE76-7F6E-8F86"))
    }
}
