// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay

import run.ostrom.server.mcprelay.domain.McpCallLimiter
import run.ostrom.server.mcprelay.domain.McpCallSlotRepository
import run.ostrom.server.mcprelay.domain.McpLimits
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Each of the four limits refuses on its own, the per-minute windows roll over and a lost slot expires. */
class McpCallLimiterTest {
    private val org = Relay.orgA
    private val member = Relay.memberA.memberId
    private val other = Relay.otherA.memberId
    private val server = Uuid.random()

    private fun limiter(limits: McpLimits, clock: TestClock = TestClock(), slots: InMemoryMcpSlots? = null) =
        McpCallLimiter(limits, slots ?: InMemoryMcpSlots(clock))

    @Test
    fun aMemberHasItsOwnConcurrencyAndReleasingGivesItBack() = runTest {
        val limiter = limiter(McpLimits(memberConcurrency = 1))
        val first = assertNotNull(limiter.acquire(org, member, server))
        assertNull(limiter.acquire(org, member, server), "One running call per member")
        assertNotNull(limiter.acquire(org, other, server), "Another member is not held back")
        limiter.release(org, first)
        assertNotNull(limiter.acquire(org, member, server))
    }

    @Test
    fun aMemberRunsUpToItsConcurrencyAtOnce() = runTest {
        val limiter = limiter(McpLimits(memberConcurrency = 2))
        assertNotNull(limiter.acquire(org, member, server))
        assertNotNull(limiter.acquire(org, member, server), "A second call while the first runs")
        assertNull(limiter.acquire(org, member, server), "Not a third")
    }

    @Test
    fun aServerHasAConcurrencyAcrossItsMembers() = runTest {
        val limiter = limiter(McpLimits(serverConcurrency = 1))
        assertNotNull(limiter.acquire(org, member, server))
        assertNull(limiter.acquire(org, other, server))
    }

    @Test
    fun perMinuteLimitsHoldForAMemberAndAServerThenRollOver() = runTest {
        val clock = TestClock()
        val memberLimited = limiter(McpLimits(memberPerMinute = 1), clock)
        memberLimited.release(org, assertNotNull(memberLimited.acquire(org, member, server)))
        assertNull(memberLimited.acquire(org, member, server))
        clock.advance(1.minutes)
        assertNotNull(memberLimited.acquire(org, member, server), "A new minute, a new window")

        val serverLimited = limiter(McpLimits(serverPerMinute = 1), clock)
        assertNotNull(serverLimited.acquire(org, member, server))
        assertNull(serverLimited.acquire(org, other, server))
    }

    @Test
    fun aSlotNeverGivenBackStopsCountingAfterItsLease() = runTest {
        val clock = TestClock()
        val limiter = limiter(McpLimits(memberConcurrency = 1), clock)
        assertNotNull(limiter.acquire(org, member, server))
        assertNull(limiter.acquire(org, member, server))
        clock.advance(McpCallSlotRepository.SLOT_LEASE)
        assertNotNull(limiter.acquire(org, member, server), "A replica that died does not hold the slot forever")
    }

    @Test
    fun aLongOperationRenewsItsSlotWhileItRunsAndOnlyThen() = runTest {
        val clock = TestClock()
        val limiter = limiter(McpLimits(memberConcurrency = 1), clock)
        val slot = assertNotNull(limiter.acquire(org, member, server))
        val refused = limiter.held(org, slot) {
            repeat(4) {
                delay(McpCallSlotRepository.SLOT_RENEWAL)
                clock.advance(McpCallSlotRepository.SLOT_RENEWAL)
            }
            limiter.acquire(org, member, server)
        }
        assertNull(refused, "Twenty minutes in, past the slot's lease, the operation still counts")
        clock.advance(McpCallSlotRepository.SLOT_LEASE)
        assertNotNull(limiter.acquire(org, member, server), "Once it ends, nothing renews it")
    }

    @Test
    fun aSlotTheDatabaseCannotRenewOrTakeBackFailsNoOperation() = runTest {
        val clock = TestClock()
        val slots = InMemoryMcpSlots(clock)
        val limiter = limiter(McpLimits(), clock, slots)
        val slot = assertNotNull(limiter.acquire(org, member, server))
        slots.failure = IllegalStateException("The database is unreachable")
        val answer = limiter.held(org, slot) {
            delay(McpCallSlotRepository.SLOT_RENEWAL + 1.seconds)
            "answered"
        }
        assertEquals("answered", answer, "A renewal that fails is left to the next one")
        limiter.release(org, slot)
        slots.failure = CancellationException("cancelled")
        assertFailsWith<CancellationException> { limiter.release(org, slot) }
    }
}
