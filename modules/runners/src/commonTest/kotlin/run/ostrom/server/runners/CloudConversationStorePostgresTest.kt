// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.runners.domain.AnswerContext
import run.ostrom.server.runners.domain.CloudConversationException
import run.ostrom.server.runners.domain.CloudConversationStore
import run.ostrom.server.runners.domain.CloudHumanWait
import run.ostrom.server.runners.domain.CloudInput
import run.ostrom.server.runners.domain.CloudInputKind
import run.ostrom.server.runners.domain.CloudInputStatus
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CloudSessionNotFoundException
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.InputOutcome
import run.ostrom.server.runners.domain.NewInput
import run.ostrom.server.runners.domain.PermissionBypass
import run.ostrom.server.runners.domain.PermissionRequestState
import run.ostrom.server.runners.domain.SessionMode
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.ORG_B
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The conversation's tables on real PostgreSQL (TeamCode's `CloudConversationPersistenceTest`), per organization. */
class CloudConversationStorePostgresTest {
    private val t0 = Instant.parse("2026-11-14T10:00:00Z")

    private suspend fun TestServer.conversation(
        state: SessionState = SessionState.RUNNING,
        desired: DesiredState = DesiredState.RUNNING,
        orgId: Uuid = ORG_A,
    ): CloudSession {
        val created = session(state = state, desired = desired, orgId = orgId, createdAt = t0)
            .copy(mode = SessionMode.CONVERSATION)
        assertTrue(koin.get<CloudSessionsRepository>().createWithinCap(created, 100))
        return created
    }

    private suspend fun TestServer.sql(statement: String) {
        database.superuser.execute(statement).getOrThrow()
    }

    /** An event as the runner channel will append it (slice 3), with the cursor it moves. */
    @Suppress("LongParameterList") // An event's columns, each with a default.
    private suspend fun TestServer.event(
        session: CloudSession,
        seq: Long,
        type: String,
        data: String,
        feed: String = "",
        at: Instant = t0,
    ) {
        val quoted = data.replace("'", "''")
        sql(
            "INSERT INTO runners_session_events (session_id, seq, org_id, epoch, runner_seq, type, at, data, feed) " +
                "VALUES ('${session.id}', $seq, '${session.orgId}', ${session.epoch}, $seq, '$type', '$at', " +
                "'$quoted', '$feed')",
        )
        sql(
            "INSERT INTO runners_session_transcripts (session_id, org_id, last_seq, runner_epoch, runner_seq) " +
                "VALUES ('${session.id}', '${session.orgId}', $seq, 0, 0) " +
                "ON CONFLICT (session_id) DO UPDATE SET last_seq = EXCLUDED.last_seq",
        )
    }

    @Suppress("LongParameterList") // An input's fields, each with a default.
    private fun input(
        session: CloudSession,
        kind: CloudInputKind = CloudInputKind.MESSAGE,
        text: String? = "Hello",
        key: String? = null,
        digest: String? = key,
        at: Instant = t0,
        wake: Boolean = true,
    ) = CloudInput(
        Uuid.random(), session.orgId, session.id, kind, CloudInputStatus.QUEUED, "alice", "c1", text, wake, key,
        digest, at,
    )

    private fun TestServer.store() = koin.get<CloudConversationStore>()

    @Test
    fun eventsArePagedBothWaysAndByFeed() = serverTest(listOf(RunnersModule)) { server ->
        val store = server.store()
        val session = server.conversation()
        assertEquals(0, store.lastSeq(ORG_A, session.id))
        val empty = store.events(ORG_A, session.id, null, null, 10, null)
        assertTrue(empty.events.isEmpty())
        assertNull(empty.firstSeq)
        for (seq in 1L..5L) {
            val feed = if (seq % 2 == 0L) "toolu_1" else ""
            server.event(session, seq, if (seq == 2L) "nonsense" else "status", "{}", feed)
        }
        assertEquals(5, store.lastSeq(ORG_A, session.id))

        val first = store.events(ORG_A, session.id, null, null, 2, null)
        assertEquals(listOf(1L, 2L), first.events.map { it.seq })
        assertEquals(1L to 5L, first.firstSeq to first.lastSeq)
        assertEquals(2L, first.nextAfter)
        assertNull(first.nextBefore)
        assertEquals("toolu_1", first.events[1].feed)
        assertNull(first.events[0].feed)
        val rest = store.events(ORG_A, session.id, 2, null, 10, null)
        assertEquals(listOf(3L, 4L, 5L), rest.events.map { it.seq })
        assertNull(rest.nextAfter)
        assertEquals(3L, rest.nextBefore)

        val back = store.events(ORG_A, session.id, null, 5, 2, null)
        assertEquals(listOf(3L, 4L), back.events.map { it.seq })
        assertEquals(4L, back.nextAfter)
        assertEquals(3L, back.nextBefore)
        val oldest = store.events(ORG_A, session.id, null, 2, 5, null)
        assertEquals(listOf(1L), oldest.events.map { it.seq })
        assertNull(oldest.nextBefore)
        assertTrue(store.events(ORG_A, session.id, null, 1, 5, null).events.isEmpty())

        val main = store.events(ORG_A, session.id, null, null, 10, "main")
        assertEquals(listOf(1L, 3L, 5L), main.events.map { it.seq })
        val sub = store.events(ORG_A, session.id, null, null, 1, "toolu_1")
        assertEquals(listOf(2L), sub.events.map { it.seq })
        assertEquals(2L, sub.nextAfter)
        val subBack = store.events(ORG_A, session.id, null, 5, 10, "toolu_1")
        assertEquals(listOf(2L, 4L), subBack.events.map { it.seq })
        assertNull(subBack.nextAfter, "The newest of the feed")
        val none = store.events(ORG_A, session.id, null, null, 10, "toolu_9")
        assertTrue(none.events.isEmpty())
        assertTrue(store.events(ORG_A, session.id, null, 3, 10, "toolu_9").events.isEmpty())

        assertTrue(store.events(ORG_B, session.id, null, null, 10, null).events.isEmpty(), "Org B sees none")
        assertEquals(0, store.lastSeq(ORG_B, session.id))
    }

    @Test
    fun subagentsAreTheNewestOldestFirst() = serverTest(listOf(RunnersModule)) { server ->
        val store = server.store()
        val session = server.conversation()
        for ((index, id) in listOf("a", "b", "c").withIndex()) {
            server.sql(
                "INSERT INTO runners_session_subagents (session_id, id, org_id, type, description, state, " +
                    "started_at, " +
                    "steps, result_error) VALUES ('${session.id}', '$id', '$ORG_A', 'Explore', 'd', 'running', " +
                    "'${t0 + index.seconds}', 1, false)",
            )
        }
        assertEquals(listOf("b", "c"), store.subagents(ORG_A, session.id, 2).map { it.id })
        assertTrue(store.subagents(ORG_B, session.id, 2).isEmpty())
    }

    @Test
    fun anIdempotentRetryIsReplayedAndAnotherRequestWithTheKeyRefused() = serverTest(listOf(RunnersModule)) { server ->
        val store = server.store()
        val session = server.conversation()
        val first = input(session, key = "k1")
        assertIs<InputOutcome.Accepted>(store.insertInput(NewInput(first, null), 100))
        val replay = store.insertInput(NewInput(input(session, key = "k1"), null), 100)
        assertIs<InputOutcome.Replayed>(replay)
        assertEquals(first.id, replay.input.id)
        val conflict = assertFailsWith<CloudConversationException> {
            store.insertInput(NewInput(input(session, key = "k1", digest = "other"), null), 100)
        }
        assertEquals("idempotency_conflict", conflict.type)
        assertEquals(
            "idempotency_conflict",
            assertFailsWith<CloudConversationException> {
                store.insertInput(NewInput(input(session, CloudInputKind.INTERRUPT, null, "k1"), null), 100)
            }.type,
        )
        assertEquals(first, store.input(ORG_A, session.id, first.id))
        server.sql("UPDATE runners_sessions SET state = 'terminated' WHERE id = '${session.id}'")
        val late = store.insertInput(NewInput(input(session, key = "k1"), null), 100)
        assertIs<InputOutcome.Replayed>(late, "A retry is its first answer, even once the session ended")
        assertNull(store.input(ORG_B, session.id, first.id), "Org B sees none")
        assertFailsWith<CloudSessionNotFoundException> {
            store.insertInput(NewInput(input(session).copy(orgId = ORG_B), null), 100)
        }
    }

    @Test
    fun anInputIsRefusedOnAnEndedSessionAStaleViewOrPastTheBound() = serverTest(listOf(RunnersModule)) { server ->
        val store = server.store()
        val ended = server.conversation(SessionState.TERMINATED)
        val deleting = server.conversation(desired = DesiredState.TERMINATED)
        for (session in listOf(ended, deleting)) {
            val refused = assertFailsWith<CloudConversationException> {
                store.insertInput(NewInput(input(session), null), 5)
            }
            assertEquals("session_not_running", refused.type)
        }
        val session = server.conversation()
        server.event(session, 3, "status", "{}")
        store.insertInput(NewInput(input(session), 3), 2)
        val stale = assertFailsWith<CloudConversationException> { store.insertInput(NewInput(input(session), 2), 2) }
        assertEquals("stale_view", stale.type)
        store.insertInput(NewInput(input(session), null), 2)
        val full = assertFailsWith<CloudConversationException> { store.insertInput(NewInput(input(session), null), 2) }
        assertEquals("too_many_pending_inputs", full.type)
    }

    @Suppress("LongMethod") // One transcript, every way a request closes.
    @Test
    fun anAnswerSeesItsRequestAndWhatClosedIt() = serverTest(listOf(RunnersModule)) { server ->
        val store = server.store()
        val session = server.conversation()
        val seen = mutableListOf<AnswerContext>()
        suspend fun answer(requestId: String, check: (AnswerContext) -> Unit = { seen += it }) = store.insertInput(
            NewInput(
                input(session, CloudInputKind.PERMISSION, """{"requestId":"$requestId","behavior":"deny"}"""),
                null,
                requestId,
                check,
            ),
            100,
        )
        val bash = """{"requestId":"r1","toolName":"Bash","input":{"command":"ls"}}"""
        server.event(session, 1, "permission.request", bash)
        server.event(
            session,
            2,
            "permission.request",
            """{"requestId":"q1","question":true,"input":{"questions":[{"question":"Which?","options":[{},{}]},""" +
                """{"options":[{}],"multiSelect":true},"nonsense"]}}""",
        )
        server.event(session, 3, "permission.request", """{"requestId":"r10","inputTruncated":true}""")
        server.event(session, 4, "permission.request", """{"requestId":"r1x"}""")
        server.event(session, 5, "permission.request", "not json")

        answer("r1")
        val tool = assertNotNull(seen.single().request)
        assertFalse(tool.question)
        assertFalse(tool.inputLeftOut)
        assertEquals(PermissionRequestState.OPEN, tool.state)
        assertEquals(SessionState.RUNNING to DesiredState.RUNNING, seen.single().state to seen.single().desiredState)
        val again = assertFailsWith<CloudConversationException> { answer("r1") }
        assertEquals("permission_request_answered", again.type, "One answer on its way at a time")

        answer("q1")
        val question = assertNotNull(seen.last().request)
        assertTrue(question.question)
        assertEquals(3, question.questions?.size)
        assertEquals(2, question.questions?.first()?.options)
        assertEquals("Which?", question.questions?.first()?.text)
        assertTrue(question.questions.orEmpty()[1].multiSelect)
        assertEquals(0, question.questions?.get(2)?.options)

        answer("r10")
        assertTrue(assertNotNull(seen.last().request).inputLeftOut)
        answer("r404")
        assertNull(seen.last().request)
        server.event(session, 16, "permission.request", "{\"requestId\":\"broken\",")
        answer("broken")
        assertNull(seen.last().request, "Not JSON: no request")

        // What closes a request, the oldest first.
        server.event(session, 6, "permission.request", """{"requestId":"r2"}""")
        server.event(session, 7, "permission.expired", """{"requestId":"other"}""")
        server.event(session, 8, "permission.expired", """{"requestId":"r2"}""")
        server.event(session, 9, "turn", """{"phase":"completed"}""")
        answer("r2")
        assertEquals(PermissionRequestState.EXPIRED, seen.last().request?.state)
        server.event(session, 10, "permission.request", """{"requestId":"r3"}""")
        server.event(session, 11, "turn", """{"phase":"started"}""")
        server.event(session, 12, "input", """{"requestId":"r3","status":"rejected"}""")
        server.event(session, 13, "input", """{"requestId":"r3","status":"consumed"}""")
        answer("r3")
        assertEquals(PermissionRequestState.ANSWERED, seen.last().request?.state)
        server.event(session, 14, "permission.request", """{"requestId":"r4"}""")
        server.event(session, 15, "turn", """{"phase":"interrupted"}""")
        answer("r4")
        assertEquals(PermissionRequestState.ENDED, seen.last().request?.state)

        // A check that refuses leaves nothing behind.
        val refused = assertFailsWith<CloudConversationException> {
            answer("r9") { throw CloudConversationException(ErrorKind.CONFLICT, "no", "No.") }
        }
        assertEquals("no", refused.type)
        // Answers have their own small bound, apart from messages.
        repeat(16 - store.inputs(ORG_A, session.id, null, 100).inputs.size) { answer("f$it") }
        val full = assertFailsWith<CloudConversationException> { answer("f99") }
        assertEquals("too_many_pending_inputs", full.type)
        store.insertInput(NewInput(input(session), null), 100)
    }

    @Test
    fun inputsArePagedNewestFirst() = serverTest(listOf(RunnersModule)) { server ->
        val store = server.store()
        val session = server.conversation()
        val sent = (0..3).map { input(session, at = t0 + (it / 2).seconds) }
        for (one in sent) store.insertInput(NewInput(one, null), 100)
        val first = store.inputs(ORG_A, session.id, null, 3)
        assertEquals(3, first.inputs.size)
        val next = assertNotNull(first.nextBefore)
        val rest = store.inputs(ORG_A, session.id, next, 3)
        assertEquals(1, rest.inputs.size)
        assertNull(rest.nextBefore)
        assertEquals(sent.map { it.id }.toSet(), (first.inputs + rest.inputs).map { it.id }.toSet())
        val unknown = assertFailsWith<CloudConversationException> { store.inputs(ORG_A, session.id, Uuid.random(), 3) }
        assertEquals("invalid_cursor", unknown.type)
        assertTrue(store.inputs(ORG_B, session.id, null, 3).inputs.isEmpty())
    }

    @Test
    fun aMessageToASleepingSessionWaitsToWakeIt() = serverTest(listOf(RunnersModule)) { server ->
        val store = server.store()
        val paused = server.conversation(SessionState.PAUSED, DesiredState.PAUSED)
        val pausing = server.conversation(SessionState.PAUSING, DesiredState.PAUSED)
        val stopping = server.conversation(SessionState.PAUSING)
        val asleep = server.conversation(SessionState.PAUSED)
        val running = server.conversation()
        val elsewhere = server.conversation(SessionState.PAUSED, DesiredState.PAUSED, ORG_B)
        for (session in listOf(paused, pausing, stopping, asleep, running, elsewhere)) {
            store.insertInput(NewInput(input(session), null), 100)
        }
        store.insertInput(NewInput(input(paused, wake = false), null), 100)
        store.insertInput(NewInput(input(pausing, CloudInputKind.INTERRUPT, null), null), 100)
        val wakes = store.pendingWakes(100)
        assertEquals(
            setOf(paused.id, pausing.id, stopping.id, asleep.id, elsewhere.id),
            wakes.map { it.sessionId }.toSet(),
            "Every organization's, once per session",
        )
        assertEquals(ORG_B, wakes.single { it.sessionId == elsewhere.id }.orgId)
        store.clearWake(ORG_A, paused.id)
        store.clearWake(ORG_B, pausing.id)
        assertEquals(
            setOf(pausing.id, stopping.id, asleep.id, elsewhere.id),
            store.pendingWakes(100).map { it.sessionId }.toSet(),
            "Cleared within its organization only",
        )
    }

    @Test
    fun theInputsOfAnEndedSessionAreCancelledAndOldTranscriptsPurged() = serverTest(listOf(RunnersModule)) { server ->
        val store = server.store()
        val session = server.conversation()
        val message = input(session)
        val answer = input(session, CloudInputKind.PERMISSION, """{"requestId":"r1","behavior":"allow"}""")
        val odd = input(session, CloudInputKind.PERMISSION, "not json")
        for (one in listOf(message, answer, odd)) store.insertInput(NewInput(one, null), 100)
        val other = server.conversation(orgId = ORG_B)
        store.insertInput(NewInput(input(other), null), 100)
        server.event(session, 1, "status", "{}", at = t0 - 40.days)
        server.sql("UPDATE runners_sessions SET state = 'terminated' WHERE id IN ('${session.id}', '${other.id}')")

        assertEquals(4, store.cancelEnded(100, t0))
        assertEquals(0, store.cancelEnded(100, t0), "Once")
        val cancelled = assertNotNull(store.input(ORG_A, session.id, message.id))
        assertEquals(CloudInputStatus.CANCELLED, cancelled.status)
        assertEquals("session_ended", cancelled.reason)
        assertEquals(t0, cancelled.finishedAt)
        val events = store.events(ORG_A, session.id, 1, null, 10, null).events
        assertEquals(listOf(2L, 3L, 4L), events.map { it.seq }, "One input event each, after the runner's")
        assertTrue(events.all { it.epoch == 0L && it.type.wire == "input" })
        val allowed = events.single { it.data.contains("\"behavior\":\"allow\"") }
        assertTrue(allowed.data.contains("\"authorId\":\"alice\""))
        assertEquals(4, store.lastSeq(ORG_A, session.id))
        assertEquals(1L, store.events(ORG_B, other.id, null, null, 10, null).lastSeq, "A cursor of its own")

        // Past the retention: the runner's 40-day-old event; later, every event and finished input of both.
        assertEquals(1, store.purge(t0 - 30.days, 100))
        assertEquals(4, store.lastSeq(ORG_A, session.id), "The cursor stays")
        assertEquals(8, store.purge(t0 + 1.seconds, 100))
        assertNull(store.input(ORG_A, session.id, message.id))
    }

    @Test
    fun theOwnersOptionsAreWrittenUnlessTheSessionEnded() = serverTest(listOf(RunnersModule)) { server ->
        val store = server.store()
        val repository = server.koin.get<CloudSessionsRepository>()
        val session = server.conversation()
        val bypass = PermissionBypass("alice", "c1", t0)
        assertTrue(store.setPermissionBypass(ORG_A, session.id, bypass, t0 + 1.seconds))
        assertTrue(store.setAgentOption(ORG_A, session.id, false, "opus", t0 + 2.seconds))
        assertTrue(store.setAgentOption(ORG_A, session.id, true, "high", t0 + 3.seconds))
        val changed = assertNotNull(repository.get(ORG_A, session.id))
        assertEquals(bypass, changed.permissionBypass)
        assertEquals("opus" to "high", changed.model to changed.effort)
        assertEquals(3, changed.generation)
        assertEquals(t0 + 3.seconds, changed.updatedAt)
        assertTrue(store.setPermissionBypass(ORG_A, session.id, null, t0 + 4.seconds))
        assertNull(repository.get(ORG_A, session.id)?.permissionBypass)
        assertFalse(store.setAgentOption(ORG_B, session.id, false, "x", t0), "Not across organizations")
        val ended = server.conversation(SessionState.FAILED)
        assertFalse(store.setAgentOption(ORG_A, ended.id, false, "x", t0))
        val deleting = server.conversation(desired = DesiredState.TERMINATED)
        assertFalse(store.setPermissionBypass(ORG_A, deleting.id, bypass, t0))

        server.sql(
            "UPDATE runners_sessions SET waiting_reason = 'question', waiting_request_id = 'q1', " +
                "waiting_since = '$t0', waiting_event_seq = 7, waiting_epoch = 2, mode = 'later' " +
                "WHERE id = '${session.id}'",
        )
        val waiting = assertNotNull(repository.get(ORG_A, session.id))
        assertEquals(CloudHumanWait("question", "q1", t0, 7, 2), waiting.waiting)
        assertEquals(SessionMode.TERMINAL, waiting.mode, "A mode this version does not know reads as a terminal")
    }
}
