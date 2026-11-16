// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.BackgroundWork
import run.ostrom.server.runners.domain.BackgroundWorkLimit
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.MemberSessionCaps
import run.ostrom.server.runners.domain.ReconcileCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.RepositoryCloudUsageMeter
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.FakeRunnerTokens
import run.ostrom.server.runners.fakes.FakeRuntimeDriver
import run.ostrom.server.runners.fakes.FixedPlans
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** The per-member running cap (TeamCode's `waiting_capacity`): a start past it waits, the owner's oldest first. */
class ReconcileCapacityTest {
    private val repository = InMemoryCloudSessionsRepository()
    private val driver = FakeRuntimeDriver()
    private val clock = SettableClock()
    private val t0 = clock.now
    private var running = 1

    private val caps = object : MemberSessionCaps {
        override suspend fun runningLimit(): Int = running

        override suspend fun retainedLimit(): Int = Int.MAX_VALUE
    }

    private val reconcile = ReconcileCloudSessionUseCaseImpl(
        repository,
        driver,
        FakeRunnerTokens(),
        FixedPlans(),
        RepositoryCloudUsageMeter(repository),
        clock,
        backgroundWork = BackgroundWorkLimit { BackgroundWork.DEFAULT_MAX_MINUTES },
        caps = caps,
    )

    private fun put(session: CloudSession) = session.also { repository.put(it) }

    private fun stored(session: CloudSession) = repository.sessions.getValue(session.id)

    private fun executing(owner: String = "alice") = put(
        session(state = SessionState.RUNNING, ownerId = owner)
            .copy(epoch = 1, executionStartedAt = t0, readyAt = t0),
    )

    private fun pause(session: CloudSession) = put(
        stored(session).copy(
            state = SessionState.PAUSED,
            desiredState = DesiredState.PAUSED,
            executionStartedAt = null,
        ),
    )

    @Test
    fun aStartPastTheOwnersCapWaitsThenStartsOnceASessionPauses() = runTest {
        val busy = executing()
        val created = put(session())

        assertEquals(t0 + 5.seconds, reconcile(created))
        val waiting = stored(created)
        assertEquals(CloudSession.WAITING_CAPACITY to t0, waiting.phase to waiting.capacityWaitingSince)
        assertEquals(0L, waiting.epoch)
        assertEquals(emptyList(), driver.started)
        // Looked at again, it keeps its place.
        clock.now = t0 + 5.seconds
        assertEquals(t0 + 10.seconds, reconcile(stored(created)))
        assertEquals(t0, stored(created).capacityWaitingSince)

        pause(busy)
        reconcile(stored(created))
        val started = stored(created)
        assertEquals(1L to "starting_runner", started.epoch to started.phase)
        assertNull(started.capacityWaitingSince)
        assertEquals(listOf(created.id), driver.started.map { it.sessionId })
    }

    @Test
    fun theOwnersOldestWaitingStartGoesFirst() = runTest {
        val busy = executing()
        val older = put(session())
        reconcile(older)
        clock.now = t0 + 1.seconds
        val newer = put(session())
        reconcile(newer)
        assertEquals(t0 + 1.seconds, stored(newer).capacityWaitingSince)

        pause(busy)
        clock.now = t0 + 5.seconds
        // The newer one is looked at first, yet the older one holds the place.
        reconcile(stored(newer))
        assertEquals(0L, stored(newer).epoch)
        reconcile(stored(older))
        assertEquals(1L, stored(older).epoch)
        // Now the owner is at the cap again: the newer one still waits.
        reconcile(stored(newer))
        assertEquals(CloudSession.WAITING_CAPACITY to 0L, stored(newer).let { it.phase to it.epoch })
    }

    @Test
    fun anotherMembersSessionsAnAutomationsAndNoLimitNeverHoldAStart() = runTest {
        executing(owner = "bob")
        put(executing().copy(automationId = Uuid.random()))
        val mine = put(session())
        reconcile(mine)
        assertEquals(1L, stored(mine).epoch)
        // The automation's own start has the automation cap, not the member's.
        val run = put(session().copy(automationId = Uuid.random()))
        reconcile(run)
        assertEquals(1L, stored(run).epoch)
        // 0 in the console: no limit.
        running = Int.MAX_VALUE
        val another = put(session())
        reconcile(another)
        assertEquals(1L, stored(another).epoch)
    }

    @Test
    fun aResumeWaitsTooAndAPauseLeavesTheQueue() = runTest {
        executing()
        val paused = put(session(state = SessionState.PAUSED, desired = DesiredState.RUNNING))
        reconcile(paused)
        assertEquals(SessionState.RESUMING, stored(paused).state)
        reconcile(stored(paused))
        assertEquals(CloudSession.WAITING_CAPACITY to t0, stored(paused).let { it.phase to it.capacityWaitingSince })

        put(stored(paused).copy(desiredState = DesiredState.PAUSED))
        reconcile(stored(paused))
        assertEquals(SessionState.PAUSED, stored(paused).state)
        assertNull(stored(paused).capacityWaitingSince)
    }
}
