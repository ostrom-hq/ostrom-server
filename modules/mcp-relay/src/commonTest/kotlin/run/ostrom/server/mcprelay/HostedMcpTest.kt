// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.mcprelay

import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.mcprelay.domain.HostedSweep
import run.ostrom.server.mcprelay.domain.HostedWarmup
import run.ostrom.server.mcprelay.domain.ListMemberServersUseCaseImpl
import run.ostrom.server.mcprelay.domain.McpHostControl
import run.ostrom.server.mcprelay.domain.McpServer
import run.ostrom.server.mcprelay.domain.McpSharing
import run.ostrom.server.mcprelay.domain.McpTransport
import run.ostrom.server.mcprelay.domain.RetainHostedMcpProcessesUseCaseImpl
import run.ostrom.server.mcprelay.domain.StdioLauncher
import run.ostrom.server.mcprelay.domain.StdioProcessKey
import run.ostrom.server.mcprelay.domain.StdioSpec
import run.ostrom.server.mcprelay.domain.UpstreamTarget
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

class HostedMcpTest {
    private class FakeHost(var fail: Boolean = false) : McpHostControl {
        val retained = mutableListOf<Set<String>>()
        val warmed = mutableListOf<UpstreamTarget>()

        override suspend fun warm(target: UpstreamTarget) {
            warmed += target
        }

        override suspend fun retain(keys: Collection<String>): Int {
            if (fail) error("host down")
            retained += keys.toSet()
            return 3
        }
    }

    private class FakeMembers(var ids: List<String>?) : MemberDirectory {
        override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean = true

        override suspend fun memberIds(orgId: Uuid, limit: Int): List<String>? = ids
    }

    private val secretId = Uuid.random()

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
        repository.assign(http, null)
        val host = FakeHost()
        val members = FakeMembers(listOf(Relay.memberA.memberId.toString(), Relay.otherA.memberId.toString(), "x"))
        val sweep = RetainHostedMcpProcessesUseCaseImpl(repository, members, host)()
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
        val host = FakeHost()
        // Without a directory the org-wide assignment cannot be expanded: nothing is sent.
        assertEquals(HostedSweep.Incomplete, RetainHostedMcpProcessesUseCaseImpl(repository, null, host)())
        assertEquals(HostedSweep.Incomplete, RetainHostedMcpProcessesUseCaseImpl(repository, FakeMembers(null), host)())
        val crowd = FakeMembers(List(RetainHostedMcpProcessesUseCaseImpl.MAX_KEYS + 1) { Uuid.random().toString() })
        assertEquals(HostedSweep.Incomplete, RetainHostedMcpProcessesUseCaseImpl(repository, crowd, host)())
        val full = object : run.ostrom.server.mcprelay.domain.McpRelayRepository by repository {
            override suspend fun hostedAssignments(limit: Int) = null
        }
        val unlisted = RetainHostedMcpProcessesUseCaseImpl(full, FakeMembers(emptyList()), host)
        assertEquals(HostedSweep.Incomplete, unlisted())
        assertTrue(host.retained.isEmpty())
        host.fail = true
        assertEquals(
            HostedSweep.HostUnavailable,
            RetainHostedMcpProcessesUseCaseImpl(repository, FakeMembers(emptyList()), host)(),
        )
    }

    @Test
    fun listingItsServersWarmsTheMembersStdioProcesses() = runTest {
        val repository = InMemoryRelayRepository()
        val personal = stdio("personal", McpSharing.PER_MEMBER)
        val broken = stdio("broken", McpSharing.SHARED).copy(
            stdio = StdioSpec(StdioLauncher.NPM, "mcp-y@1.0.0", emptyList(), emptyMap(), mapOf("T" to Uuid.random())),
        )
        repository.servers += listOf(broken, personal, Relay.server("http"))
        repository.servers.forEach { repository.assign(it, null) }
        val host = FakeHost()
        val reveal = FakeReveal(mutableMapOf(secretId to "s3cret-value"))
        val listing = ListMemberServersUseCaseImpl(repository, HostedWarmup(reveal, host, backgroundScope))
        assertEquals(3, listing(Relay.memberA).size)
        runCurrent()
        // The server whose secret is gone is skipped: its first operation reports it.
        assertEquals(listOf(personal.key(Relay.memberA.memberId)), host.warmed.map { it.processKey })
        assertEquals("s3cret-value", host.warmed.single().secretEnv.getValue("TOKEN").reveal())
        ListMemberServersUseCaseImpl(repository)(Relay.memberA)
        repository.assignments.clear()
        repository.assign(repository.servers.last(), null)
        listing(Relay.memberA)
        runCurrent()
        assertEquals(1, host.warmed.size, "No stdio server, no warm-up")
    }
}
