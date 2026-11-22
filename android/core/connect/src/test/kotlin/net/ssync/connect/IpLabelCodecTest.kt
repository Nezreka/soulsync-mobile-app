package net.ssync.connect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IpLabelCodecTest {

    private val sampleDnsId = "ee767f6e8f8602fe277759e44a85cca9"

    @Test
    fun `encodes and decodes ipv4 and ipv6 labels`() {
        assertEquals("192-168-1-50", IpLabelCodec.encodeIp("192.168.1.50"))
        assertEquals("192.168.1.50", IpLabelCodec.decodeLabel("192-168-1-50").hostAddress)

        assertEquals("20010db8000000000000000000000001", IpLabelCodec.encodeIp("2001:db8::1"))
        assertEquals("00000000000000000000000000000001", IpLabelCodec.encodeIp("::1"))
    }

    @Test
    fun `builds and parses prod and staging hostnames using 32-char dns_id`() {
        val prodHost = IpLabelCodec.buildHostname("203.0.113.7", sampleDnsId, staging = false)
        assertEquals("203-0-113-7.$sampleDnsId.connect.ssync.net", prodHost)
        val parsedProd = IpLabelCodec.parseHostname(prodHost)
        assertFalse(parsedProd.staging)
        assertEquals(sampleDnsId, parsedProd.dnsId)
        assertEquals("203.0.113.7", parsedProd.address.hostAddress)

        val stagingHost = IpLabelCodec.buildHostname("192.168.1.50", sampleDnsId, staging = true)
        assertEquals("192-168-1-50.$sampleDnsId.staging.connect.ssync.net", stagingHost)
        val parsedStaging = IpLabelCodec.parseHostname(stagingHost)
        assertTrue(parsedStaging.staging)
        assertEquals("192.168.1.50", parsedStaging.address.hostAddress)

        assertEquals("*.$sampleDnsId.connect.ssync.net", IpLabelCodec.buildWildcardHostname(sampleDnsId))
        assertEquals(
            "*.$sampleDnsId.staging.connect.ssync.net",
            IpLabelCodec.buildWildcardHostname(sampleDnsId, staging = true),
        )
    }

    @Test
    fun `rejects full 64-char lookup_id as dns_id because DNS labels max at 63 chars`() {
        val fullLookupId = "ee767f6e8f8602fe277759e44a85cca92c1edcdfa62f789ac99a275b3c5234bd"
        assertThrows(IllegalArgumentException::class.java) {
            IpLabelCodec.buildHostname("192.168.1.50", fullLookupId)
        }
    }

    @Test
    fun `rejects malformed ip labels`() {
        val malformed = listOf(
            "192-168-01-50",
            "192-168-1-256",
            "192-168-1",
            "192.168.1.50",
            "20010DB8000000000000000000000001",
            "gggggggggggggggggggggggggggggggg",
            "",
            "1-2-3-4-5",
        )
        for (bad in malformed) {
            assertThrows(IllegalArgumentException::class.java, { IpLabelCodec.decodeLabel(bad) }, "Should reject '$bad'")
        }
    }
}
