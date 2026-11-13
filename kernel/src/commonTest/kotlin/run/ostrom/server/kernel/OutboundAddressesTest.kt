// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import run.ostrom.server.kernel.network.isPublicAddress
import run.ostrom.server.kernel.network.isReachableUrl
import run.ostrom.server.kernel.network.parseLegacyIpv4
import run.ostrom.server.kernel.network.isReachableAddress
import run.ostrom.server.kernel.network.parseIpAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The address rules of an outbound call to a URL someone else supplied (TC-CODE-SEC-1, TC-GWY-17). */
class OutboundAddressesTest {
    /** The bytes of an address as the resolver gives them: IPv4 mapped forms stay 16 bytes here. */
    private fun bytes(text: String): ByteArray {
        val parsed = assertNotNull(parseIpAddress(text), text)
        return if (parsed.size == 4 && text.contains(':')) ByteArray(10) + byteArrayOf(-1, -1) + parsed else parsed
    }

    private fun public(text: String) = isPublicAddress(bytes(text))

    private fun reachable(text: String) = isReachableAddress(bytes(text))

    @Test
    fun refusesEveryNonPublicIpv4Range() {
        listOf(
            "0.0.0.0", "0.1.2.3", "10.0.0.1", "10.255.255.255", "127.0.0.1", "127.255.255.254", "169.254.169.254",
            "169.254.0.1", "172.16.0.1", "172.31.255.255", "192.168.0.1", "192.168.255.255", "100.64.0.1",
            "100.127.255.255", "192.0.0.8", "192.0.2.1", "192.88.99.1", "198.18.0.1", "198.19.255.255",
            "198.51.100.7", "203.0.113.9", "224.0.0.1", "239.255.255.255", "240.0.0.1", "255.255.255.255",
        ).forEach { assertFalse(public(it), it) }
    }

    @Test
    fun allowsPublicIpv4Addresses() {
        listOf(
            "1.1.1.1", "8.8.8.8", "93.184.216.34", "172.15.255.255", "172.32.0.1", "100.63.255.255", "100.128.0.1",
            "198.17.255.255", "198.20.0.1", "192.0.1.1", "192.169.0.1", "223.255.255.255", "169.1.1.1", "192.88.1.1",
            "198.51.1.1", "203.0.1.1", "203.1.1.1", "198.50.1.1",
        ).forEach { assertTrue(public(it), it) }
    }

    @Test
    fun refusesNonPublicIpv6AddressesAndEmbeddedIpv4Forms() {
        listOf(
            "::", "::1", "fe80::1", "febf::1", "fc00::1", "fd00::1", "fd00:ec2::254", "fec0::1", "ff02::1",
            "100::1", "64:ff9b::808:808", "64:ff9b:1::1", "5f00::1", "2001::1", "2001:1::1", "2001:db8::1",
            "2002:7f00:1::1", "2002:808:808::1", "3fff::1", "3fff:fff::1", "4000::1", "1000::1",
            // IPv4-mapped (::ffff:a.b.c.d), IPv4-translated (::ffff:0:a.b.c.d), IPv4-compatible (::a.b.c.d).
            "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:169.254.169.254", "::ffff:0:10.0.0.1", "::10.0.0.1",
            "::7f00:1",
            // Zero-prefixed forms that stand for no IPv4 address are not global unicast either.
            "::2:3:4:5", "::ff00:0:0", "::ffff:100:0:0", "::ffff:1:2:3", "::ff00:0:0:0",
        ).forEach { assertFalse(public(it), it) }
    }

    @Test
    fun allowsGlobalUnicastIpv6AndMappedPublicIpv4() {
        listOf("2606:4700:4700::1111", "2a00:1450:4007:80f::200e", "2001:4860:4860::8888", "3ffe::1", "::ffff:8.8.8.8",
            "2001:d00::1", "3fff:1000::1")
            .forEach { assertTrue(public(it), it) }
        // The IPv4-translated form of a public address is that public address.
        assertTrue(isPublicAddress(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 0, 0, 8, 8, 8, 8)))
    }

    @Test
    fun refusesWhatIsNotAnAddress() {
        assertFalse(isPublicAddress(ByteArray(0)))
        assertFalse(isPublicAddress(ByteArray(5)))
        assertFalse(isPublicAddress(ByteArray(15)))
        assertFalse(isReachableAddress(ByteArray(3)))
    }

    @Test
    fun anOperatorWhoAllowsPrivateTargetsStillCannotReachMetadataOrLinkLocalOrUnspecified() {
        listOf(
            "0.0.0.0", "169.254.169.254", "169.254.1.1", "fe80::1", "febf::1", "fd00:ec2::254", "::",
            "::ffff:169.254.169.254", "::ffff:0.0.0.0", "::169.254.169.254",
        ).forEach { assertFalse(reachable(it), it) }
    }

    @Test
    fun anOperatorWhoAllowsPrivateTargetsReachesLoopbackAndPrivateRanges() {
        listOf(
            "127.0.0.1", "10.0.0.5", "172.16.0.9", "192.168.1.1", "100.64.0.1", "8.8.8.8", "::1", "fd00::1",
            "fc00::1", "2606:4700::1", "::ffff:10.0.0.1", "fd00:ec3::1", "fd01:ec2::1", "fd00:1::1", "fec0::1",
            "169.1.1.1", "::ffff:169.1.1.1",
        ).forEach { assertTrue(reachable(it), it) }
        assertEquals(false, isReachableAddress(ByteArray(7)))
    }
}

/** The URL of a service of the operator's own (the Kubernetes API of a cluster, TC-CLN-1). */
class OutboundHostsTest {
    @Test
    fun anHttpsUrlOnAPublicPrivateOrLoopbackHostPasses() {
        listOf(
            "https://kubernetes.default.svc", "https://k8s.example.com:6443/", "https://10.0.0.1:6443",
            "https://127.0.0.1:6443", "https://localhost:6443", "https://[::1]:6443", "https://[fd00::1]:6443",
            "https://192.168.1.10", "https://8.8.8.8",
            "https://user@k8s.example.com", "https://0x7f.0.0.1", "https://2130706433", "https://127.1",
        ).forEach { assertTrue(isReachableUrl(it), it) }
    }

    @Test
    fun linkLocalUnspecifiedAndMetadataHostsAreRefusedInEverySpelling() {
        listOf(
            "https://169.254.169.254/", "https://169.254.1.1", "https://0.0.0.0", "https://0", "https://[::]",
            "https://[fe80::1]",
            "https://[fd00:ec2::254]", "https://[::ffff:169.254.169.254]", "https://[::ffff:a9fe:a9fe]",
            // Decimal, hexadecimal, octal and short forms of 169.254.169.254.
            "https://2852039166", "https://0xa9fea9fe", "https://0251.0376.0251.0376", "https://169.254.43518",
            "https://169.16689662",
            "https://metadata.google.internal", "https://METADATA.GOOG.", "https://metadata", "https://instance-data",
        ).forEach { assertFalse(isReachableUrl(it), it) }
    }

    @Test
    fun anythingButAnHttpsUrlWithAHostIsRefused() {
        listOf(
            "http://k8s.example.com", "ftp://k8s.example.com", "k8s.example.com", "", "https://", "https://:6443",
            "https://k8s.example.com:70000", "https://[zz::1]",
        ).forEach { assertFalse(isReachableUrl(it), it) }
    }

    @Test
    fun readsEveryInetAtonSpelling() {
        assertContentEquals(byteArrayOf(127, 0, 0, 1), parseLegacyIpv4("127.0.0.1"))
        assertContentEquals(byteArrayOf(127, 0, 0, 1), parseLegacyIpv4("127.1"))
        assertContentEquals(byteArrayOf(127, 0, 0, 1), parseLegacyIpv4("2130706433"))
        assertContentEquals(byteArrayOf(127, 0, 0, 1), parseLegacyIpv4("0x7f.0.0.1"))
        assertContentEquals(byteArrayOf(127, 0, 0, 1), parseLegacyIpv4("0177.0.0.1"))
        assertContentEquals(byteArrayOf(0, 0, 0, 0), parseLegacyIpv4("0"))
        assertContentEquals(byteArrayOf(-1, -1, -1, -1), parseLegacyIpv4("4294967295"))
        listOf(
            "", "abc", "k8s.example.com", "1.2.3.4.5", "256.1.1.1", "1.2.3.256", "1.16777216", "4294967296", "1..2",
            "0x", "0xg1",
            "08", "09.1.1.1", "99999999999999999999",
        ).forEach { assertNull(parseLegacyIpv4(it), it) }
    }
}
