// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel

import dev.ostrom.server.kernel.config.InvalidConfigException
import dev.ostrom.server.kernel.network.IpNetwork
import dev.ostrom.server.kernel.network.TrustedProxies
import dev.ostrom.server.kernel.network.formatIpAddress
import dev.ostrom.server.kernel.network.parseIpAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NetworkTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun network(text: String): IpNetwork = assertNotNull(IpNetwork.parse(text), text)

    private fun ip(text: String): ByteArray = assertNotNull(parseIpAddress(text), text)

    @Test
    fun parsesIpv4Addresses() {
        assertContentEquals(bytes(203, 0, 113, 7), parseIpAddress("203.0.113.7"))
        assertContentEquals(bytes(0, 0, 0, 0), parseIpAddress(" 0.0.0.0 "))
        assertContentEquals(bytes(255, 255, 255, 255), parseIpAddress("255.255.255.255"))
    }

    @Test
    fun refusesWhatIsNotAnIpv4Address() {
        listOf("", "localhost", "1.2.3", "1.2.3.4.5", "256.0.0.1", "01.2.3.4", "1.2.3.-4", "1.2.3.4:8080", "1..3.4")
            .forEach { assertNull(parseIpAddress(it), it) }
    }

    @Test
    fun parsesIpv6Addresses() {
        assertContentEquals(ByteArray(16), parseIpAddress("::"))
        assertContentEquals(ByteArray(15) + bytes(1), parseIpAddress("::1"))
        assertContentEquals(ByteArray(15) + bytes(1), parseIpAddress("[::1]"))
        assertContentEquals(bytes(0x20, 0x01, 0x0d, 0xb8) + ByteArray(12), parseIpAddress("2001:db8::"))
        assertContentEquals(
            bytes(0x20, 0x01, 0x0d, 0xb8, 0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0xAB, 0xCD),
            parseIpAddress("2001:DB8:1:2:3:4:5:abcd"),
        )
        assertContentEquals(bytes(0xfe, 0x80) + ByteArray(13) + bytes(1), parseIpAddress("fe80::1%eth0"))
        assertContentEquals(ByteArray(12) + bytes(192, 0, 2, 1), parseIpAddress("::192.0.2.1"))
    }

    @Test
    fun readsAnIpv4MappedAddressAsIpv4() {
        assertContentEquals(bytes(10, 0, 0, 1), parseIpAddress("::ffff:10.0.0.1"))
        assertContentEquals(bytes(10, 0, 0, 1), parseIpAddress("::ffff:a00:1"))
    }

    @Test
    fun refusesWhatIsNotAnIpv6Address() {
        listOf(
            "1::2::3", ":1", "1:", "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7:8::", "::1:2:3:4:5:6:7:8",
            "12345::", "g::1", "::1.2.3", "::1.2.3.4:5", "::+1",
        ).forEach { assertNull(parseIpAddress(it), it) }
    }

    @Test
    fun aNetworkHoldsTheAddressesOfItsPrefix() {
        val private = network("10.0.0.0/8")
        assertTrue(ip("10.255.0.1") in private)
        assertFalse(ip("11.0.0.1") in private)
        val odd = network("192.168.4.0/22")
        assertTrue(ip("192.168.7.255") in odd)
        assertFalse(ip("192.168.8.0") in odd)
        assertTrue(ip("203.0.113.7") in network("0.0.0.0/0"))
        assertTrue(ip("fd12::1") in network("fd00::/8"))
        assertFalse(ip("fe80::1") in network("fd00::/8"))
    }

    @Test
    fun aBareAddressIsANetworkOfOne() {
        assertTrue(ip("127.0.0.1") in network("127.0.0.1"))
        assertFalse(ip("127.0.0.2") in network("127.0.0.1"))
        assertTrue(ip("::1") in network("::1"))
    }

    @Test
    fun ipv4AndIpv6NeverMatchEachOther() {
        assertFalse(ip("::1") in network("0.0.0.0/0"))
        assertFalse(ip("10.0.0.1") in network("::/0"))
        assertTrue(ip("::ffff:10.0.0.1") in network("10.0.0.0/8"))
    }

    @Test
    fun refusesWhatIsNotANetwork() {
        listOf("10.0.0.0/33", "::/129", "10.0.0.0/-1", "10.0.0.0/+8", "10.0.0.0/", "10.0.0.0/x", "/8", "proxy.local/8")
            .forEach { assertNull(IpNetwork.parse(it), it) }
    }

    @Test
    fun writesAddressesInCanonicalForm() {
        mapOf(
            "203.0.113.7" to "203.0.113.7",
            "::" to "::",
            "::1" to "::1",
            "2001:DB8:0:0:0:0:0:1" to "2001:db8::1",
            "1:0:0:2:0:0:0:3" to "1:0:0:2::3",
            "1:0:0:2:0:0:3:4" to "1::2:0:0:3:4",
            "1:0:2:3:4:5:6:7" to "1:0:2:3:4:5:6:7",
            "1:2:3:4:5:6:7:8" to "1:2:3:4:5:6:7:8",
            "fe80:0:0:0:0:0:0:0" to "fe80::",
            "::ffff:198.51.100.1" to "198.51.100.1",
        ).forEach { (written, canonical) -> assertEquals(canonical, formatIpAddress(ip(written)), written) }
    }

    private val proxies = TrustedProxies.parse("10.0.0.0/8, fd00::/8")

    @Test
    fun aPeerThatIsNotATrustedProxyIsTheClientWhateverItForwards() {
        assertEquals("203.0.113.7", proxies.clientAddress("203.0.113.7", listOf("198.51.100.1")))
        assertEquals("localhost", proxies.clientAddress("localhost", listOf("198.51.100.1")))
        // The peer is canonical too: one client is one rate-limit key, whatever form the socket gives.
        assertEquals("203.0.113.7", proxies.clientAddress("::ffff:203.0.113.7", listOf("198.51.100.1")))
        assertEquals("2001:db8::7", TrustedProxies.NONE.clientAddress("2001:DB8:0:0::7", emptyList()))
        assertEquals("10.0.0.5", TrustedProxies.NONE.clientAddress("10.0.0.5", listOf("198.51.100.1")))
    }

    @Test
    fun behindATrustedProxyTheClientIsTheRightMostUntrustedHop() {
        assertEquals("198.51.100.1", proxies.clientAddress("10.0.0.5", listOf("198.51.100.1")))
        // A client that writes its own X-Forwarded-For only adds hops to the left of the real one.
        assertEquals("198.51.100.1", proxies.clientAddress("10.0.0.5", listOf("1.1.1.1, 198.51.100.1")))
        assertEquals("198.51.100.1", proxies.clientAddress("10.0.0.5", listOf("1.1.1.1", "198.51.100.1, 10.0.0.9")))
        assertEquals("2001:db8::7", proxies.clientAddress("fd00::2", listOf("2001:db8::7")))
    }

    @Test
    fun theClientAddressIsCanonicalSoThatOneClientIsOneKey() {
        assertEquals("2001:db8::7", proxies.clientAddress("10.0.0.5", listOf("[2001:DB8:0:0::7]")))
        assertEquals("198.51.100.1", proxies.clientAddress("10.0.0.5", listOf("::ffff:198.51.100.1")))
        assertEquals("10.0.0.9", proxies.clientAddress("10.0.0.5", listOf("198.51.100.1:5000, 10.0.0.9")))
    }

    @Test
    fun whenEveryHopIsTrustedTheLeftMostIsTheClient() {
        assertEquals("10.1.1.1", proxies.clientAddress("10.0.0.5", listOf("10.1.1.1, 10.2.2.2")))
    }

    @Test
    fun aMalformedHopStopsTheWalk() {
        assertEquals("10.0.0.5", proxies.clientAddress("10.0.0.5", emptyList()))
        assertEquals("10.0.0.5", proxies.clientAddress("10.0.0.5", listOf("unknown")))
        assertEquals("10.0.0.5", proxies.clientAddress("::ffff:10.0.0.5", listOf("unknown")))
        assertEquals("10.2.2.2", proxies.clientAddress("10.0.0.5", listOf("198.51.100.1, garbage, 10.2.2.2")))
        assertEquals("10.0.0.5", proxies.clientAddress("10.0.0.5", listOf(" , ")))
    }

    @Test
    fun readsTheTrustedProxiesSetting() {
        assertSame(TrustedProxies.NONE, TrustedProxies.parse(null))
        assertSame(TrustedProxies.NONE, TrustedProxies.parse(" , "))
        assertTrue(TrustedProxies.NONE.isEmpty)
        assertFalse(proxies.isEmpty)
        val error = assertFailsWith<InvalidConfigException> { TrustedProxies.parse("10.0.0.0/8, proxy.local") }
        assertEquals(
            "OSTROM_TRUSTED_PROXIES: 'proxy.local' is not an IP address or network (address/prefix).",
            error.message,
        )
    }
}
