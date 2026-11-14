// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay

import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.mcprelay.domain.HostedProcessAdministration
import run.ostrom.server.mcprelay.domain.HostedProcessEvent
import run.ostrom.server.mcprelay.domain.HostedProcessRecord
import run.ostrom.server.mcprelay.domain.HostedProcessRef
import run.ostrom.server.mcprelay.domain.HostedProcessStateRules
import run.ostrom.server.mcprelay.domain.HostedProcessStates
import run.ostrom.server.mcprelay.domain.HostedProcessStatus
import run.ostrom.server.mcprelay.domain.HostedSweep
import run.ostrom.server.mcprelay.domain.ListMemberServersUseCaseImpl
import run.ostrom.server.mcprelay.domain.McpHostDisabledException
import run.ostrom.server.mcprelay.domain.McpHostUnavailableException
import run.ostrom.server.mcprelay.domain.McpHostedTooManyException
import run.ostrom.server.mcprelay.domain.McpRelayRepository
import run.ostrom.server.mcprelay.domain.McpServer
import run.ostrom.server.mcprelay.domain.McpServerNotFoundException
import run.ostrom.server.mcprelay.domain.McpSharing
import run.ostrom.server.mcprelay.domain.McpTransport
import run.ostrom.server.mcprelay.domain.RetainHostedMcpProcessesUseCaseImpl
import run.ostrom.server.mcprelay.domain.StdioLauncher
import run.ostrom.server.mcprelay.domain.StdioProcessKey
import run.ostrom.server.mcprelay.domain.StdioSpec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

class HostedStatesTest {
    private val notice = RecordingHostNotice()
    private val ref = HostedProcessRef(Relay.orgA, Uuid.random(), null, "org:server:1")

    private fun stored(state: String, failure: String?, key: String = ref.key) =
        HostedProcessRecord(ref.orgId, ref.serverId, null, key, state, failure, Relay.epoch)

    private fun status(state: String, failure: String? = null) =
        HostedProcessStatus(state, "npm", "mcp-x@1.0.0", lastFailure = failure)

    private fun stdio(name: String, sharing: McpSharing) =
        Relay.server(name, transport = McpTransport.STDIO, sharing = sharing).copy(
            stdio = StdioSpec(StdioLauncher.NPM, "mcp-$name@1.0.0", emptyList(), emptyMap(), emptyMap()),
        )

    private fun McpServer.key(member: Uuid? = null) =
        if (member == null) {
            StdioProcessKey.shared(orgId, id, version)
        } else {
            StdioProcessKey.of(this, member, Relay.epoch)
        }

    @Test
    fun theStatesAreTheHostsAndAnyOtherIsUnknown() {
        assertEquals("ready", HostedProcessStates.known("ready"))
        assertEquals("unknown", HostedProcessStates.known("hibernating"))
        assertTrue(HostedProcessStates.failing("failed") && HostedProcessStates.failing("backoff"))
        assertFalse(HostedProcessStates.failing("starting"))
        assertEquals("idle", stored("ready", null, key = "old").stateFor(ref.key))
        assertNull(stored("failed", "crashed", key = "old").failureFor(ref.key))
    }

    @Test
    fun aFailureIsKeptUntilAStartSucceedsAndAnIdleProcessThatNeverFailedHasNoRow() {
        val rules = HostedProcessStateRules
        val now = Relay.epoch + 1.minutes
        assertNull(rules.change(ref, null, null, now), "Idle and never failed: no row")
        val failed = rules.change(ref, status("failed", "start_failed"), null, now)!!.next
        assertEquals("failed" to "start_failed", failed.state to failed.failure)
        // A failure code that is not one of the host's labels gives way to the previous failure, then to the state.
        val odd = rules.change(ref, status("backoff", "Not A Code!"), stored("failed", "crashed"), now)!!.next
        assertEquals("crashed", odd.failure)
        assertEquals("backoff", rules.change(ref, status("backoff"), null, now)!!.next.failure)
        // Starting again keeps the streak's failure; ready ends it.
        assertNull(rules.change(ref, status("starting"), stored("starting", "crashed"), now))
        assertEquals("crashed", rules.change(ref, status("starting"), stored("failed", "crashed"), now)!!.next.failure)
        assertNull(rules.change(ref, status("ready"), stored("failed", "crashed"), now)!!.next.failure)
        // A row recorded under another key is replaced, even with the same state.
        val rekeyed = rules.change(ref, status("ready"), stored("ready", null, key = "old"), now)!!.next
        assertEquals(ref.key, rekeyed.processKey)
        assertEquals("unknown", rules.change(ref, status("asleep"), null, now)!!.next.state)
    }

    @Test
    fun aFailureStreakIsOneFailedEventAndItsEndOneRecoveredEventNamingWhatItRecoveredFrom() {
        val rules = HostedProcessStateRules
        val now = Relay.epoch + 1.minutes
        fun eventOf(next: HostedProcessStatus?, before: HostedProcessRecord?) = rules.change(ref, next, before, now)
        val failed = eventOf(status("failed", "start_failed"), null)!!
        assertEquals(HostedProcessEvent.FAILED to null, failed.event to failed.recoveredFrom)
        // Retries, `failed` becoming `backoff` and the streak's later changes tell nothing more.
        assertNull(eventOf(status("backoff"), stored("failed", "start_failed"))!!.event)
        assertNull(eventOf(status("starting"), stored("failed", "start_failed"))!!.event)
        assertNull(eventOf(status("failed", "crashed"), stored("failed", "start_failed"))!!.event)
        val recovered = eventOf(status("ready"), stored("backoff", "crashed"))!!
        assertEquals(HostedProcessEvent.RECOVERED to "crashed", recovered.event to recovered.recoveredFrom)
        // A process that starts, or goes idle, without ever having failed is no event.
        assertNull(eventOf(status("ready"), null)!!.event)
        assertNull(eventOf(status("ready"), stored("idle", null))!!.event)
        // A failure recorded for an earlier key is not this process's: it fails afresh.
        assertEquals(
            HostedProcessEvent.FAILED,
            eventOf(status("failed", "crashed"), stored("failed", "crashed", key = "old"))!!.event,
        )
    }

    @Test
    fun theSweepRecordsEachProcessStateAndRemovesTheUnassignedOnes() = runTest {
        val repository = InMemoryRelayRepository()
        val shared = stdio("shared", McpSharing.SHARED)
        val personal = stdio("personal", McpSharing.PER_MEMBER)
        val gone = stdio("gone", McpSharing.SHARED)
        repository.servers += listOf(shared, personal, gone)
        repository.assign(shared, null)
        repository.assign(personal, Relay.memberA.memberId)
        repository.states += HostedProcessRecord(Relay.orgA, gone.id, null, gone.key(), "ready", null, Relay.epoch)
        // An org whose states cannot be read completely is left as it is.
        val other = stdio("other", McpSharing.SHARED).copy(orgId = Relay.orgB)
        repository.servers += other
        repository.assign(other, null)
        repository.states += List(RetainHostedMcpProcessesUseCaseImpl.MAX_KEYS * 2 + 1) {
            HostedProcessRecord(Relay.orgB, Uuid.random(), null, "k", "ready", null, Relay.epoch)
        }
        val host = FakeHostControl()
        host.processes[shared.key()] = status("ready")
        host.processes[personal.key(Relay.memberA.memberId)] = status("failed", "start_failed")
        val clock = TestClock(Relay.epoch + 1.minutes)
        val sweep = RetainHostedMcpProcessesUseCaseImpl(repository, null, host, clock, notice)
        assertEquals(HostedSweep.Done(3, 3, 2), sweep())
        val orgA = repository.states.filter { it.orgId == Relay.orgA }.associateBy { it.serverId }
        assertEquals(setOf(shared.id, personal.id), orgA.keys)
        assertEquals("failed" to "start_failed", orgA.getValue(personal.id).let { it.state to it.failure })
        assertEquals(Relay.memberA.memberId, orgA.getValue(personal.id).memberId)
        assertEquals(HostedSweep.Done(3, 3, 0), sweep(), "Nothing changed: nothing written")
        repository.assignments.removeAll { it.serverId == personal.id }
        assertEquals(HostedSweep.Done(2, 3, 0), sweep())
        assertEquals(listOf(shared.id), repository.states.filter { it.orgId == Relay.orgA }.map { it.serverId })
        host.statusFails = true
        assertEquals(HostedSweep.StatesUnrecorded, sweep(), "Retained, but no state read: not the host refusing")
        assertEquals(listOf(setOf(Relay.orgA, Relay.orgB) to "unreachable"), notice.notices, "The host is silent")
    }

    @Test
    fun administratorsSeeTheProcessesLiveOrAsRecordedWhenTheHostIsDown() = runTest {
        val repository = InMemoryRelayRepository()
        val shared = stdio("shared", McpSharing.SHARED)
        val personal = stdio("personal", McpSharing.PER_MEMBER)
        val bare = stdio("bare", McpSharing.SHARED).copy(stdio = null)
        repository.servers += listOf(shared, personal, bare)
        repository.assign(shared, null)
        repository.assign(personal, null)
        repository.assign(bare, null)
        repository.states += HostedProcessRecord(
            Relay.orgA, personal.id, Relay.memberA.memberId, personal.key(Relay.memberA.memberId), "backoff",
            "crashed", Relay.epoch,
        )
        repository.states += HostedProcessRecord(
            Relay.orgA, shared.id, null, shared.key(), "failed", "crashed", Relay.epoch,
        )
        val host = FakeHostControl()
        host.processes[shared.key()] = status("ready").copy(starts = 2, stderr = "x".repeat(5000), rssBytes = 9)
        host.processes[personal.key(Relay.otherA.memberId)] = status("failed", "start_failed")
        val members = FakeMembers(
            listOf(Relay.otherA.memberId.toString(), "not-a-member", Relay.memberA.memberId.toString()),
        )
        val admin = HostedProcessAdministration(repository, members, host)
        val live = admin(Relay.adminA)
        assertTrue(live.hostReachable)
        assertEquals("1.2.3", live.hostVersion)
        assertEquals(listOf("personal", "personal", "shared"), live.processes.map { it.server })
        val byMember = live.processes.associateBy { it.memberId }
        // The host answered without this process: it is idle, its last failure kept, as the sweep would record it.
        assertEquals("idle" to "crashed", byMember.getValue(Relay.memberA.memberId).let { it.state to it.failure })
        val other = byMember.getValue(Relay.otherA.memberId)
        assertEquals("failed" to "start_failed", other.state to other.failure)
        val sharedProcess = byMember.getValue(null)
        assertEquals("ready" to null, sharedProcess.state to sharedProcess.failure, "Recovered: no stale failure")
        assertEquals(4096, sharedProcess.status!!.stderr.length, "The last 4 KiB of stderr")
        assertEquals("npm" to "mcp-shared@1.0.0", sharedProcess.launcher to sharedProcess.packageName)
        host.fail = true
        val recorded = admin(Relay.adminA)
        assertFalse(recorded.hostReachable)
        assertEquals(listOf("backoff", "idle", "failed"), recorded.processes.map { it.state })
        assertFailsWith<OstromException> { admin(Relay.memberA) }
        val disabled = HostedProcessAdministration(repository, members, null)
        assertFailsWith<McpHostDisabledException> { disabled(Relay.adminA) }
        val unlisted = HostedProcessAdministration(repository, FakeMembers(null), host)
        assertFailsWith<McpHostedTooManyException> { unlisted(Relay.adminA) }
        val crowded = object : McpRelayRepository by repository {
            override suspend fun orgHostedAssignments(orgId: Uuid, limit: Int) = null
        }
        assertFailsWith<McpHostedTooManyException> { HostedProcessAdministration(crowded, members, host)(Relay.adminA) }
        // States past their bound are not shown: each process reads as the host says.
        val unrecorded = object : McpRelayRepository by repository {
            override suspend fun stdioStates(orgId: Uuid, limit: Int) = null
        }
        host.fail = false
        val asTheHostSays = HostedProcessAdministration(unrecorded, members, host)(Relay.adminA)
        assertEquals(listOf("idle", "failed", "ready"), asTheHostSays.processes.map { it.state })
    }

    @Test
    fun aRestartIsJournaledThenStopsTheProcessTheServerAndMemberName() = runTest {
        val repository = InMemoryRelayRepository()
        val shared = stdio("shared", McpSharing.SHARED)
        val personal = stdio("personal", McpSharing.PER_MEMBER)
        repository.servers += listOf(shared, personal)
        repository.assign(shared, null)
        repository.assign(personal, Relay.memberA.memberId)
        val host = FakeHostControl()
        // Without the member list, a member's own assignment still designates its process.
        val admin = HostedProcessAdministration(repository, null, host)
        val journal = mutableListOf<String>()
        admin(Relay.adminA, "shared", Relay.otherA.memberId) { journal += "shared" }
        admin(Relay.adminA, "personal", Relay.memberA.memberId) { journal += "personal" }
        assertEquals(listOf("shared", "personal"), journal)
        assertEquals(listOf(shared.key(), personal.key(Relay.memberA.memberId)), host.stopped)
        assertFailsWith<McpServerNotFoundException> { admin(Relay.adminA, "personal", Relay.otherA.memberId) {} }
        assertFailsWith<McpServerNotFoundException> { admin(Relay.adminA, "personal", null) {} }
        assertFailsWith<McpServerNotFoundException> { admin(Relay.adminA, "missing", null) {} }
        assertFailsWith<OstromException> { admin(Relay.memberA, "shared", null) {} }
        val disabled = HostedProcessAdministration(repository, null, null)
        assertFailsWith<McpHostDisabledException> { disabled(Relay.adminA, "shared", null) {} }
        host.fail = true
        assertFailsWith<McpHostUnavailableException> { admin(Relay.adminA, "shared", null) { journal += "down" } }
        assertEquals("down", journal.last(), "Journaled before the stop, even when the host does not answer")
    }

    @Test
    fun aMemberSeesTheStateOfItsStdioServersAndNeverLosesOneToAFailedRead() = runTest {
        val repository = InMemoryRelayRepository()
        val shared = stdio("shared", McpSharing.SHARED)
        val personal = stdio("personal", McpSharing.PER_MEMBER)
        val fresh = stdio("fresh", McpSharing.SHARED)
        val http = Relay.server("http")
        repository.servers += listOf(shared, personal, fresh, http)
        repository.servers.forEach { repository.assign(it, null) }
        repository.states += HostedProcessRecord(Relay.orgA, shared.id, null, shared.key(), "ready", null, Relay.epoch)
        repository.states += HostedProcessRecord(
            Relay.orgA, personal.id, Relay.memberA.memberId, personal.key(Relay.memberA.memberId), "failed",
            "start_failed", Relay.epoch,
        )
        val listing = ListMemberServersUseCaseImpl(repository)
        val servers = listing(Relay.memberA).associateBy { it.name }
        assertEquals("ready" to null, servers.getValue("shared").let { it.state to it.failure })
        assertEquals("failed" to "start_failed", servers.getValue("personal").let { it.state to it.failure })
        assertEquals("idle", servers.getValue("fresh").state)
        assertNull(servers.getValue("http").state)
        assertEquals("idle", listing(Relay.otherA).first { it.name == "personal" }.state, "Another member's row")
        repository.failStates = true
        val unread = listing(Relay.memberA)
        assertEquals(4, unread.size)
        assertTrue(unread.all { it.state == null && it.failure == null })
    }
}
