package net.ssync.connect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PairingCodeTest {

    @Test
    fun `parses valid 16-char code and computes lowercase sha256 code_hash`() {
        val code = PairingCode.parse("k7m9-p2x4-q8rt-w3za").getOrThrow()
        assertEquals("K7M9P2X4Q8RTW3ZA", code.rawForDirectRedemption())
        assertEquals(
            "f6bae887878e96e428a1474ff1ce607b038e1a689dab35a7c72840b3fb610586",
            code.codeHash,
        )
    }

    @Test
    fun `rejects ambiguous alphabet characters I L O U`() {
        for (badChar in listOf('I', 'L', 'O', 'U')) {
            val candidate = "K7M9P2X4Q8RTW3Z$badChar"
            assertTrue(PairingCode.parse(candidate).isFailure, "Expected rejection for '$badChar'")
        }
    }

    @Test
    fun `rejects wrong length`() {
        assertTrue(PairingCode.parse("K7M9P2X4").isFailure)
        assertTrue(PairingCode.parse("K7M9P2X4Q8RTW3ZAA").isFailure)
    }

    @Test
    fun `toString is redacted and never contains raw code`() {
        val code = PairingCode.parse("K7M9P2X4Q8RTW3ZA").getOrThrow()
        val rendered = code.toString()
        assertFalse(rendered.contains("K7M9"))
        assertFalse(rendered.contains("W3ZA"))
        assertTrue(rendered.contains("REDACTED"))
    }
}
