// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners

import dev.ostrom.server.runners.domain.AuthenticateRunnerUseCaseImpl
import dev.ostrom.server.runners.domain.Caller
import dev.ostrom.server.runners.domain.CloudSessionNotFoundException
import dev.ostrom.server.runners.domain.CloudSessionStateException
import dev.ostrom.server.runners.domain.DesiredState
import dev.ostrom.server.runners.domain.ExecutionFencedException
import dev.ostrom.server.runners.domain.IssueTerminalTicketUseCaseImpl
import dev.ostrom.server.runners.domain.OpenTerminalUseCaseImpl
import dev.ostrom.server.runners.domain.RecordCloudSessionActivityUseCase
import dev.ostrom.server.runners.domain.RunnerUnauthenticatedException
import dev.ostrom.server.runners.domain.SessionState
import dev.ostrom.server.runners.domain.TerminalActivity
import dev.ostrom.server.runners.fakes.FakeRunnerTokens
import dev.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import dev.ostrom.server.runners.fakes.ORG_A
import dev.ostrom.server.runners.fakes.ORG_B
import dev.ostrom.server.runners.fakes.SettableClock
import dev.ostrom.server.runners.fakes.session
import dev.ostrom.server.runners.infrastructure.InMemoryTerminalTickets
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun aTicketOpensOneSessionOnceBeforeItExpires() = runTest {
        val tickets = InMemoryTerminalTickets(tokens, clock)
        val issue = IssueTerminalTicketUseCaseImpl(OpenTerminalUseCaseImpl(repository), tickets)
        val live = session(state = SessionState.RUNNING).also { repository.put(it) }
        assertFailsWith<CloudSessionNotFoundException> { issue(Caller(ORG_B, "carol"), live.id) }

        val ticket = issue(alice, live.id)
        assertTrue(ticket.value.startsWith("ostrom_term_"))
        assertEquals(clock.now + 60.seconds, ticket.expiresAt)
        assertNull(tickets.redeem("ostrom_term_unknown", live.id))
        assertEquals(alice, tickets.redeem(ticket.value, live.id))
        assertNull(tickets.redeem(ticket.value, live.id))

        val other = issue(alice, live.id)
        assertNull(tickets.redeem(other.value, Uuid.random()))
        assertNull(tickets.redeem(other.value, live.id))

        val late = issue(alice, live.id)
        clock.now += 61.seconds
        assertNull(tickets.redeem(late.value, live.id))
        val kept = issue(alice, live.id)
        // Expired tickets are purged at the next issue, live ones kept.
        issue(alice, live.id)
        assertEquals(alice, tickets.redeem(kept.value, live.id))
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
