// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import run.ostrom.server.kernel.network.PinnedDns
import run.ostrom.server.kernel.network.PinnedDnsRefusal
import run.ostrom.server.kernel.network.PinnedDnsTimeout
import run.ostrom.server.kernel.network.isPublicAddress
import run.ostrom.server.kernel.network.isReachableAddress
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** One lookup, checked and dialled (TC-CODE-SEC-1, TC-GWY-17), with real threads: the lookups are blocking. */
class PinnedDnsTest {
    private fun realTime(block: suspend () -> Unit) = runTest { withContext(Dispatchers.Default) { block() } }

    private fun address(text: String): InetAddress = InetAddress.getByName(text)

    /** A lookup that answers [answers] in turn (the last one again after that) and counts its calls. */
    private class Scripted(private vararg val answers: List<InetAddress>) {
        var calls = 0

        fun lookup(host: String): List<InetAddress> =
            answers[minOf(calls++, answers.size - 1)].also { assertEquals("a.test", host) }
    }

    private fun scripted(vararg answers: String) = Scripted(answers.map(::address))

    @Test
    fun aNameIsResolvedOnceAndEveryAnswerIsChecked() = realTime {
        val dns = scripted("93.184.216.34", "2606:4700::1111")
        val resolver = PinnedDns.resolver(::isPublicAddress, dns::lookup)
        assertEquals(listOf("93.184.216.34", "2606:4700:0:0:0:0:0:1111"), resolver("a.test"))
        assertEquals(1, dns.calls)
    }

    @Test
    fun aNameWithOneRefusedAnswerIsRefusedWhole() = realTime {
        val resolver = PinnedDns.resolver(::isPublicAddress, scripted("93.184.216.34", "10.0.0.1")::lookup)
        assertFailsWith<PinnedDnsRefusal> { resolver("a.test") }
        val ipv6 = PinnedDns.resolver(::isPublicAddress, scripted("2606:4700::1111", "fd00::1")::lookup)
        assertFailsWith<PinnedDnsRefusal> { ipv6("a.test") }
    }

    @Test
    fun aNameThatAnswersNothingIsRefused() = realTime {
        val resolver = PinnedDns.resolver(::isPublicAddress, Scripted(emptyList())::lookup)
        assertFailsWith<PinnedDnsRefusal> { resolver("a.test") }
    }

    @Test
    fun theRefusalIsAnUnknownHostThatDoesNotEchoTheHost() = realTime {
        val refusal = assertFailsWith<PinnedDnsRefusal> {
            PinnedDns.resolver(::isPublicAddress, scripted("127.0.0.1")::lookup)("a.test")
        }
        assertIs<UnknownHostException>(refusal)
        assertEquals("The destination address is not allowed.", refusal.message)
        assertEquals("a.test", refusal.host)
    }

    @Test
    fun aNameThatChangesItsAnswerCannotPassWithOneAndBeDialledWithTheOther() = realTime {
        // DNS rebinding: a public answer to the first lookup, the metadata address to every next one.
        val dns = Scripted(listOf(address("93.184.216.34")), listOf(address("169.254.169.254")))
        val resolver = PinnedDns.resolver(::isPublicAddress, dns::lookup)
        // The answer that was checked is the answer that is returned: there is no second lookup to dial.
        assertEquals(listOf("93.184.216.34"), resolver("a.test"))
        assertEquals(1, dns.calls)
        // The next connection resolves again, and is checked again: it is refused.
        assertFailsWith<PinnedDnsRefusal> { resolver("a.test") }
        assertEquals(2, dns.calls)
    }

    @Test
    fun everyLoopbackPrivateAndMetadataAnswerIsRefusedForAPublicName() = realTime {
        listOf(
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.0.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1",
            "fe80::1", "fd00:ec2::254", "::ffff:10.0.0.1", "64:ff9b::a00:1", "2002:a00:1::1",
        ).forEach { text ->
            assertFailsWith<PinnedDnsRefusal>(text) {
                PinnedDns.resolver(::isPublicAddress, scripted(text)::lookup)("a.test")
            }
        }
    }

    @Test
    fun aBracketedLiteralIsLookedUpWithoutItsBrackets() = realTime {
        val seen = mutableListOf<String>()
        val resolver = PinnedDns.resolver(::isPublicAddress, { host -> seen += host; listOf(address(host)) })
        assertEquals(listOf("2606:4700:0:0:0:0:0:1111"), resolver("[2606:4700::1111]"))
        assertFailsWith<PinnedDnsRefusal> { resolver("[::1]") }
        assertEquals(listOf("2606:4700::1111", "::1"), seen)
    }

    @Test
    fun everyNumericSpellingIsReadByTheResolverAndItsAnswerIsChecked() = realTime {
        // The system resolver reads 2130706433 and 127.1 as 127.0.0.1 and refuses 0x7f.1; the JDK reads 0177.0.0.1 as
        // the decimal 177.0.0.1, a public address: what is dialed is always what was checked.
        val resolver = PinnedDns.resolver(::isPublicAddress)
        listOf("2130706433", "127.1").forEach { assertFailsWith<PinnedDnsRefusal>(it) { resolver(it) } }
        assertFailsWith<java.net.UnknownHostException> { resolver("0x7f.1") }
        assertEquals(listOf("177.0.0.1"), resolver("0177.0.0.1"))
    }

    @Test
    fun aLookupThatFailsFailsTheCall() = realTime {
        val resolver = PinnedDns.resolver(::isPublicAddress, { error("no such host") })
        assertEquals("no such host", assertFailsWith<IllegalStateException> { resolver("a.test") }.message)
    }

    @Test
    fun aLookupThatNeverAnswersTimesOutAsAnIoFailure() = realTime {
        val resolver = PinnedDns.resolver(::isPublicAddress, {
            val start = TimeSource.Monotonic.markNow()
            while (start.elapsedNow() < 600.milliseconds) continue
            listOf(address("93.184.216.34"))
        }, deadlineMillis = 50)
        val timeout = assertFailsWith<PinnedDnsTimeout> { resolver("a.test") }
        assertIs<UnknownHostException>(timeout)
        assertEquals("The destination did not resolve in time.", timeout.message)
    }

    @Test
    fun theSystemResolverAnswersLocalhostWithLoopbackAddressesOnly() = realTime {
        val answers = PinnedDns.resolver(::isReachableAddress)("localhost")
        assertTrue(answers.isNotEmpty())
        assertFailsWith<PinnedDnsRefusal> { PinnedDns.resolver(::isPublicAddress)("localhost") }
    }

    @Test
    fun theSystemResolverFailsOnAnUnknownName() = realTime {
        assertFailsWith<UnknownHostException> { PinnedDns.resolver(::isPublicAddress)("no-such-host.invalid") }
    }
}
