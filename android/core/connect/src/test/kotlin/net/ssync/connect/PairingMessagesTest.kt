package net.ssync.connect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** #2328: a failed redeem says why, from the server's status */
class PairingMessagesTest {
    @Test
    fun `redeem failures by status`() {
        assertEquals(PairingMessages.CODE_USED, PairingMessages.forRedeemStatus(404))
        assertEquals(PairingMessages.TOO_MANY_TRIES, PairingMessages.forRedeemStatus(429))
        assertEquals(PairingMessages.LOST_SERVER, PairingMessages.forRedeemStatus(0))
        assertEquals(PairingMessages.FALLBACK, PairingMessages.forRedeemStatus(500))
        assertEquals("Couldn't reach that server with this code.", PairingMessages.FALLBACK)
    }
}
