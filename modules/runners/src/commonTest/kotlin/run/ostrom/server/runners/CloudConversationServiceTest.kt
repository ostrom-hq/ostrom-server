// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CloudConversationException
import run.ostrom.server.runners.domain.CloudConversationServiceImpl
import run.ostrom.server.runners.domain.CloudEvent
import run.ostrom.server.runners.domain.CloudEventType
import run.ostrom.server.runners.domain.CloudInputKind
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CloudSessionNotFoundException
import run.ostrom.server.runners.domain.CloudSessionWake
import run.ostrom.server.runners.domain.CloudSubagent
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.GitHubAccessMode
import run.ostrom.server.runners.domain.InputOutcome
import run.ostrom.server.runners.domain.InputRequest
import run.ostrom.server.runners.domain.PendingWake
import run.ostrom.server.runners.domain.PermissionAnswer
import run.ostrom.server.runners.domain.PermissionRequestRecord
import run.ostrom.server.runners.domain.SessionMode
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.domain.WakeCloudSessionUseCase
import run.ostrom.server.runners.fakes.FakeConversationStore
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.cloud.domain.CloudRuntime
import run.ostrom.server.cloud.domain.CloudRuntimeSource
import run.ostrom.server.runners.infrastructure.CloudConversationMaintenance
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/** TeamCode's `CloudConversationService` rules, on a store in memory. */
class CloudConversationServiceTest {
    private val sessions = InMemoryCloudSessionsRepository()
    private val store = FakeConversationStore(sessions)
    private val clock = SettableClock()
    private val audit = mutableListOf<OrgAuditRecord>()
    private val woken = mutableListOf<Uuid>()
    private var wakeAnswer = CloudSessionWake.RESUMED
    private val wake = object : WakeCloudSessionUseCase {
        override suspend fun invoke(orgId: Uuid, sessionId: String, waitId: Uuid) = error("Not a message")

        override suspend fun forMessage(orgId: Uuid, sessionId: Uuid, inputId: Uuid): CloudSessionWake {
            woken += sessionId
            return wakeAnswer
        }
    }
    private val service = CloudConversationServiceImpl(store, sessions, wake, OrgAuditTrail { audit += it }, clock) {
        clock.now += it.milliseconds
    }
    private val alice = Caller(ORG_A, "alice", false)
    private val bob = Caller(ORG_A, "bob", false)
    private val dana = Caller(ORG_A, "dana", true)
    private val person = AuthenticatedPrincipal("alice", PrincipalKind.USER, emptySet(), credentialId = "c1")
    private val key = AuthenticatedPrincipal("alice", PrincipalKind.SERVICE, emptySet())

    private fun conversation(
        state: SessionState = SessionState.RUNNING,
        desired: DesiredState = DesiredState.RUNNING,
        mode: SessionMode = SessionMode.CONVERSATION,
    ): CloudSession = session(state = state, desired = desired).copy(mode = mode).also(sessions::put)

    private fun request(text: String? = "Hello", wake: Boolean = true) = InputRequest(text, wake, null, null, null)

    private suspend fun code(block: suspend () -> Unit): String =
        assertFailsWith<CloudConversationException> { block() }.type

    private fun event(seq: Long) =
        CloudEvent(Uuid.random(), seq, CloudEventType.STATUS, clock.now, 1, "{}", null)

    @Test
    fun theOwnerAndAnAdministratorReadTheTranscriptAnyoneElseFindsNothing() = runTest {
        val session = conversation()
        store.events += event(1)
        store.subagents = listOf(CloudSubagent("t1", null, "", "running", clock.now, null, 0, null, null))
        assertEquals(1, service.events(alice, session.id, null, null, 10, null).events.size)
        assertEquals(1, service.events(dana, session.id, null, null, 10, "main").events.size)
        assertEquals(1, service.subagents(alice, session.id).size)
        assertFailsWith<CloudSessionNotFoundException> { service.events(bob, session.id, null, null, 10, null) }
        assertFailsWith<CloudSessionNotFoundException> { service.subagents(bob, Uuid.random()) }
        assertEquals("invalid_limit", code { service.events(alice, session.id, null, null, 0, null) })
        assertEquals("invalid_feed", code { service.events(alice, session.id, null, null, 10, "a b") })
    }

    @Test
    fun aStreamOpensOnAConversationOnly() = runTest {
        service.openStream(alice, conversation().id, 0)
        val terminal = conversation(mode = SessionMode.TERMINAL)
        assertEquals("terminal_session", code { service.openStream(alice, terminal.id, 0) })
        assertEquals("invalid_cursor", code { service.openStream(alice, conversation().id, -1) })
    }

    @Test
    fun aStreamSendsEveryPageKeepsAliveAndEndsWithTheSession() = runTest {
        val session = conversation()
        repeat(250) { store.events += event(it + 1L) }
        var polls = 0
        store.onLastSeq = {
            polls++
            // Quiet for a while, so a keep-alive goes; then the session ends.
            if (polls == 40) sessions.put(sessions.sessions.getValue(session.id).copy(state = SessionState.TERMINATED))
        }
        val batches = mutableListOf<Int>()
        var idles = 0
        service.follow(alice, session.id, 0, { batches += it.size }, { idles++ })
        assertEquals(listOf(200, 50), batches)
        assertTrue(idles >= 1)
    }

    @Test
    fun aStreamEndsWithTheRefusalWhenTheCallerLosesTheSession() = runTest {
        val session = conversation()
        store.onLastSeq = { sessions.sessions.remove(session.id) }
        assertFailsWith<CloudSessionNotFoundException> { service.follow(alice, session.id, 0, {}, {}) }
    }

    @Test
    fun aMessageWakesItsSessionUnlessAskedNotTo() = runTest {
        val paused = conversation(SessionState.PAUSED, DesiredState.PAUSED)
        val sent = service.send(alice, person, paused.id, request())
        assertTrue(sent.input.woke)
        assertEquals(listOf(paused.id), store.cleared)
        assertEquals("c1", sent.input.credentialId)

        val quiet = service.send(alice, person, paused.id, request(wake = false))
        assertFalse(quiet.input.woke)
        assertEquals(1, woken.size)

        // A refusal of the plan keeps the mark for the sweep.
        wakeAnswer = CloudSessionWake.AT_CAP
        assertFalse(service.send(alice, person, paused.id, request()).input.woke)
        assertEquals(1, store.cleared.size)
        wakeAnswer = CloudSessionWake.NOT_PAUSED
        assertFalse(service.send(alice, person, paused.id, request()).input.woke)
        assertEquals(2, store.cleared.size)

        store.replay = sent.input
        assertIs<InputOutcome.Replayed>(service.send(alice, person, paused.id, request()))
    }

    @Test
    fun aMessageIsRefusedWhereItCannotGo() = runTest {
        assertEquals("invalid_message", code { service.send(alice, person, conversation().id, request(null)) })
        assertEquals("invalid_message", code { service.send(alice, person, conversation().id, request(" ")) })
        val terminal = conversation(mode = SessionMode.TERMINAL)
        assertEquals("terminal_session", code { service.send(alice, person, terminal.id, request()) })
        val ended = conversation(SessionState.TERMINATED)
        assertEquals("session_not_running", code { service.send(alice, person, ended.id, request()) })
        val deleting = conversation(desired = DesiredState.TERMINATED)
        assertEquals("session_not_running", code { service.send(alice, person, deleting.id, request()) })
        val owned = conversation().copy(githubAccess = GitHubAccessMode.OWNER).also(sessions::put)
        assertEquals("github_owner_only", code { service.send(dana, person, owned.id, request()) })
        val shared = conversation().copy(githubAccess = GitHubAccessMode.NONE).also(sessions::put)
        service.send(dana, person, shared.id, request())
    }

    @Test
    fun anInterruptAndATaskStopNeedARunningAgent() = runTest {
        val running = conversation()
        assertEquals(CloudInputKind.INTERRUPT, service.interrupt(alice, person, running.id, request(null)).input.kind)
        val stop = service.stopTask(alice, person, running.id, "b1", request(null))
        assertEquals(CloudInputKind.STOP_TASK, stop.input.kind)
        assertEquals("b1", stop.input.text)
        assertFalse(stop.input.wake)
        val paused = conversation(SessionState.PAUSED, DesiredState.PAUSED)
        assertEquals("nothing_to_interrupt", code { service.interrupt(alice, person, paused.id, request(null)) })
        assertEquals("nothing_to_interrupt", code { service.stopTask(alice, person, paused.id, "b1", request(null)) })
        val pausing = conversation(desired = DesiredState.PAUSED)
        assertEquals("nothing_to_interrupt", code { service.stopTask(alice, person, pausing.id, "b1", request(null)) })
        assertEquals("invalid_task", code { service.stopTask(alice, person, running.id, "a b", request(null)) })
        assertTrue(woken.isEmpty())
    }

    @Test
    fun anAnswerIsCheckedAgainstItsRequestUnderTheLock() = runTest {
        val running = conversation().copy(githubAccess = GitHubAccessMode.NONE).also(sessions::put)
        val allow = PermissionAnswer("r1", true, null, emptyList())
        assertEquals("unknown_permission_request", code { service.answer(alice, person, running.id, allow, request()) })
        store.request = PermissionRequestRecord("r1", false, null)
        assertEquals(CloudInputKind.PERMISSION, service.answer(alice, person, running.id, allow, request()).input.kind)
        service.answer(dana, person, running.id, allow, request())
        assertFailsWith<CloudSessionNotFoundException> { service.answer(bob, person, running.id, allow, request()) }
        val paused = conversation(SessionState.PAUSED, DesiredState.PAUSED)
        assertEquals("nothing_to_answer", code { service.answer(alice, person, paused.id, allow, request()) })
        val pausing = conversation(desired = DesiredState.PAUSED)
        assertEquals("nothing_to_answer", code { service.answer(alice, person, pausing.id, allow, request()) })
        assertEquals(
            "invalid_permission_answer",
            code { service.answer(alice, person, running.id, allow.copy(requestId = ""), request()) },
        )
        // A bare denial only stops: it reaches an agent on its owner's GitHub access from anyone who may message.
        val owned = conversation().copy(ownerId = "bob", githubAccess = GitHubAccessMode.OWNER).also(sessions::put)
        val deny = PermissionAnswer("r1", false, null, emptyList())
        service.answer(dana, person, owned.id, deny, request())
        assertEquals("github_owner_only", code { service.answer(dana, person, owned.id, allow, request()) })
    }

    @Test
    fun inputsArePagedAndReadOneByOne() = runTest {
        val running = conversation()
        val sent = service.send(alice, person, running.id, request()).input
        assertEquals(listOf(sent.id), service.inputs(alice, running.id, null, 10).inputs.map { it.id })
        assertEquals(sent.id, service.input(alice, running.id, sent.id).id)
        assertEquals("not_found", code { service.input(alice, running.id, Uuid.random()) })
        assertEquals("invalid_limit", code { service.inputs(alice, running.id, null, 0) })
        assertEquals("invalid_limit", code { service.inputs(alice, running.id, null, 101) })
    }

    @Test
    fun onlyTheOwnerInPersonTurnsBypassOnAndOff() = runTest {
        val running = conversation()
        val on = service.setPermissionBypass(alice, person, running.id, true)
        assertEquals("alice", on.permissionBypass?.by)
        assertEquals("c1", on.permissionBypass?.credentialId)
        assertEquals("cloud.session.permission_bypass_enabled", audit.single().event)
        assertEquals(on, service.setPermissionBypass(alice, person, running.id, true), "Idempotent")
        assertEquals(1, audit.size)
        assertNull(service.setPermissionBypass(alice, person, running.id, false).permissionBypass)
        assertEquals("cloud.session.permission_bypass_disabled", audit.last().event)
        assertEquals("bypass_owner_only", code { service.setPermissionBypass(alice, key, running.id, true) })
        assertFailsWith<CloudSessionNotFoundException> { service.setPermissionBypass(dana, person, running.id, true) }
        val ended = conversation(SessionState.TERMINATED)
        assertEquals("session_not_running", code { service.setPermissionBypass(alice, person, ended.id, true) })
        store.updates = false
        assertEquals("session_not_running", code { service.setPermissionBypass(alice, person, running.id, true) })
    }

    @Test
    fun aModelOrEffortChangeReachesARunningConversationAgent() = runTest {
        val running = conversation()
        assertEquals("opus", service.setAgentOption(alice, person, running.id, false, "opus").model)
        assertEquals("cloud.session.model_changed", audit.last().event)
        assertEquals(CloudInputKind.SET_MODEL, store.inputs.last().kind)
        assertEquals("high", service.setAgentOption(alice, person, running.id, true, "high").effort)
        assertEquals("cloud.session.effort_changed", audit.last().event)
        val queued = store.inputs.last()
        assertEquals(CloudInputKind.SET_EFFORT, queued.kind)
        assertEquals(queued.clientKey, queued.requestDigest)
        service.setAgentOption(alice, person, running.id, true, "high")
        assertEquals(2, store.inputs.size, "The same value changes nothing")

        val paused = conversation(SessionState.PAUSED, DesiredState.PAUSED)
        service.setAgentOption(alice, person, paused.id, false, "opus")
        val pausing = conversation(desired = DesiredState.PAUSED)
        service.setAgentOption(alice, person, pausing.id, false, "opus")
        val terminal = conversation(mode = SessionMode.TERMINAL)
        service.setAgentOption(alice, person, terminal.id, false, "opus")
        assertEquals(2, store.inputs.size, "Taken at the next start")

        assertEquals("invalid_agent", code { service.setAgentOption(alice, person, running.id, false, "a b") })
        assertEquals("invalid_agent", code { service.setAgentOption(alice, person, running.id, true, "") })
        store.updates = false
        assertEquals("session_not_running", code { service.setAgentOption(alice, person, running.id, false, "sonnet") })
    }

    @Test
    fun theSweepWakesWhatAMessageCouldNot() = runTest {
        val first = conversation(SessionState.PAUSED, DesiredState.PAUSED)
        val second = conversation(SessionState.PAUSED, DesiredState.PAUSED)
        store.wakes = listOf(PendingWake(ORG_A, first.id, Uuid.random()), PendingWake(ORG_A, second.id, Uuid.random()))
        assertEquals(2, service.wakePending(10))
        wakeAnswer = CloudSessionWake.AT_CAP
        assertEquals(0, service.wakePending(10))
        assertEquals(4, store.cleared.size, "One more try only")
    }

    @Test
    fun theMaintenancePurgesPastTheRuntimesRetentionOnly() = runTest {
        fun runtime(days: Int?) = object : CloudRuntimeSource {
            override suspend fun current() = CloudRuntime(transcriptRetentionDays = days)
        }
        CloudConversationMaintenance(store, service, runtime(30), clock).run()
        assertEquals(listOf(clock.now - 30.days), store.purged)
        CloudConversationMaintenance(store, service, runtime(null), clock).run()
        CloudConversationMaintenance(store, service, null, clock).run()
        assertEquals(1, store.purged.size, "Kept forever")
    }
}
