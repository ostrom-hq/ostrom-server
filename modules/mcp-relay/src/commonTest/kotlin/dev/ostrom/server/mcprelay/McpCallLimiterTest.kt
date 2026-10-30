// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.mcprelay

import dev.ostrom.server.mcprelay.domain.McpCallLimiter
import dev.ostrom.server.mcprelay.domain.McpLimits
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** Each of the four limits refuses on its own, and the per-minute windows roll over. */
class McpCallLimiterTest {
    private val org = Relay.orgA
    private val member = Relay.memberA.memberId
    private val other = Relay.otherA.memberId
    private val server = Uuid.random()

    @Test
    fun aMemberHasItsOwnConcurrencyAndReleasingGivesItBack() = runTest {
        val limiter = McpCallLimiter(McpLimits(memberConcurrency = 1), TestClock())
        assertEquals(true, limiter.acquire(org, member, server))
        assertEquals(false, limiter.acquire(org, member, server), "One running call per member")
        assertEquals(true, limiter.acquire(org, other, server), "Another member is not held back")
        limiter.release(org, member, server)
        assertEquals(true, limiter.acquire(org, member, server))
        limiter.release(org, member, server)
        limiter.release(org, member, server)
    }

    @Test
    fun aMemberRunsUpToItsConcurrencyAtOnce() = runTest {
        val limiter = McpCallLimiter(McpLimits(memberConcurrency = 2), TestClock())
        assertEquals(true, limiter.acquire(org, member, server))
        assertEquals(true, limiter.acquire(org, member, server), "A second call while the first runs")
        assertEquals(false, limiter.acquire(org, member, server), "Not a third")
    }

    @Test
    fun aServerHasAConcurrencyAcrossItsMembers() = runTest {
        val limiter = McpCallLimiter(McpLimits(serverConcurrency = 1), TestClock())
        assertEquals(true, limiter.acquire(org, member, server))
        assertEquals(false, limiter.acquire(org, other, server))
    }

    @Test
    fun perMinuteLimitsHoldForAMemberAndAServerThenRollOver() = runTest {
        val clock = TestClock()
        val memberLimited = McpCallLimiter(McpLimits(memberPerMinute = 1), clock)
        assertEquals(true, memberLimited.acquire(org, member, server))
        memberLimited.release(org, member, server)
        assertEquals(false, memberLimited.acquire(org, member, server))
        clock.advance(1.minutes)
        assertEquals(true, memberLimited.acquire(org, member, server), "A new minute, a new window")

        val serverLimited = McpCallLimiter(McpLimits(serverPerMinute = 1), clock)
        assertEquals(true, serverLimited.acquire(org, member, server))
        assertEquals(false, serverLimited.acquire(org, other, server))
    }

    @Test
    fun oldWindowsAreDroppedWhenTooManyKeysAreKept() = runTest {
        val clock = TestClock()
        val limiter = McpCallLimiter(McpLimits(), clock)
        // Two keys per call (member and server): 5 001 calls keep more than 10 000.
        repeat(5_001) { assertEquals(true, limiter.acquire(org, Uuid.random(), Uuid.random())) }
        clock.advance(1.minutes)
        assertEquals(true, limiter.acquire(org, member, server), "Keys of past minutes are dropped, not counted")
    }
}
