package net.ssync.connect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class PairingLinkTest {

    private val lookupId = "ae0396a668c3" + "0".repeat(52)

    private fun link(jsonText: String): String =
        "soulsync://pair?d=" + Base64.getUrlEncoder().withoutPadding().encodeToString(jsonText.toByteArray())

    // what the server's pairing_link makes (sorted keys, compact)
    private val serverJson =
        """{"addresses":[{"kind":"local","url":"http://192.168.1.5:8008"}],"code":"7KQ4-M2XD-9FHA-T3VB",""" +
            """"fingerprint":"ae0396a668c3","lookup_id":"$lookupId","v":1}"""

    private fun message(raw: String?): String? = PairingLink.parse(raw).exceptionOrNull()?.message

    @Test
    fun `reads a server link`() {
        val parsed = PairingLink.parse(link(serverJson)).getOrThrow()
        assertEquals("7KQ4M2XD9FHAT3VB", parsed.code.rawForDirectRedemption())
        assertEquals("7KQ4-M2XD-9FHA-T3VB", parsed.codeText)
        assertEquals("ae0396a668c3", parsed.fingerprint)
        assertEquals("AE03-96A6-68C3", parsed.formattedFingerprint)
        assertFalse(parsed.toString().contains("7KQ4"))
    }

    @Test
    fun `matches the exact bytes the server sends`() {
        // copied from python: pairing_link(pairing_payload(...)) for the payload above
        val fromServer = "soulsync://pair?d=eyJhZGRyZXNzZXMiOlt7ImtpbmQiOiJsb2NhbCIsInVybCI6Imh0dHA6Ly8xOTIuMTY4LjEuNTo4MDA4In1dLCJjb2RlIjoiN0tRNC1NMlhELTlGSEEtVDNWQiIsImZpbmdlcnByaW50IjoiYWUwMzk2YTY2OGMzIiwibG9va3VwX2lkIjoiYWUwMzk2YTY2OGMzMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMCIsInYiOjF9"
        assertEquals(fromServer, link(serverJson))
        assertTrue(PairingLink.parse(fromServer).isSuccess)
        // padded and upper case scheme are fine too
        val padded = "SoulSync://Pair?d=" + Base64.getUrlEncoder().encodeToString(serverJson.toByteArray())
        assertTrue(PairingLink.parse(padded).isSuccess)
    }

    @Test
    fun `ignores fields it doesn't know`() {
        val withExtra = serverJson.dropLast(1) + ""","name":"Home","later":{"x":[1,2]}}"""
        assertTrue(PairingLink.parse(link(withExtra)).isSuccess)
    }

    @Test
    fun `a fingerprint alone or a lookup id alone is enough`() {
        assertEquals(
            "ae0396a668c3",
            PairingLink.parse(link("""{"v":1,"code":"7KQ4M2XD9FHAT3VB","lookup_id":"$lookupId"}""")).getOrThrow().fingerprint,
        )
        assertNull(PairingLink.parse(link("""{"v":1,"code":"7KQ4M2XD9FHAT3VB"}""")).getOrThrow().fingerprint)
    }

    @Test
    fun `a fingerprint that disagrees with the lookup id is refused`() {
        val bad = """{"v":1,"code":"7KQ4M2XD9FHAT3VB","lookup_id":"$lookupId","fingerprint":"ffffffffffff"}"""
        assertEquals(PairingLink.BAD_LINK, message(link(bad)))
    }

    @Test
    fun `junk fingerprints are dropped, not trusted`() {
        val junk = """{"v":1,"code":"7KQ4M2XD9FHAT3VB","fingerprint":"<script>","lookup_id":42}"""
        assertNull(PairingLink.parse(link(junk)).getOrThrow().fingerprint)
    }

    @Test
    fun `a newer version says to update`() {
        assertEquals(PairingLink.NEWER_LINK, message(link("""{"v":2,"code":"7KQ4M2XD9FHAT3VB"}""")))
    }

    @Test
    fun `bad links get the plain message`() {
        val bad = listOf(
            null,
            "",
            "https://example.com/pair?d=abc",
            "soulsync://other?d=abc",
            "soulsync://pair",
            "soulsync://pair?d=",
            "soulsync://pair?d=%%%%",
            "soulsync://pair?d=a",
            link("not json"),
            link("[1,2]"),
            link("""{"code":"7KQ4M2XD9FHAT3VB"}"""),
            link("""{"v":"1","code":"7KQ4M2XD9FHAT3VB"}"""),
            link("""{"v":0,"code":"7KQ4M2XD9FHAT3VB"}"""),
            link("""{"v":1}"""),
            link("""{"v":1,"code":"SHORT"}"""),
            link("""{"v":1,"code":"7KQ4M2XD9FHAT3VI"}"""),
            link("""{"v":1,"code":12345}"""),
        )
        for (raw in bad) assertEquals(PairingLink.BAD_LINK, message(raw), "for $raw")
    }

    @Test
    fun `oversized links are refused before decoding`() {
        assertEquals(PairingLink.BAD_LINK, message("soulsync://pair?d=" + "A".repeat(PairingLink.MAX_LINK_CHARS)))
        val big = """{"v":1,"code":"7KQ4M2XD9FHAT3VB","pad":"${"x".repeat(PairingLink.MAX_JSON_BYTES)}"}"""
        assertEquals(PairingLink.BAD_LINK, message(link(big)))
    }

    @Test
    fun `knows its own links`() {
        assertTrue(PairingLink.isPairingLink("soulsync://pair?d=x"))
        assertTrue(PairingLink.isPairingLink("soulsync://pair/?d=x"))
        assertFalse(PairingLink.isPairingLink("soulsync://pairing?d=x"))
        assertFalse(PairingLink.isPairingLink(null))
    }

    // ── what an already paired phone does ──

    private val parsed get() = PairingLink.parse(link(serverJson)).getOrThrow()

    @Test
    fun `not paired pairs`() {
        assertEquals(PairingLinkAction.Pair, PairingLinkDecision.decide(parsed, null))
    }

    @Test
    fun `the same server is already paired`() {
        assertEquals(PairingLinkAction.AlreadyPaired, PairingLinkDecision.decide(parsed, "ae0396a668c3"))
        assertEquals(PairingLinkAction.AlreadyPaired, PairingLinkDecision.decide(parsed, "AE0396A668C3"))
        // a full lookup id compares by its first 12
        assertEquals(PairingLinkAction.AlreadyPaired, PairingLinkDecision.decide(parsed, lookupId))
    }

    @Test
    fun `another server asks to switch`() {
        assertEquals(PairingLinkAction.Switch, PairingLinkDecision.decide(parsed, "0123456789ab"))
    }

    @Test
    fun `a link that can't be matched asks to switch, never pairs over the top`() {
        val noFp = PairingLink.parse(link("""{"v":1,"code":"7KQ4M2XD9FHAT3VB"}""")).getOrThrow()
        assertEquals(PairingLinkAction.Switch, PairingLinkDecision.decide(noFp, "ae0396a668c3"))
        assertEquals(PairingLinkAction.Pair, PairingLinkDecision.decide(noFp, null))
    }
}
