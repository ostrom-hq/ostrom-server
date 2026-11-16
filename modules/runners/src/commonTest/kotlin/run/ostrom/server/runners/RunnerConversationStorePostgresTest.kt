// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.CloudConversationStore
import run.ostrom.server.runners.domain.CloudEventType
import run.ostrom.server.runners.domain.CloudHumanWait
import run.ostrom.server.runners.domain.CloudInput
import run.ostrom.server.runners.domain.CloudInputKind
import run.ostrom.server.runners.domain.CloudInputStatus
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CloudSessionNotFoundException
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.ConversationResume
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.NewInput
import run.ostrom.server.runners.domain.RunnerEvent
import run.ostrom.server.runners.domain.RunnerEventsAck
import run.ostrom.server.runners.domain.RunnerInputAck
import run.ostrom.server.runners.domain.SessionMode
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.domain.TurnMarker
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.ORG_B
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.infrastructure.RunnerConversationStoreImpl
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The runner side of the conversation's tables on real PostgreSQL (TeamCode's `SqlCloudConversationStore`). */
class RunnerConversationStorePostgresTest {
    private val t0 = Instant.parse("2026-11-14T10:00:00Z")
    private val clock = SettableClock(t0)

    private suspend fun TestServer.conversation(
        state: SessionState = SessionState.RUNNING,
        desired: DesiredState = DesiredState.RUNNING,
    ): CloudSession {
        val created = session(state = state, desired = desired, createdAt = t0)
            .copy(mode = SessionMode.CONVERSATION, epoch = 2)
        assertTrue(koin.get<CloudSessionsRepository>().createWithinCap(created, 100))
        return created
    }

    private suspend fun TestServer.sql(statement: String) {
        database.superuserOutsideTransaction(statement)
    }

    private fun TestServer.runner() = RunnerConversationStoreImpl(koin.get(), clock)

    private fun TestServer.conversations() = koin.get<CloudConversationStore>()

    private suspend fun TestServer.waiting(session: CloudSession): CloudHumanWait? =
        koin.get<CloudSessionsRepository>().get(ORG_A, session.id)?.waiting

    private suspend fun TestServer.queue(
        session: CloudSession,
        kind: CloudInputKind,
        text: String? = "Hi",
    ): CloudInput {
        clock.now += 1.seconds
        val input = CloudInput(
            Uuid.random(), ORG_A, session.id, kind, CloudInputStatus.QUEUED, "alice", "c1", text, false, null, null,
            clock.now,
        )
        conversations().insertInput(NewInput(input, null), 100)
        return input
    }

    private suspend fun TestServer.stored(session: CloudSession, input: CloudInput): CloudInput =
        assertNotNull(conversations().input(ORG_A, session.id, input.id))

    private fun ask(seq: Long, data: String) = RunnerEvent(seq, CloudEventType.PERMISSION_REQUEST, data)

    private fun expire(seq: Long, id: String) =
        RunnerEvent(seq, CloudEventType.PERMISSION_EXPIRED, """{"requestId":"$id"}""")

    private fun ended(seq: Long) = RunnerEvent(seq, CloudEventType.TURN, """{"phase":"completed"}""", TurnMarker.ENDED)

    @Test
    fun aBatchIsStoredOnceInOrderAndNumberedAfreshByANewExecution() = serverTest(listOf(RunnersModule)) { server ->
        val runner = server.runner()
        val session = server.conversation()
        val status = { seq: Long -> RunnerEvent(seq, CloudEventType.STATUS, "{}") }
        val unordered = listOf(status(2), status(1))
        assertEquals(RunnerEventsAck(2, 2), runner.appendRunnerEvents(ORG_A, session.id, 2, unordered))
        // A repeat is skipped, and a gap stops the batch: the runner resends from acceptedThrough + 1.
        val retried = listOf(status(2), status(3), status(5))
        assertEquals(RunnerEventsAck(3, 3), runner.appendRunnerEvents(ORG_A, session.id, 2, retried))
        assertEquals(RunnerEventsAck(3, 3), runner.appendRunnerEvents(ORG_A, session.id, 2, emptyList()))
        // The next execution counts from 1 again, after the transcript.
        assertEquals(RunnerEventsAck(1, 4), runner.appendRunnerEvents(ORG_A, session.id, 3, listOf(status(1))))
        val events = server.conversations().events(ORG_A, session.id, null, null, 10, null).events
        assertEquals(listOf(1L, 2L, 3L, 4L), events.map { it.seq })
        assertEquals(listOf(2L, 2L, 2L, 3L), events.map { it.epoch })
        assertFailsWith<CloudSessionNotFoundException> {
            runner.appendRunnerEvents(ORG_B, session.id, 2, listOf(status(1)))
        }
        assertFailsWith<CloudSessionNotFoundException> { runner.deliver(ORG_A, Uuid.random(), 2, 5) }
        assertFailsWith<CloudSessionNotFoundException> { runner.acknowledge(ORG_B, session.id, 2, emptyList()) }
    }

    @Test
    fun theWaitFollowsTheRequestsTheirExpiryAndTheTurn() = serverTest(listOf(RunnersModule)) { server ->
        val runner = server.runner()
        val session = server.conversation()
        val asked = listOf(
            ask(1, """{"requestId":"r1","tool":"Bash"}"""),
            ask(2, """{"tool":"Bash"}"""),
            ask(3, """{"requestId":"a b"}"""),
            ask(4, """{"requestId":"q1","question":true}"""),
            ask(5, "[]"),
        )
        runner.appendRunnerEvents(ORG_A, session.id, 2, asked)
        assertEquals(CloudHumanWait("question", "q1", t0, 4, 2), server.waiting(session), "No answerable id: no wait")
        runner.appendRunnerEvents(ORG_A, session.id, 2, listOf(expire(6, "zz")))
        assertEquals("q1", server.waiting(session)?.requestId, "Another request's expiry leaves the wait")
        // The shown request expired: the newest earlier one still open waits, its id read from the stored head.
        clock.now += 5.seconds
        runner.appendRunnerEvents(ORG_A, session.id, 2, listOf(expire(7, "q1")))
        assertEquals(CloudHumanWait("permission", "r1", t0, 1, 2), server.waiting(session))
        runner.appendRunnerEvents(ORG_A, session.id, 2, listOf(expire(8, "r1")))
        assertNull(server.waiting(session), "Nothing earlier")
        // A turn that ends closes every request of it; an earlier request whose turn ended stops the search.
        runner.appendRunnerEvents(ORG_A, session.id, 2, listOf(ask(9, """{"requestId":"t1"}"""), ended(10)))
        assertNull(server.waiting(session))
        runner.appendRunnerEvents(ORG_A, session.id, 2, listOf(ask(11, """{"requestId":"t2"}"""), expire(12, "t2")))
        assertNull(server.waiting(session))
        // A new execution's requests never reopen the last one's.
        runner.appendRunnerEvents(ORG_A, session.id, 3, listOf(ask(1, """{"requestId":"n1"}""")))
        assertEquals(3, server.waiting(session)?.epoch)
        runner.appendRunnerEvents(ORG_A, session.id, 3, listOf(expire(2, "n1")))
        assertNull(server.waiting(session))
    }

    @Test
    fun aRequestRaisedWhileTheSessionTakesNoAnswerNeverWaits() = serverTest(listOf(RunnersModule)) { server ->
        val runner = server.runner()
        val pausing = server.conversation(desired = DesiredState.PAUSED)
        runner.appendRunnerEvents(ORG_A, pausing.id, 2, listOf(ask(1, """{"requestId":"r1"}"""), expire(2, "r1")))
        assertNull(server.waiting(pausing))
        val starting = server.conversation(state = SessionState.CREATING)
        runner.appendRunnerEvents(ORG_A, starting.id, 2, listOf(ask(1, """{"requestId":"r1"}""")))
        assertNull(server.waiting(starting))
        // A wait row written half (by hand) still reads, and is replaced whole.
        val half = server.conversation()
        server.sql("UPDATE runners_sessions SET waiting_reason = 'permission' WHERE id = '${half.id}'")
        runner.appendRunnerEvents(ORG_A, half.id, 2, listOf(expire(1, "")))
        runner.appendRunnerEvents(ORG_A, half.id, 2, listOf(ask(2, """{"requestId":"r2"}""")))
        assertEquals(CloudHumanWait("permission", "r2", t0, 2, 2), server.waiting(half))
    }

    @Test
    fun anAnswerConsumedClosesItsRequestAndAnAnsweredOneIsPassed() = serverTest(listOf(RunnersModule)) { server ->
        val runner = server.runner()
        val session = server.conversation()
        val asked = listOf(
            ask(1, """{"requestId":"p0"}"""),
            ask(2, """{"requestId":"p1"}"""),
            ask(3, """{"requestId":"p2"}"""),
        )
        runner.appendRunnerEvents(ORG_A, session.id, 2, asked)
        val answer = { id: String ->
            """{"requestId":"$id","behavior":"allow"}"""
        }
        val p1 = server.queue(session, CloudInputKind.PERMISSION, answer("p1"))
        runner.deliver(ORG_A, session.id, 2, 5)
        val done = runner.acknowledge(ORG_A, session.id, 2, listOf(RunnerInputAck(p1.id, true, null)))
        assertEquals(CloudInputStatus.CONSUMED, done.single().status)
        assertEquals("p2", server.waiting(session)?.requestId, "Not the request shown")
        val p2 = server.queue(session, CloudInputKind.PERMISSION, answer("p2"))
        runner.deliver(ORG_A, session.id, 2, 5)
        runner.acknowledge(ORG_A, session.id, 2, listOf(RunnerInputAck(p2.id, true, null)))
        assertEquals("p0", server.waiting(session)?.requestId, "p1 was answered: p0 still waits")
        // A refused answer closes nothing.
        val p0 = server.queue(session, CloudInputKind.PERMISSION, answer("p0"))
        runner.deliver(ORG_A, session.id, 2, 5)
        runner.acknowledge(ORG_A, session.id, 2, listOf(RunnerInputAck(p0.id, false, "stale_answer")))
        assertEquals("p0", server.waiting(session)?.requestId)
    }

    @Test
    fun urgentInputsGoFirstAndAStaleDeliveryIsRetriedOrRejected() = serverTest(listOf(RunnersModule)) { server ->
        val runner = server.runner()
        val session = server.conversation()
        val message = server.queue(session, CloudInputKind.MESSAGE)
        val later = server.queue(session, CloudInputKind.MESSAGE)
        val interrupt = server.queue(session, CloudInputKind.INTERRUPT, null)
        val answer = server.queue(session, CloudInputKind.PERMISSION, """{"requestId":"r1","behavior":"deny"}""")
        assertEquals(listOf(interrupt.id, answer.id), runner.deliver(ORG_A, session.id, 2, 2).map { it.id })
        val first = runner.deliver(ORG_A, session.id, 2, 1).single()
        assertEquals(message.id to 1, first.id to first.attempts)
        assertEquals(CloudInputStatus.DELIVERED, server.stored(session, message).status)
        // Unacknowledged past the redelivery delay: a message and an answer again, an interrupt never.
        clock.now += 61.seconds
        val again = runner.deliver(ORG_A, session.id, 2, 5)
        assertEquals(listOf(answer.id, message.id, later.id), again.map { it.id })
        assertEquals(listOf(2, 2, 1), again.map { it.attempts })
        assertEquals("delivery_failed", server.stored(session, interrupt).reason)
        // Another execution: the answer concerned the agent that ended; the message is offered once more.
        val next = runner.deliver(ORG_A, session.id, 3, 5)
        assertEquals(listOf(message.id, later.id), next.map { it.id })
        assertEquals(CloudInputStatus.REJECTED to "execution_ended", server.stored(session, answer).let {
            it.status to it.reason
        })
        // Its attempts spent, a message is rejected.
        clock.now += 61.seconds
        assertEquals(listOf(later.id), runner.deliver(ORG_A, session.id, 3, 5).map { it.id })
        assertEquals("delivery_failed", server.stored(session, message).reason)
    }

    @Test
    fun anAcknowledgementFinishesOnlyThisExecutionsDeliveries() = serverTest(listOf(RunnersModule)) { server ->
        val runner = server.runner()
        val session = server.conversation()
        val message = server.queue(session, CloudInputKind.MESSAGE, "Fix the build")
        val interrupt = server.queue(session, CloudInputKind.INTERRUPT, null)
        val stop = server.queue(session, CloudInputKind.STOP_TASK, "b1")
        val queued = server.queue(session, CloudInputKind.MESSAGE)
        runner.deliver(ORG_A, session.id, 2, 3)
        val acks = listOf(
            RunnerInputAck(message.id, true, null),
            RunnerInputAck(message.id, false, "twice"),
            RunnerInputAck(interrupt.id, false, null),
            RunnerInputAck(stop.id, false, "task_stop_unsupported"),
            RunnerInputAck(queued.id, true, null),
            RunnerInputAck(Uuid.random(), true, null),
        )
        assertTrue(runner.acknowledge(ORG_A, session.id, 3, acks).isEmpty(), "Another execution's acks")
        val finished = runner.acknowledge(ORG_A, session.id, 2, acks)
        assertEquals(listOf(message.id, interrupt.id, stop.id), finished.map { it.id })
        assertEquals(listOf(null, "rejected", "task_stop_unsupported"), finished.map { it.reason })
        val events = server.conversations().events(ORG_A, session.id, null, null, 10, null).events
        val said = events.single { it.type == CloudEventType.USER_MESSAGE }
        assertTrue(""""text":"Fix the build"""" in said.data && """"authorId":"alice"""" in said.data)
        assertEquals(2, events.count { it.type == CloudEventType.INPUT })
        assertEquals(CloudInputStatus.QUEUED, server.stored(session, queued).status)
        // A message without text (none is sent so) is still said, empty.
        server.sql("UPDATE runners_session_inputs SET text = NULL WHERE id = '${queued.id}'")
        runner.deliver(ORG_A, session.id, 2, 3)
        runner.acknowledge(ORG_A, session.id, 2, listOf(RunnerInputAck(queued.id, true, null)))
        val empty = server.conversations().events(ORG_A, session.id, null, null, 10, null).events.last()
        assertTrue(""""text":""""" in empty.data)
    }

    @Test
    fun theConversationMarkerIsTheNewestStatusThatSaysIt() = serverTest(listOf(RunnersModule)) { server ->
        val runner = server.runner()
        val session = server.conversation()
        assertEquals(ConversationResume.NONE, runner.conversation(ORG_A, session.id))
        val said = listOf(
            RunnerEvent(1, CloudEventType.STATUS, """{"conversation":"resumable"}"""),
            RunnerEvent(2, CloudEventType.STATUS, """{"conversation":"transcript_only"}"""),
            RunnerEvent(3, CloudEventType.AGENT_MESSAGE, """{"conversation":"none"}"""),
            RunnerEvent(4, CloudEventType.STATUS, """{"conversation":"lost"}"""),
            RunnerEvent(5, CloudEventType.STATUS, """{"state":"idle"}"""),
        )
        runner.appendRunnerEvents(ORG_A, session.id, 2, said)
        assertEquals(ConversationResume.TRANSCRIPT_ONLY, runner.conversation(ORG_A, session.id))
        assertEquals(ConversationResume.NONE, runner.conversation(ORG_B, session.id), "Org B sees none")
    }

    @Test
    fun subagentsAreFollowedFromTheirCallToTheirResultAndTasks() = serverTest(listOf(RunnersModule)) { server ->
        val runner = server.runner()
        val session = server.conversation()
        var n = 0L
        fun ev(type: CloudEventType, data: String) = RunnerEvent(++n, type, data)
        suspend fun send(vararg events: RunnerEvent) = runner.appendRunnerEvents(ORG_A, session.id, 2, events.toList())
        suspend fun agents() = server.conversations().subagents(ORG_A, session.id, 50).associateBy { it.id }
        send(
            ev(CloudEventType.TOOL_CALL, """{"id":"main-1","name":"Read","input":{}}"""),
            ev(
                CloudEventType.TOOL_CALL,
                """{"id":"ag-1","name":"Agent","input":{"description":"Scan the repo","subagent_type":"Explore"}}""",
            ),
            ev(CloudEventType.TOOL_CALL, """{"id":"s-1","name":"Grep","input":{},"parentToolUseId":"ag-1"}"""),
            ev(CloudEventType.AGENT_MESSAGE, """{"text":"found it","parentToolUseId":"ag-1"}"""),
            ev(CloudEventType.AGENT_MESSAGE, """{"text":"main text","parentToolUseId":"bad feed!"}"""),
            ev(CloudEventType.TOOL_CALL, """{"id":"s-2","name":"Read","input":{},"parentToolUseId":"ag-1"}"""),
            ev(
                CloudEventType.TOOL_CALL,
                """{"id":"ag-9","name":"Task","input":{"description":"Inner","subagent_type":"Plan"},""" +
                    """"parentToolUseId":"ag-1"}""",
            ),
            ev(CloudEventType.TOOL_CALL, """{"id":"ag-x","name":"Agent","input":"not an object"}"""),
            ev(CloudEventType.TOOL_CALL, """{"id":5,"name":"Agent"}"""),
            ev(CloudEventType.TOOL_CALL, """{"id":"bad id!","name":"Agent"}"""),
            ev(CloudEventType.TOOL_CALL, "[]"),
            ev(CloudEventType.TURN, """{"phase":"started","parentToolUseId":"ag-1"}"""),
        )
        val feed = server.conversations().events(ORG_A, session.id, null, null, 50, "ag-1").events
        assertEquals(4, feed.size, "Its calls and its text; a turn is the session's")
        assertTrue(server.conversations().events(ORG_A, session.id, null, null, 50, "main").events.none {
            "found it" in it.data
        })
        val running = agents().getValue("ag-1")
        assertEquals("Explore" to "Scan the repo", running.type to running.description)
        assertEquals("running", running.state)
        assertEquals(3, running.steps, "Its tool uses, a nested agent's call included")
        assertEquals("ag-1", agents().getValue("ag-9").parentId)
        assertEquals(null to "", agents().getValue("ag-x").let { it.type to it.description })

        // A returned call ends its agent; an error fails it.
        send(
            ev(CloudEventType.TOOL_RESULT, """{"id":"ag-1","output":"done","isError":false}"""),
            ev(CloudEventType.TOOL_RESULT, """{"id":"ag-9","output":"boom","isError":true,"parentToolUseId":"ag-1"}"""),
            ev(CloudEventType.TOOL_RESULT, """{"id":"bad id!","isError":true}"""),
            ev(CloudEventType.TOOL_RESULT, "[]"),
            ev(CloudEventType.TOOL_RESULT, """{"output":"x"}"""),
            ev(CloudEventType.TOOL_RESULT, """{"id":"ag-x","output":"done"}"""),
        )
        assertEquals("completed", agents().getValue("ag-1").state)
        assertNotNull(agents().getValue("ag-1").endedAt)
        assertEquals("failed", agents().getValue("ag-9").state)

    }

    @Test
    fun aBackgroundAgentRunsWhileItsTaskIsLive() = serverTest(listOf(RunnersModule)) { server ->
        val runner = server.runner()
        val session = server.conversation()
        var n = 0L
        fun ev(type: CloudEventType, data: String) = RunnerEvent(++n, type, data)
        suspend fun send(vararg events: RunnerEvent) = runner.appendRunnerEvents(ORG_A, session.id, 2, events.toList())
        suspend fun agents() = server.conversations().subagents(ORG_A, session.id, 50).associateBy { it.id }
        // Its call returns at once and its live task keeps it running; a task seen first still shows, with its start.
        fun tasks(count: Int?, vararg tasks: String) = ev(
            CloudEventType.TASKS,
            """{${count?.let { "\"count\":$it," }.orEmpty()}"tasks":[${tasks.joinToString(",")}]}""",
        )
        fun task(id: String, use: String, started: String = "111") =
            """{"id":"$id","description":"bg","subagentType":"Explore","toolUseId":"$use","startedAt":$started}"""
        send(
            ev(CloudEventType.TOOL_CALL, """{"id":"ag-2","name":"Agent","input":{"description":"Background"}}"""),
            ev(CloudEventType.TOOL_CALL, """{"id":"ag-4","name":"Agent","input":{"description":"Failing"}}"""),
            tasks(
                null,
                task("t1", "ag-2"),
                task("t2", "ag-3", "222"),
                task("t4", "ag-4"),
                task("t6", "ag-6", "\"soon\""),
                """{"id":"c1","description":"npm test","toolUseId":"cmd-1"}""",
                """{"id":"c2","subagentType":"Explore","toolUseId":"bad id!"}""",
                """{"subagentType":"Explore","toolUseId":"ag-5"}""",
                """{"id":"c3","subagentType":"Explore"}""",
                """{"id":"t7","subagentType":"Plan","toolUseId":"ag-7"}""",
                "1",
            ),
            ev(CloudEventType.TOOL_RESULT, """{"id":"ag-2","output":"started","isError":false}"""),
            ev(CloudEventType.TOOL_RESULT, """{"id":"ag-4","output":"started","isError":true}"""),
            ev(CloudEventType.TASKS, """{"count":3}"""),
        )
        assertEquals("running" to "t1", agents().getValue("ag-2").let { it.state to it.taskId })
        assertEquals(Instant.fromEpochMilliseconds(222), agents().getValue("ag-3").startedAt)
        assertEquals("running", agents().getValue("ag-4").state)
        assertEquals(clock.now, agents().getValue("ag-6").startedAt, "No start known: when it was seen")
        assertEquals("Plan" to "", agents().getValue("ag-7").let { it.type to it.description })
        assertNull(agents()["cmd-1"], "A background command is not an agent")
        // A task standing for another agent's call moves it; a set cut at its limit does not say what ended.
        send(tasks(60, task("t9", "ag-3"), task("t1", "ag-2")))
        assertEquals("t9", agents().getValue("ag-3").taskId)
        assertEquals("running", agents().getValue("ag-4").state)
        // A whole set does: agents without their task are over, a failed call fails its agent.
        send(ev(CloudEventType.TASKS, """{"count":"many","tasks":[${task("t9", "ag-3")}]}"""))
        assertEquals("completed", agents().getValue("ag-2").state)
        assertEquals("failed", agents().getValue("ag-4").state)
        // An agent ended and reported live again runs again.
        send(tasks(1, task("t1", "ag-2")))
        assertEquals("running" to null, agents().getValue("ag-2").let { it.state to it.endedAt })
        send(tasks(0))
        assertTrue(agents().values.none { it.state == "running" })
    }
}
