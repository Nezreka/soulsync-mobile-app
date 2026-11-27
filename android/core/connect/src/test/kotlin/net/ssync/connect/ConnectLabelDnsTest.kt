package net.ssync.connect

import okhttp3.Dns
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.InetAddress

/** #2328: a connect name resolves from its own first label, never via dns */
class ConnectLabelDnsTest {
    private val dnsId = "0123456789abcdef0123456789abcdef"

    private val refuse = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            throw java.net.UnknownHostException("fallback asked for $hostname")
    }
    private val fallback = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            listOf(InetAddress.getByAddress(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `a home address name resolves without asking dns`() {
        val dns = ConnectLabelDns(refuse)
        val got = dns.lookup("192-168-1-5.$dnsId.connect.ssync.net")
        assertEquals(listOf(InetAddress.getByName("192.168.1.5")), got)
        assertEquals(
            listOf(InetAddress.getByName("10.0.0.9")),
            dns.lookup("10-0-0-9.$dnsId.staging.connect.ssync.net."),
        )
        assertEquals(
            listOf(InetAddress.getByName("192.168.1.5")),
            dns.lookup("192-168-1-5.${dnsId.uppercase()}.CONNECT.SSYNC.NET"),
        )
    }

    @Test
    fun `an ipv6 label resolves too`() {
        val v6 = InetAddress.getByName("fd00::1")
        val label = CryptoUtils.toLowerHex(v6.address)
        assertEquals(listOf(v6), ConnectLabelDns(refuse).lookup("$label.$dnsId.connect.ssync.net"))
    }

    @Test
    fun `any other name goes to dns`() {
        val dns = ConnectLabelDns(fallback)
        val other = listOf(InetAddress.getByAddress(byteArrayOf(1, 2, 3, 4)))
        assertEquals(other, dns.lookup("connect.ssync.net"))
        assertEquals(other, dns.lookup("music.example.com"))
        // a look-alike zone, a bad label, a bad dns id, an extra label
        assertEquals(other, dns.lookup("192-168-1-5.$dnsId.connect.ssync.net.evil.com"))
        assertEquals(other, dns.lookup("192-168-1-500.$dnsId.connect.ssync.net"))
        assertEquals(other, dns.lookup("192-168-1-5.nothex.connect.ssync.net"))
        assertEquals(other, dns.lookup("a.192-168-1-5.$dnsId.connect.ssync.net"))
        assertNull(ConnectLabelDns.decode("192-168-1-5.connect.ssync.net"))
    }
}
