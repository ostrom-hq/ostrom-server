// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.AuthenticateRunnerUseCaseImpl
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CloudSessionNotFoundException
import run.ostrom.server.runners.domain.CloudSessionStateException
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.ExecutionFencedException
import run.ostrom.server.runners.domain.IssueTerminalTicketUseCaseImpl
import run.ostrom.server.runners.domain.OpenTerminalUseCaseImpl
import run.ostrom.server.runners.domain.RecordCloudSessionActivityUseCase
import run.ostrom.server.runners.domain.RunnerUnauthenticatedException
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.domain.TerminalActivity
import run.ostrom.server.runners.domain.TerminalTicket
import run.ostrom.server.runners.domain.TerminalTickets
import run.ostrom.server.runners.fakes.FakeRunnerTokens
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.ORG_B
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class TerminalUseCasesTest {
    private val repository = InMemoryCloudSessionsRepository()
    private val tokens = FakeRunnerTokens()
    private val clock = SettableClock()
    private val alice = Caller(ORG_A, "alice")

    @Test
    fun aRunnerIsItsTokensCurrentExecution() = runTest {
        val running = session(
            state = SessionState.RUNNING,
        ).copy(epoch = 3, runnerTokenHash = "hash(tok)").also { repository.put(it) }
        val authenticate = AuthenticateRunnerUseCaseImpl(repository, tokens)

        assertEquals(running, authenticate("tok", running.id, 3))
        assertFailsWith<RunnerUnauthenticatedException> { authenticate("other", running.id, 3) }
        assertFailsWith<ExecutionFencedException> { authenticate("tok", running.id, 2) }
        assertFailsWith<ExecutionFencedException> { authenticate("tok", Uuid.random(), 3) }
    }

    @Test
    fun onlyTheOwnerOpensALiveSessionsTerminal() = runTest {
        val open = OpenTerminalUseCaseImpl(repository)
        val live = session(state = SessionState.RUNNING).also { repository.put(it) }
        assertEquals(live, open(alice, live.id))
        assertFailsWith<CloudSessionNotFoundException> { open(Caller(ORG_A, "bob"), live.id) }
        assertFailsWith<CloudSessionNotFoundException> { open(Caller(ORG_B, "alice"), live.id) }

        val over = session(
            state = SessionState.TERMINATED,
            desired = DesiredState.TERMINATED,
        ).also { repository.put(it) }
        assertFailsWith<CloudSessionStateException> { open(alice, over.id) }
        val deleting = session(
            state = SessionState.RUNNING,
            desired = DesiredState.TERMINATED,
        ).also { repository.put(it) }
        assertFailsWith<CloudSessionStateException> { open(alice, deleting.id) }
        val failed = session(state = SessionState.FAILED).also { repository.put(it) }
        assertFailsWith<CloudSessionStateException> { open(alice, failed.id) }
    }

    @Test
    fun aTicketIsIssuedOnlyToWhoMayAttach() = runTest {
        val issued = mutableListOf<Pair<Caller, Uuid>>()
        val tickets = object : TerminalTickets {
            override suspend fun issue(caller: Caller, sessionId: Uuid): TerminalTicket {
                issued += caller to sessionId
                return TerminalTicket("ostrom_term_x", clock.now + 60.seconds)
            }

            override suspend fun redeem(value: String, sessionId: Uuid): Caller? = null
        }
        val issue = IssueTerminalTicketUseCaseImpl(OpenTerminalUseCaseImpl(repository), tickets)
        val live = session(state = SessionState.RUNNING).also { repository.put(it) }
        assertFailsWith<CloudSessionNotFoundException> { issue(Caller(ORG_B, "carol"), live.id) }

        assertEquals("ostrom_term_x", issue(alice, live.id).value)
        assertEquals(listOf(alice to live.id), issued)
    }

    @Test
    fun terminalInputIsRecordedAtMostEveryThirtySeconds() = runTest {
        val recorded = mutableListOf<Uuid>()
        val activity = TerminalActivity(
            object : RecordCloudSessionActivityUseCase {
                override suspend fun invoke(sessionId: Uuid) {
                    recorded += sessionId
                }
            },
            clock,
        )
        val first = Uuid.random()
        val second = Uuid.random()

        assertTrue(activity.touch(first))
        assertFalse(activity.touch(first))
        assertTrue(activity.touch(second))
        clock.now += 30.seconds
        assertTrue(activity.touch(first))
        assertEquals(listOf(first, second, first), recorded)
    }

    @Test
    fun beyondItsLimitTheActivityForgetsQuietSessions() = runTest {
        var records = 0
        val activity = TerminalActivity(
            object : RecordCloudSessionActivityUseCase {
                override suspend fun invoke(sessionId: Uuid) {
                    records++
                }
            },
            clock,
            maxTracked = 1,
        )
        val quiet = Uuid.random()
        val busy = Uuid.random()
        assertTrue(activity.touch(quiet))
        clock.now += 30.seconds
        // Two sessions are over the limit: the quiet one is forgotten, the busy one kept.
        assertTrue(activity.touch(busy))
        assertFalse(activity.touch(busy))
        assertEquals(2, records)
    }
}
