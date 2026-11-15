// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.runners.domain.AgentKind
import run.ostrom.server.runners.domain.CloudConversationException
import run.ostrom.server.runners.domain.CloudEventType
import run.ostrom.server.runners.domain.CloudInput
import run.ostrom.server.runners.domain.CloudInputKind
import run.ostrom.server.runners.domain.CloudInputStatus
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.ConversationResume
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.ExecutionFencedException
import run.ostrom.server.runners.domain.GitHubAccessMode
import run.ostrom.server.runners.domain.PermissionBypass
import run.ostrom.server.runners.domain.PermissionRequestRecord
import run.ostrom.server.runners.domain.RunnerAccepts
import run.ostrom.server.runners.domain.RunnerConversationStore
import run.ostrom.server.runners.domain.RunnerConversationUseCaseImpl
import run.ostrom.server.runners.domain.RunnerEvent
import run.ostrom.server.runners.domain.RunnerEventsAck
import run.ostrom.server.runners.domain.RunnerExecution
import run.ostrom.server.runners.domain.RunnerInputAck
import run.ostrom.server.runners.domain.SessionMode
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.domain.TurnMarker
import run.ostrom.server.runners.fakes.ALICE
import run.ostrom.server.runners.fakes.FakeConversationStore
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/** TeamCode's `RunnerConversationUseCase` rules, on stores in memory. */
class RunnerConversationUseCaseTest {
    private val sessions = InMemoryCloudSessionsRepository()
    private val conversations = FakeConversationStore(sessions)
    private val clock = SettableClock()
    private val runner = FakeRunnerStore()
    private val logins = object : MemberLogins {
        override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> = emptyMap()

        override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> =
            memberIds.filter { it == ALICE }.associateWith { "alice" }
    }
    private var sleeps = 0
    private val use = RunnerConversationUseCaseImpl(sessions, runner, conversations, logins, clock) {
        sleeps++
        clock.now += it.milliseconds
    }

    /** The runner side of the tables, in memory: queued inputs, and what was acknowledged. */
    class FakeRunnerStore : RunnerConversationStore {
        val queued = mutableListOf<CloudInput>()
        val acked = mutableListOf<RunnerInputAck>()
        var through: Long? = null

        override suspend fun appendRunnerEvents(
            orgId: Uuid,
            sessionId: Uuid,
            epoch: Long,
            events: List<RunnerEvent>,
        ): RunnerEventsAck {
            val last = through ?: events.lastOrNull()?.sequence ?: 0
            return RunnerEventsAck(last, last)
        }

        override suspend fun deliver(orgId: Uuid, sessionId: Uuid, epoch: Long, limit: Int): List<CloudInput> =
            queued.toList().also { queued.clear() }

        override suspend fun acknowledge(
            orgId: Uuid,
            sessionId: Uuid,
            epoch: Long,
            acks: List<RunnerInputAck>,
        ): List<CloudInput> {
            acked += acks
            return emptyList()
        }

        override suspend fun conversation(orgId: Uuid, sessionId: Uuid): ConversationResume =
            ConversationResume.RESUMABLE
    }

    private fun conversation(
        state: SessionState = SessionState.RUNNING,
        desired: DesiredState = DesiredState.RUNNING,
        owner: String = ALICE.toString(),
    ): CloudSession = session(state = state, desired = desired, ownerId = owner)
        .copy(mode = SessionMode.CONVERSATION, epoch = 2).also(sessions::put)

    private fun CloudSession.execution(epoch: Long = this.epoch) = RunnerExecution(orgId, id, epoch)

    private suspend fun code(block: suspend () -> Unit): String =
        assertFailsWith<CloudConversationException> { block() }.type

    private fun input(kind: CloudInputKind, author: String = ALICE.toString(), text: String? = "x") = CloudInput(
        Uuid.random(), ORG_A, Uuid.random(), kind, CloudInputStatus.DELIVERED, author, null, text, false, null, null,
        clock.now,
    )

    private fun request(seq: Long, data: String, turn: TurnMarker? = null) =
        RunnerEvent(seq, CloudEventType.PERMISSION_REQUEST, data, turn)

    @Test
    fun theAgentIsConfiguredForItsKindAndItsCurrentOwner() = runTest {
        val claude = conversation().copy(model = "opus", effort = "high").also(sessions::put)
        val agent = use.agent(claude.execution())
        assertEquals(AgentKind.CLAUDE, agent.kind)
        assertEquals("opus" to "high", agent.model to agent.effort)
        assertEquals(ConversationResume.RESUMABLE, agent.conversation)
        assertTrue(agent.permissionRelay && agent.liveText && agent.backgroundTasks)
        assertEquals(0, agent.permissionDeadlineMinutes)
        assertEquals("alice", agent.owner?.username)
        assertNull(agent.owner?.githubLogin)
        val codex = conversation(owner = "bob").copy(agent = AgentKind.CODEX).also(sessions::put)
        val other = use.agent(codex.execution())
        assertTrue(!other.permissionRelay && !other.liveText && !other.backgroundTasks)
        assertNull(other.owner, "No login for an owner who is no member id")
        val stranger = conversation(owner = Uuid.random().toString())
        assertNull(use.agent(stranger.execution()).owner, "Nor for a member who left")
    }

    @Test
    fun onlyTheLiveExecutionOfAConversationIsServed() = runTest {
        val live = conversation()
        assertFailsWith<ExecutionFencedException> { use.agent(live.execution(epoch = 1)) }
        assertFailsWith<ExecutionFencedException> { use.agent(conversation(state = SessionState.PAUSED).execution()) }
        val deleted = conversation(desired = DesiredState.TERMINATED)
        assertFailsWith<ExecutionFencedException> { use.agent(deleted.execution()) }
        assertFailsWith<ExecutionFencedException> { use.agent(RunnerExecution(ORG_A, Uuid.random(), 2)) }
        val terminal = conversation().copy(mode = SessionMode.TERMINAL).also(sessions::put)
        assertEquals("terminal_session", code { use.agent(terminal.execution()) })
        use.agent(conversation(state = SessionState.CREATING).execution())
    }

    @Test
    fun aBatchIsBoundedConsecutiveAndATurnIsActivity() = runTest {
        val session = conversation()
        val status = { seq: Long -> RunnerEvent(seq, CloudEventType.STATUS, "{}") }
        assertEquals("invalid_events", code { use.events(session.execution(), (1L..201L).map(status)) })
        assertEquals("invalid_events", code { use.events(session.execution(), listOf(status(0))) })
        assertEquals("invalid_events", code { use.events(session.execution(), listOf(status(1), status(3))) })
        assertEquals(RunnerEventsAck(2, 2), use.events(session.execution(), listOf(status(1), status(2))))
        assertNull(sessions.sessions.getValue(session.id).lastActivityAt, "No turn, no activity")
        val turn = RunnerEvent(3, CloudEventType.TURN, """{"phase":"started"}""", TurnMarker.STARTED)
        use.events(session.execution(), listOf(turn))
        assertEquals(clock.now, sessions.sessions.getValue(session.id).lastActivityAt)
        val pausing = conversation(desired = DesiredState.PAUSED)
        use.events(pausing.execution(), listOf(turn))
        assertNull(sessions.sessions.getValue(pausing.id).lastActivityAt, "A session asked to pause is not kept busy")
        val starting = conversation(state = SessionState.CREATING)
        use.events(starting.execution(), listOf(turn))
        assertNull(sessions.sessions.getValue(starting.id).lastActivityAt)
    }

    @Test
    fun theOwnersBypassAllowsEachToolUseOfTheBatchButNoQuestion() = runTest {
        val owner = ALICE.toString()
        val bypass = PermissionBypass(owner, "c1", clock.now)
        val session = conversation().copy(permissionBypass = bypass).also(sessions::put)
        conversations.request = PermissionRequestRecord("r1", question = false, questions = null, epoch = 2)
        val batch = listOf(
            request(1, """{"requestId":"r1","tool":"Bash"}"""),
            request(2, """{"requestId":"q1","question":true}"""),
            request(3, """{"requestId":"a b"}"""),
            request(4, """{"tool":"Bash"}"""),
            request(5, "[]"),
            RunnerEvent(6, CloudEventType.STATUS, """{"requestId":"r2"}"""),
            request(7, """{"requestId":null}"""),
        )
        use.events(session.execution(), batch)
        val answer = conversations.inputs.single()
        assertEquals(CloudInputKind.PERMISSION, answer.kind)
        assertEquals("""{"requestId":"r1","behavior":"allow"}""", answer.text)
        assertEquals(owner to "c1", answer.authorId to answer.credentialId)
        assertEquals("bypass:r1", answer.clientKey)
        assertEquals(64, answer.requestDigest?.length)
        // Only what the runner stored is answered.
        runner.through = 0
        use.events(session.execution(), listOf(request(7, """{"requestId":"r3"}""")))
        assertEquals(1, conversations.inputs.size)
        runner.through = null
        // Someone else's bypass answers nothing.
        val other = conversation().copy(permissionBypass = bypass.copy(by = "bob")).also(sessions::put)
        use.events(other.execution(), listOf(request(1, """{"requestId":"r1"}""")))
        assertEquals(1, conversations.inputs.size)
    }

    @Test
    fun aBypassAnswerTheChecksRefuseLeavesTheRequestToTheOwner() = runTest {
        val bypass = PermissionBypass(ALICE.toString(), null, clock.now)
        val ask = listOf(request(1, """{"requestId":"r1"}"""))
        conversations.request = null
        use.events(conversation().copy(permissionBypass = bypass).also(sessions::put).execution(), ask)
        conversations.request = PermissionRequestRecord("r1", question = false, questions = null, epoch = 2)
        val pausing = conversation(desired = DesiredState.PAUSED).copy(permissionBypass = bypass).also(sessions::put)
        use.events(pausing.execution(), ask)
        val stopping = conversation(state = SessionState.PAUSING).copy(permissionBypass = bypass).also(sessions::put)
        use.events(stopping.execution(), ask)
        assertTrue(conversations.inputs.isEmpty())
        // A request too large to show is still allowed: the approval was given in advance.
        conversations.request = PermissionRequestRecord("r1", false, null, epoch = 2, inputLeftOut = true)
        use.events(conversation().copy(permissionBypass = bypass).also(sessions::put).execution(), ask)
        assertEquals(1, conversations.inputs.size)
    }

    @Test
    fun aPollDeliversWhatTheRunnerTakesAndRefusesTheRest() = runTest {
        val session = conversation().copy(githubAccess = GitHubAccessMode.OWNER).also(sessions::put)
        val message = input(CloudInputKind.MESSAGE)
        val stop = input(CloudInputKind.STOP_TASK, text = "b1")
        val model = input(CloudInputKind.SET_MODEL)
        val effort = input(CloudInputKind.SET_EFFORT)
        val foreign = input(CloudInputKind.MESSAGE, author = "bob")
        val interrupt = input(CloudInputKind.INTERRUPT, author = "bob")
        runner.queued += listOf(message, stop, model, effort, foreign, interrupt)
        val polled = use.poll(session.execution(), 0, null, RunnerAccepts())
        assertEquals(listOf(message, interrupt), polled.inputs)
        assertEquals(DesiredState.RUNNING, polled.desiredState)
        assertEquals(
            listOf(
                stop.id to "task_stop_unsupported",
                model.id to "model_change_unsupported",
                effort.id to "effort_change_unsupported",
                foreign.id to "github_owner_only",
            ),
            runner.acked.map { it.id to it.reason },
        )
        runner.acked.clear()
        runner.queued += listOf(stop, model, effort)
        val accepts = RunnerAccepts(taskStops = true, modelChanges = true, effortChanges = true)
        assertEquals(listOf(stop, model, effort), use.poll(session.execution(), 0, "running", accepts).inputs)
        assertTrue(runner.acked.isEmpty())
        // Without the owner's GitHub access, anyone who may message the agent instructs it.
        val open = conversation().copy(githubAccess = GitHubAccessMode.NONE).also(sessions::put)
        runner.queued += foreign
        assertEquals(listOf(foreign), use.poll(open.execution(), 0, null, RunnerAccepts()).inputs)
    }

    @Test
    fun aPollWaitsUntilItsDeadlineOrAStateChange() = runTest {
        val session = conversation()
        assertEquals("invalid_state", code { use.poll(session.execution(), 0, "terminated", RunnerAccepts()) })
        assertEquals("invalid_state", code { use.poll(session.execution(), 0, "asleep", RunnerAccepts()) })
        val empty = use.poll(session.execution(), 60_000, null, RunnerAccepts())
        assertTrue(empty.inputs.isEmpty())
        assertEquals(40, sleeps, "Waited 20 s at most, every half second")
        sleeps = 0
        val paused = conversation(desired = DesiredState.PAUSED)
        assertEquals(DesiredState.PAUSED, use.poll(paused.execution(), 5_000, "running", RunnerAccepts()).desiredState)
        assertEquals(0, sleeps, "A pause ends the poll at once")
        runner.queued += input(CloudInputKind.MESSAGE)
        assertTrue(use.poll(paused.execution(), 0, "paused", RunnerAccepts()).inputs.isEmpty(), "Nothing for a pause")
        val starting = conversation(state = SessionState.CREATING)
        assertTrue(use.poll(starting.execution(), 0, null, RunnerAccepts()).inputs.isEmpty())
    }

    @Test
    fun acknowledgementsAreBoundedAndCarryStableReasons() = runTest {
        val session = conversation()
        val ack = { reason: String? -> RunnerInputAck(Uuid.random(), reason == null, reason) }
        assertEquals("invalid_acks", code { use.acknowledge(session.execution(), List(101) { ack(null) }) })
        assertEquals("invalid_acks", code { use.acknowledge(session.execution(), listOf(ack("Not Stable"))) })
        val acks = listOf(ack(null), ack("files_unsupported"))
        use.acknowledge(session.execution(), acks)
        assertEquals(acks, runner.acked)
        assertFailsWith<ExecutionFencedException> { use.acknowledge(session.execution(epoch = 9), acks) }
    }
}
