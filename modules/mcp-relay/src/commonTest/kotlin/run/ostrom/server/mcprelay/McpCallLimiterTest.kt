// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.mcprelay

import run.ostrom.server.mcprelay.domain.McpCallLimiter
import run.ostrom.server.mcprelay.domain.McpCallSlotRepository
import run.ostrom.server.mcprelay.domain.McpLimits
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** Each of the four limits refuses on its own, the per-minute windows roll over and a lost slot expires. */
class McpCallLimiterTest {
    private val org = Relay.orgA
    private val member = Relay.memberA.memberId
    private val other = Relay.otherA.memberId
    private val server = Uuid.random()

    private fun limiter(limits: McpLimits, clock: TestClock = TestClock()) =
        McpCallLimiter(limits, InMemoryMcpSlots(clock))

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
}
