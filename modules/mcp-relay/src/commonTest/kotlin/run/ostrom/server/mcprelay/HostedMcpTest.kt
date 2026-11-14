// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay

import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.mcprelay.domain.HostStatus
import run.ostrom.server.mcprelay.domain.HostedSweep
import run.ostrom.server.mcprelay.domain.HostedWarmup
import run.ostrom.server.mcprelay.domain.ListMemberServersUseCaseImpl
import run.ostrom.server.mcprelay.domain.McpHostControl
import run.ostrom.server.mcprelay.domain.McpRelayRepository
import run.ostrom.server.mcprelay.domain.McpServer
import run.ostrom.server.mcprelay.domain.McpSharing
import run.ostrom.server.mcprelay.domain.McpTransport
import run.ostrom.server.mcprelay.domain.RetainHostedMcpProcessesUseCaseImpl
import run.ostrom.server.mcprelay.domain.StdioLauncher
import run.ostrom.server.mcprelay.domain.StdioProcessKey
import run.ostrom.server.mcprelay.domain.StdioSpec
import run.ostrom.server.mcprelay.domain.UpstreamTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class HostedMcpTest {
    private val secretId = Uuid.random()

    private fun sweeper(repository: McpRelayRepository, members: MemberDirectory?, host: McpHostControl) =
        RetainHostedMcpProcessesUseCaseImpl(repository, members, host, TestClock())

    private fun stdio(name: String, sharing: McpSharing) =
        Relay.server(name, transport = McpTransport.STDIO, sharing = sharing).copy(
            stdio = StdioSpec(StdioLauncher.NPM, "mcp-x@1.0.0", emptyList(), emptyMap(), mapOf("TOKEN" to secretId)),
        )

    private fun McpServer.key(member: Uuid, at: kotlin.time.Instant = Relay.epoch) =
        StdioProcessKey.of(this, member, at)

    @Test
    fun theProcessKeyHoldsNoSecretAndAMemberOnlyForAPerMemberServer() {
        val shared = stdio("shared", McpSharing.SHARED)
        assertEquals("${shared.orgId}:${shared.id}:1", shared.key(Relay.memberA.memberId))
        assertEquals(shared.key(Relay.memberA.memberId), shared.key(Relay.otherA.memberId))
        val personal = stdio("personal", McpSharing.PER_MEMBER)
        assertEquals(
            "${personal.orgId}:${personal.id}:1:${Relay.memberA.memberId}:${Relay.epoch}",
            personal.key(Relay.memberA.memberId),
        )
    }

    @Test
    fun theSweepSendsEveryAssignedKeyTheOrgWideOnesForEachOtherMember() = runTest {
        val repository = InMemoryRelayRepository()
        val shared = stdio("shared", McpSharing.SHARED)
        val personal = stdio("personal", McpSharing.PER_MEMBER)
        val ownOnly = stdio("own", McpSharing.PER_MEMBER)
        val http = Relay.server("http")
        repository.servers += listOf(shared, personal, ownOnly, http)
        repository.assign(shared, null)
        repository.assign(shared, Relay.memberA.memberId)
        repository.assign(personal, null)
        val later = Relay.epoch + 1.days
        repository.assignments +=
            repository.assignments.last().copy(memberId = Relay.memberA.memberId, assignedAt = later)
        repository.assign(ownOnly, Relay.otherA.memberId)
        // A member who left the org keeps no process, though the row is still there.
        repository.assign(ownOnly, Uuid.random())
        repository.assign(http, null)
        val host = FakeHostControl()
        val members = FakeMembers(listOf(Relay.memberA.memberId.toString(), Relay.otherA.memberId.toString(), "x"))
        val sweep = sweeper(repository, members, host)()
        val expected = setOf(
            shared.key(Relay.memberA.memberId),
            personal.key(Relay.memberA.memberId, later),
            personal.key(Relay.otherA.memberId),
            ownOnly.key(Relay.otherA.memberId),
        )
        assertEquals(expected, host.retained.single())
        assertEquals(HostedSweep.Done(4, 3), sweep)
    }

    @Test
    fun anIncompleteListOrADownHostStopsNothing() = runTest {
        val repository = InMemoryRelayRepository()
        val personal = stdio("personal", McpSharing.PER_MEMBER)
        repository.servers += personal
        repository.assign(personal, null)
        val host = FakeHostControl()
        // Without a directory the org-wide assignment cannot be expanded: nothing is sent.
        assertEquals(HostedSweep.Incomplete, sweeper(repository, null, host)())
        assertEquals(HostedSweep.Incomplete, sweeper(repository, FakeMembers(null), host)())
        val crowd = FakeMembers(List(RetainHostedMcpProcessesUseCaseImpl.MAX_KEYS + 1) { Uuid.random().toString() })
        assertEquals(HostedSweep.Incomplete, sweeper(repository, crowd, host)())
        // Past the bound, the keys of the next servers are not built: one org-wide server already fills it.
        val half = FakeMembers(List(RetainHostedMcpProcessesUseCaseImpl.MAX_KEYS / 2 + 1) { Uuid.random().toString() })
        val second = stdio("second", McpSharing.PER_MEMBER)
        repository.servers += second
        repository.assign(second, null)
        repository.servers += stdio("third", McpSharing.PER_MEMBER).also { repository.assign(it, null) }
        assertEquals(HostedSweep.Incomplete, sweeper(repository, half, host)())
        val full = object : McpRelayRepository by repository {
            override suspend fun hostedAssignments(limit: Int) = null
        }
        val unlisted = sweeper(full, FakeMembers(emptyList()), host)
        assertEquals(HostedSweep.Incomplete, unlisted())
        assertTrue(host.retained.isEmpty())
        host.fail = true
        assertEquals(
            HostedSweep.HostUnavailable,
            sweeper(repository, FakeMembers(emptyList()), host)(),
        )
    }

    @Test
    fun withoutTheMemberListEveryOwnRowIsKept() = runTest {
        val repository = InMemoryRelayRepository()
        val personal = stdio("personal", McpSharing.PER_MEMBER)
        val shared = stdio("shared", McpSharing.SHARED)
        repository.servers += listOf(personal, shared)
        val gone = Uuid.random()
        repository.assign(personal, gone)
        repository.assign(shared, gone)
        val host = FakeHostControl()
        assertEquals(HostedSweep.Done(2, 3), sweeper(repository, null, host)())
        assertEquals(setOf(personal.key(gone), shared.key(gone)), host.retained.single())
    }

    @Test
    fun listingItsServersWarmsTheMembersStdioProcesses() = runTest {
        val repository = InMemoryRelayRepository()
        val personal = stdio("personal", McpSharing.PER_MEMBER)
        val broken = stdio("broken", McpSharing.SHARED).copy(
            stdio = StdioSpec(StdioLauncher.NPM, "mcp-y@1.0.0", emptyList(), emptyMap(), mapOf("T" to Uuid.random())),
        )
        // Two names that collide as agent names: both are left out of the listing, and neither is warmed.
        val dotted = stdio("a.b", McpSharing.SHARED)
        val underscored = stdio("a_b", McpSharing.SHARED)
        repository.servers += listOf(broken, personal, Relay.server("http"), dotted, underscored)
        repository.servers.forEach { repository.assign(it, null) }
        val host = FakeHostControl()
        val reveal = FakeReveal(mutableMapOf(secretId to "s3cret-value"))
        val clock = TestClock()
        val listing = ListMemberServersUseCaseImpl(repository, HostedWarmup(reveal, host, backgroundScope, clock))
        assertEquals(3, listing(Relay.memberA).size)
        runCurrent()
        // The server whose secret is gone is skipped: its first operation reports it.
        assertEquals(listOf(personal.key(Relay.memberA.memberId)), host.warmed.map { it.processKey })
        assertEquals("s3cret-value", host.warmed.single().secretEnv.getValue("TOKEN").reveal())
        // Within the minute, a key warmed is not warmed again; the failed one is tried again.
        reveal.values[broken.stdio!!.secretEnv.getValue("T")] = "an-other-secret"
        listing(Relay.memberA)
        runCurrent()
        assertEquals(listOf("personal", "broken"), host.warmed.map { it.server.name })
        clock.advance(HostedWarmup.WARM_INTERVAL)
        listing(Relay.memberA)
        runCurrent()
        assertEquals(4, host.warmed.size, "A minute later, both are warmed again")
        ListMemberServersUseCaseImpl(repository)(Relay.memberA)
        repository.assignments.removeAll { it.serverId != repository.servers[2].id }
        listing(Relay.memberA)
        runCurrent()
        assertEquals(4, host.warmed.size, "No stdio server, no warm-up")
    }

    @Test
    fun aHostThatFailsOrHangsLeavesTheKeyFreeAndManyKeysAreForgotten() = runTest {
        val repository = InMemoryRelayRepository()
        val personal = stdio("personal", McpSharing.PER_MEMBER)
        repository.servers += personal
        repository.assign(personal, null)
        var calls = 0
        var behaviour: suspend () -> Unit = { error("host down") }
        val host = object : McpHostControl {
            override suspend fun warm(target: UpstreamTarget) {
                calls++
                behaviour()
            }

            override suspend fun retain(keys: Collection<String>): Int = 0

            override suspend fun status(): HostStatus = HostStatus("1") { null }

            override suspend fun stop(key: String): Boolean = false
        }
        val clock = TestClock()
        val warmup = HostedWarmup(FakeReveal(mutableMapOf(secretId to "s3cret-value")), host, backgroundScope, clock)
        val effective = repository.effectiveServers(Relay.orgA, Relay.memberA.memberId, 10)
        warmup.warm(Relay.memberA, effective)
        runCurrent()
        behaviour = { awaitCancellation() }
        warmup.warm(Relay.memberA, effective)
        advanceTimeBy(HostedWarmup.WARM_TIMEOUT + 1.seconds)
        behaviour = {}
        warmup.warm(Relay.memberA, effective)
        runCurrent()
        warmup.warm(Relay.memberA, effective)
        runCurrent()
        assertEquals(3, calls, "A failure and a timeout free the key; a success holds it for the minute")
        // Past the bound, the keys warmed more than a minute ago are forgotten.
        val many = List(HostedWarmup.MAX_WARMED_KEYS + 1) { Uuid.random() }
        for (member in many) warmup.warm(Relay.memberA.copy(memberId = member), effective)
        runCurrent()
        clock.advance(HostedWarmup.WARM_INTERVAL)
        warmup.warm(Relay.memberA.copy(memberId = many.first()), effective)
        runCurrent()
        warmup.warm(Relay.memberA.copy(memberId = many.last()), effective)
        runCurrent()
        assertEquals(3 + many.size + 2, calls)
    }

    @Test
    fun aCancelledWarmUpLeavesTheKeyFree() = runTest {
        val repository = InMemoryRelayRepository()
        val personal = stdio("personal", McpSharing.PER_MEMBER)
        repository.servers += personal
        repository.assign(personal, null)
        var calls = 0
        val host = object : McpHostControl {
            override suspend fun warm(target: UpstreamTarget) {
                if (++calls == 1) throw CancellationException("the server stops")
            }

            override suspend fun retain(keys: Collection<String>): Int = 0

            override suspend fun status(): HostStatus = HostStatus("1") { null }

            override suspend fun stop(key: String): Boolean = false
        }
        val effective = repository.effectiveServers(Relay.orgA, Relay.memberA.memberId, 10)
        val reveal = FakeReveal(mutableMapOf(secretId to "s3cret-value"))
        val warmup = HostedWarmup(reveal, host, backgroundScope, TestClock())
        warmup.warm(Relay.memberA, effective)
        runCurrent()
        warmup.warm(Relay.memberA, effective)
        runCurrent()
        assertEquals(2, calls)
    }
}
