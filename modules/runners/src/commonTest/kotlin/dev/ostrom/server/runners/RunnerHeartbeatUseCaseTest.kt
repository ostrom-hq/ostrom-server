// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners

import dev.ostrom.server.runners.domain.CheckRunnerTokenUseCaseImpl
import dev.ostrom.server.runners.domain.DesiredState
import dev.ostrom.server.runners.domain.ExecutionFencedException
import dev.ostrom.server.runners.domain.RecordCloudSessionActivityUseCaseImpl
import dev.ostrom.server.runners.domain.RunnerHeartbeat
import dev.ostrom.server.runners.domain.RunnerHeartbeatUseCaseImpl
import dev.ostrom.server.runners.domain.RunnerInstruction
import dev.ostrom.server.runners.domain.RunnerUnauthenticatedException
import dev.ostrom.server.runners.domain.SessionState
import dev.ostrom.server.runners.fakes.FakeRunnerTokens
import dev.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import dev.ostrom.server.runners.fakes.ORG_A
import dev.ostrom.server.runners.fakes.SettableClock
import dev.ostrom.server.runners.fakes.session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class RunnerHeartbeatUseCaseTest {
    private val repository = InMemoryCloudSessionsRepository()
    private val clock = SettableClock()
    private val heartbeat = RunnerHeartbeatUseCaseImpl(repository, FakeRunnerTokens(), clock)
    private val startedAt = clock.now

    private fun running(state: SessionState = SessionState.CREATING, desired: DesiredState = DesiredState.RUNNING) =
        session(
            state = state,
            desired = desired,
        ).copy(epoch = 3, runnerTokenHash = "hash(tok)", executionStartedAt = startedAt)
            .also { repository.put(it) }

    private fun beat(
        id: Uuid,
        epoch: Long = 3,
        ready: Boolean = false,
        stopped: Boolean = false,
        model: kotlin.time.Instant? = null,
    ) =
        RunnerHeartbeat(id, epoch, ready, stopped, model)

    @Test
    fun aRunnerIsAuthenticatedByTheTokenOfACurrentExecution() = runTest {
        running()
        val check = CheckRunnerTokenUseCaseImpl(repository, FakeRunnerTokens())
        check("tok")
        assertFailsWith<RunnerUnauthenticatedException> { check("forged") }
    }

    @Test
    fun aReadyHeartbeatMakesTheSessionRunning() = runTest {
        val session = running()
        clock.now += 20.seconds

        val answer = heartbeat("tok", beat(session.id, ready = true))

        assertEquals(RunnerInstruction.RUN, answer.instruction)
        assertEquals(SessionState.RUNNING, answer.state)
        val stored = repository.sessions.getValue(session.id)
        assertEquals(clock.now, stored.readyAt)
        assertEquals(clock.now, stored.lastActivityAt)
        assertEquals(clock.now, stored.lastHeartbeatAt)
        assertNull(stored.phase)
        assertEquals(clock.now, repository.next[session.id])
        // 20 s since the execution started.
        assertEquals(20, repository.usage[ORG_A to "2026-10"])
    }

    @Test
    fun aFirstHeartbeatWithoutAStartTimeBillsNothing() = runTest {
        val session = running().copy(executionStartedAt = null).also { repository.put(it) }
        clock.now += 20.seconds
        heartbeat("tok", beat(session.id))
        assertNull(repository.usage[ORG_A to "2026-10"])
    }

    @Test
    fun aResumingSessionBecomesRunningToo() = runTest {
        val session = running(state = SessionState.RESUMING)
        assertEquals(SessionState.RUNNING, heartbeat("tok", beat(session.id, ready = true)).state)
    }

    @Test
    fun aHeartbeatBeforeReadyKeepsTheSessionStartingAndBillsNothingAtOnce() = runTest {
        val session = running()

        val answer = heartbeat("tok", beat(session.id))

        assertEquals(SessionState.CREATING, answer.state)
        assertEquals(RunnerInstruction.RUN, answer.instruction)
        assertNull(repository.usage[ORG_A to "2026-10"])
    }

    @Test
    fun usageCountsTheGapBetweenHeartbeatsCappedAtAMinute() = runTest {
        val session = running(
            state = SessionState.RUNNING,
        ).copy(lastHeartbeatAt = startedAt).also { repository.put(it) }
        clock.now += 15.seconds
        heartbeat("tok", beat(session.id))
        clock.now += 10.minutes
        heartbeat("tok", beat(session.id))

        assertEquals(75, repository.usage[ORG_A to "2026-10"])
    }

    @Test
    fun modelRequestsMoveTheActivityButNeverIntoTheFuture() = runTest {
        val session = running(state = SessionState.RUNNING).copy(lastActivityAt = startedAt).also { repository.put(it) }
        clock.now += 5.minutes

        heartbeat("tok", beat(session.id, model = startedAt + 2.minutes))
        assertEquals(startedAt + 2.minutes, repository.sessions.getValue(session.id).lastActivityAt)
        heartbeat("tok", beat(session.id, model = clock.now + 1.minutes))
        assertEquals(startedAt + 2.minutes, repository.sessions.getValue(session.id).lastActivityAt)
    }

    @Test
    fun aPausingSessionTellsTheRunnerToStopAndRecordsTheStop() = runTest {
        val session = running(state = SessionState.PAUSING, desired = DesiredState.PAUSED)

        assertEquals(RunnerInstruction.STOP, heartbeat("tok", beat(session.id)).instruction)
        assertNull(repository.sessions.getValue(session.id).runnerStoppedAt)
        clock.now += 3.seconds
        heartbeat("tok", beat(session.id, stopped = true))
        assertEquals(clock.now, repository.sessions.getValue(session.id).runnerStoppedAt)
        val first = clock.now
        clock.now += 3.seconds
        heartbeat("tok", beat(session.id, stopped = true))
        assertEquals(first, repository.sessions.getValue(session.id).runnerStoppedAt)
    }

    @Test
    fun aStoppedReportOutsideAPauseIsIgnored() = runTest {
        val session = running(state = SessionState.RUNNING)
        heartbeat("tok", beat(session.id, stopped = true))
        assertNull(repository.sessions.getValue(session.id).runnerStoppedAt)
    }

    @Test
    fun aRunningSessionWhoseOwnerAskedForAPauseIsToldToStop() = runTest {
        val session = running(state = SessionState.RUNNING, desired = DesiredState.PAUSED)
        assertEquals(RunnerInstruction.STOP, heartbeat("tok", beat(session.id)).instruction)
    }

    @Test
    fun anUnknownTokenIsUnauthenticated() = runTest {
        val session = running()
        assertFailsWith<RunnerUnauthenticatedException> { heartbeat("other", beat(session.id)) }
    }

    @Test
    fun anotherSessionOrEpochIsFenced() = runTest {
        val session = running()
        assertFailsWith<ExecutionFencedException> { heartbeat("tok", beat(Uuid.random())) }
        assertFailsWith<ExecutionFencedException> { heartbeat("tok", beat(session.id, epoch = 2)) }
    }

    @Test
    fun aLostRaceIsRetriedThenFenced() = runTest {
        val session = running()
        repository.loseNextRaces = 1
        assertEquals(SessionState.RUNNING, heartbeat("tok", beat(session.id, ready = true)).state)

        val other = running(
            state = SessionState.RUNNING,
        ).copy(id = Uuid.random(), runnerTokenHash = "hash(tok2)").also { repository.put(it) }
        repository.loseNextRaces = 3
        assertFailsWith<ExecutionFencedException> { heartbeat("tok2", beat(other.id)) }
    }

    @Test
    fun recordActivityMovesTheActivityForward() = runTest {
        val session = running(state = SessionState.RUNNING).copy(lastActivityAt = startedAt).also { repository.put(it) }
        clock.now += 1.minutes

        RecordCloudSessionActivityUseCaseImpl(repository, clock)(session.id)

        assertEquals(clock.now, repository.sessions.getValue(session.id).lastActivityAt)
    }
}
