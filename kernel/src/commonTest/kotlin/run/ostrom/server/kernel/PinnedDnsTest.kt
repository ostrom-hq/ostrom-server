// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import run.ostrom.server.kernel.network.PinnedDnsRefusal
import run.ostrom.server.kernel.network.PinnedDnsTimeout
import run.ostrom.server.kernel.network.isPublicAddress
import run.ostrom.server.kernel.network.isReachableAddress
import run.ostrom.server.kernel.network.parseIpAddress
import run.ostrom.server.kernel.network.pinnedResolver
import run.ostrom.server.kernel.network.systemLookup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** One lookup, checked and dialled (TC-CODE-SEC-1, TC-GWY-17), with real threads: the lookups are blocking. */
class PinnedDnsTest {
    private fun realTime(block: suspend () -> Unit) = runTest { withContext(Dispatchers.Default) { block() } }

    /** A lookup that answers [answers] in turn (the last one again after that) and counts its calls. */
    private class Scripted(private vararg val answers: List<String>) {
        var calls = 0

        fun lookup(host: String): List<String> =
            answers[minOf(calls++, answers.size - 1)].also { assertEquals("a.test", host) }
    }

    @Test
    fun aNameIsResolvedOnceAndEveryAnswerIsChecked() = realTime {
        val dns = Scripted(listOf("93.184.216.34", "2606:4700::1111"))
        val resolver = pinnedResolver(::isPublicAddress, dns::lookup)
        assertEquals(listOf("93.184.216.34", "2606:4700::1111"), resolver("a.test"))
        assertEquals(1, dns.calls)
    }

    @Test
    fun aNameWithOneRefusedAnswerIsRefusedWhole() = realTime {
        val resolver = pinnedResolver(::isPublicAddress, Scripted(listOf("93.184.216.34", "10.0.0.1"))::lookup)
        assertFailsWith<PinnedDnsRefusal> { resolver("a.test") }
        val ipv6 = pinnedResolver(::isPublicAddress, Scripted(listOf("2606:4700::1111", "fd00::1"))::lookup)
        assertFailsWith<PinnedDnsRefusal> { ipv6("a.test") }
    }

    @Test
    fun aNameThatAnswersNothingOrAGarbageAddressIsRefused() = realTime {
        assertFailsWith<PinnedDnsRefusal> { pinnedResolver(::isPublicAddress, Scripted(emptyList())::lookup)("a.test") }
        assertFailsWith<PinnedDnsRefusal> {
            pinnedResolver(::isPublicAddress, Scripted(listOf("not an address"))::lookup)("a.test")
        }
    }

    @Test
    fun theRefusalDoesNotNameTheHost() = realTime {
        val refusal = assertFailsWith<PinnedDnsRefusal> {
            pinnedResolver(::isPublicAddress, Scripted(listOf("127.0.0.1"))::lookup)("a.test")
        }
        assertEquals("The destination address is not allowed.", refusal.message)
    }

    @Test
    fun aNameThatChangesItsAnswerCannotPassWithOneAndBeDialledWithTheOther() = realTime {
        // DNS rebinding: a public answer to the first lookup, the metadata address to every next one.
        val dns = Scripted(listOf("93.184.216.34"), listOf("169.254.169.254"))
        val resolver = pinnedResolver(::isPublicAddress, dns::lookup)
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
        ).forEach { address ->
            assertFailsWith<PinnedDnsRefusal>(address) {
                pinnedResolver(::isPublicAddress, Scripted(listOf(address))::lookup)("a.test")
            }
        }
    }

    @Test
    fun aLiteralAddressIsCheckedWithoutALookup() = realTime {
        val dns = Scripted(listOf("127.0.0.1"))
        val resolver = pinnedResolver(::isPublicAddress, dns::lookup)
        assertEquals(listOf("93.184.216.34"), resolver("93.184.216.34"))
        assertEquals(listOf("2606:4700::1111"), resolver("[2606:4700::1111]"))
        assertFailsWith<PinnedDnsRefusal> { resolver("127.0.0.1") }
        assertFailsWith<PinnedDnsRefusal> { resolver("[::1]") }
        assertFailsWith<PinnedDnsRefusal> { resolver("[::ffff:7f00:1]") }
        assertEquals(0, dns.calls)
    }

    @Test
    fun everyOtherNumericSpellingIsReadByTheResolverAndItsAnswerIsChecked() = realTime {
        // 2130706433, 0x7f.1 and 0177.0.0.1 are no literals for the parser: the system resolver reads them as
        // 127.0.0.1.
        val resolver = pinnedResolver(::isPublicAddress, { listOf("127.0.0.1") })
        listOf("2130706433", "0x7f.1", "0177.0.0.1", "127.1").forEach {
            assertFailsWith<PinnedDnsRefusal>(it) { resolver(it) }
        }
    }

    @Test
    fun aLookupThatFailsFailsTheCall() = realTime {
        val resolver = pinnedResolver(::isPublicAddress, { error("no such host") })
        assertEquals("no such host", assertFailsWith<IllegalStateException> { resolver("a.test") }.message)
    }

    @Test
    fun aLookupThatNeverAnswersTimesOutAsAnIoFailure() = realTime {
        val resolver = pinnedResolver(::isPublicAddress, {
            val start = TimeSource.Monotonic.markNow()
            while (start.elapsedNow() < 600.milliseconds) continue
            listOf("93.184.216.34")
        }, deadline = 50.milliseconds)
        val timeout = assertFailsWith<PinnedDnsTimeout> { resolver("a.test") }
        assertEquals("The destination did not resolve in time.", timeout.message)
    }

    @Test
    fun theSystemResolverAnswersLocalhostWithLoopbackAddresses() {
        val answers = systemLookup("localhost")
        assertTrue(answers.isNotEmpty())
        // Loopback addresses, spelled as the platform likes (`::1` or `0:0:0:0:0:0:0:1`).
        assertTrue(answers.all { isReachableAddress(assertNotNull(parseIpAddress(it), it)) }, answers.toString())
        assertTrue(answers.none { isPublicAddress(assertNotNull(parseIpAddress(it), it)) }, answers.toString())
    }

    @Test
    fun theDefaultLookupIsTheSystemResolver() = realTime {
        val answers = pinnedResolver(::isReachableAddress)("localhost")
        assertTrue(answers.isNotEmpty())
        assertFailsWith<PinnedDnsRefusal> { pinnedResolver(::isPublicAddress)("localhost") }
    }

    @Test
    fun theSystemResolverFailsOnAnUnknownName() {
        assertFailsWith<Exception> { systemLookup("no-such-host.invalid") }
    }
}
