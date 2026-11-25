package net.ssync.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** the admin's orders on the heartbeat's answer (Server Activity's stop and message) */
class ServerOrdersTest {
    @Test
    fun `a stop carries the admin's words`() {
        val order = ServerOrders.parse("""{"success":true,"stop":{"message":"Server going down for the night"}}""")!!
        assertEquals(true, order.stop)
        assertEquals("Stream stopped", order.header)
        assertEquals("Server going down for the night", order.text)
    }

    @Test
    fun `a stop with no words says who stopped it`() {
        val order = ServerOrders.parse("""{"stop":{"message":""}}""")!!
        assertEquals("The server administrator ended this stream.", order.text)
    }

    @Test
    fun `a message does not stop`() {
        val order = ServerOrders.parse("""{"message":{"header":"Heads up","text":"Restarting in 5 minutes"}}""")!!
        assertEquals(false, order.stop)
        assertEquals("Heads up", order.header)
        assertEquals("Restarting in 5 minutes", order.text)
    }

    @Test
    fun `an ordinary answer or junk is nothing`() {
        assertNull(ServerOrders.parse("""{"success":true,"shared":true}"""))
        assertNull(ServerOrders.parse("""{"message":{"text":""}}"""))
        assertNull(ServerOrders.parse("not json"))
        assertNull(ServerOrders.parse(""))
    }
}
