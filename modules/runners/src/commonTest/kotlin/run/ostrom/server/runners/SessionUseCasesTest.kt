// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners

import run.ostrom.server.kernel.errors.InvalidPageException
import run.ostrom.server.runners.domain.AgentKind
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.ChangeCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.CloudHoursExhaustedException
import run.ostrom.server.runners.domain.CloudPlan
import run.ostrom.server.runners.domain.CloudSessionLimitException
import run.ostrom.server.runners.domain.CloudSessionNotFoundException
import run.ostrom.server.runners.domain.CloudSessionStateException
import run.ostrom.server.runners.domain.CloudSessionsClosedException
import run.ostrom.server.runners.domain.CreateCloudSessionPayload
import run.ostrom.server.runners.domain.CreateCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.FreeCloudPlanProvider
import run.ostrom.server.runners.domain.GetCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.InvalidCloudSessionRequestException
import run.ostrom.server.runners.domain.ListCloudSessionsUseCaseImpl
import run.ostrom.server.runners.domain.RepositoryCloudUsageMeter
import run.ostrom.server.runners.domain.RepositorySpec
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.FakeRuntimeDriver
import run.ostrom.server.runners.fakes.FixedPlans
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.ORG_B
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.presentation.toResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SessionUseCasesTest {
    private val alice = Caller(ORG_A, "alice")
    private val repository = InMemoryCloudSessionsRepository()
    private val plans = FixedPlans()
    private val clock = SettableClock()
    private val create = CreateCloudSessionUseCaseImpl(repository, plans, RepositoryCloudUsageMeter(repository), clock)
    private val change = ChangeCloudSessionUseCaseImpl(repository, plans, RepositoryCloudUsageMeter(repository), clock)

    private fun payload(
        name: String = "Fix the relist retry",
        agent: String = "claude",
        repository: RepositorySpec? = null,
    ) =
        CreateCloudSessionPayload(name, agent, repository)

    @Test
    fun createStoresACreatingSessionWithThePlansLimits() = runTest {
        val created = create(
            alice,
            payload(name = "  Fix it  ", repository = RepositorySpec("https://github.com/acme/api.git", "main")),
        )

        assertEquals(SessionState.CREATING, created.state)
        assertEquals(DesiredState.RUNNING, created.desiredState)
        assertEquals("Fix it", created.name)
        assertEquals(AgentKind.CLAUDE, created.agent)
        assertEquals("alice", created.ownerId)
        assertEquals(ORG_A, created.orgId)
        assertEquals(30, created.limits.idleTimeoutMinutes)
        assertEquals(240, created.limits.maxRunMinutes)
        assertEquals(true, created.limits.restrictedEgress)
        assertEquals(created, repository.sessions[created.id])
        assertEquals(clock.now, repository.next[created.id])
    }

    @Test
    fun aSessionWithoutNameIsNamedAfterItsRepositoryOrAgent() = runTest {
        assertEquals(
            "api",
            create(
                alice,
                CreateCloudSessionPayload(null, "codex", RepositorySpec("https://github.com/acme/api.git", null)),
            ).name,
        )
        assertEquals("codex", create(alice, CreateCloudSessionPayload("  ", "codex", null)).name)
        assertEquals("codex", create(alice, CreateCloudSessionPayload(null, "codex", null)).name)
    }

    @Test
    fun aSessionKeepsTheIdItsServerCallerChose() = runTest {
        val id = Uuid.parse("0f8a2d3c-1111-4222-8333-444455556666")
        val created = create(alice, CreateCloudSessionPayload("x", "claude", null, id = id))
        assertEquals(id, created.id)
        assertEquals(id, repository.get(created.orgId, id)?.id)
    }

    @Test
    fun theModelAndPromptAreKeptWithinBounds() = runTest {
        val created = create(
            alice,
            CreateCloudSessionPayload("x", "claude", null, model = " claude-opus-5-5 ", prompt = "Fix the tests"),
        )
        assertEquals("claude-opus-5-5", created.model)
        assertEquals("Fix the tests", created.prompt)
        val bare = create(alice, CreateCloudSessionPayload("x", "claude", null, model = "", prompt = "  "))
        assertEquals(null, bare.model)
        assertEquals(null, bare.prompt)
        assertFailsWith<InvalidCloudSessionRequestException> { create(
            alice,
            CreateCloudSessionPayload("x", "claude", null, model = "a b"),
        ) }
        assertFailsWith<InvalidCloudSessionRequestException> { create(
            alice,
            CreateCloudSessionPayload("x", "claude", null, prompt = "x".repeat(20_001)),
        ) }
    }

    @Test
    fun createAcceptsARepositoryWithoutRef() = runTest {
        val created = create(alice, payload(repository = RepositorySpec("https://github.com/acme/api", null)))
        assertEquals(RepositorySpec("https://github.com/acme/api", null), created.repository)
    }

    @Test
    fun createRefusesBadNames() = runTest {
        assertFailsWith<InvalidCloudSessionRequestException> { create(alice, payload(name = "x".repeat(121))) }
        assertFailsWith<InvalidCloudSessionRequestException> { create(alice, payload(name = "a\u0000b")) }
        assertEquals(0, repository.sessions.size)
    }

    @Test
    fun createRefusesAnUnknownAgent() = runTest {
        val error = assertFailsWith<InvalidCloudSessionRequestException> { create(alice, payload(agent = "cursor")) }
        assertEquals("Unknown agent cursor: use claude, codex, opencode, gemini, shell.", error.message)
    }

    @Test
    fun createRefusesRepositoriesOutsideGitHubAndBadRefs() = runTest {
        val bad = listOf(
            RepositorySpec("http://github.com/acme/api", null),
            RepositorySpec("https://gitlab.com/acme/api", null),
            RepositorySpec("https://github.com/acme/api/../x", null),
            RepositorySpec("https://github.com/acme/api", "a..b"),
            RepositorySpec("https://github.com/acme/api", "-c core.sshCommand=x"),
            RepositorySpec("https://github.com/acme/api", "--upload-pack"),
            RepositorySpec("https://github.com/acme/api", "main branch"),
        )
        for (repository in bad) {
            assertFailsWith<InvalidCloudSessionRequestException>(
                repository.toString(),
            ) { create(alice, payload(repository = repository)) }
        }
    }

    @Test
    fun createRefusesBeyondTheRunningCap() = runTest {
        repeat(5) { create(alice, payload()) }

        assertFailsWith<CloudSessionLimitException> { create(alice, payload()) }
        // Another organization has its own cap.
        create(Caller(ORG_B, "bob"), payload())
    }

    @Test
    fun pausedAndFinalSessionsDoNotCountTowardsTheCap() = runTest {
        repeat(4) { create(alice, payload()) }
        repository.put(session(state = SessionState.PAUSED))
        repository.put(session(state = SessionState.TERMINATED))
        repository.put(session(state = SessionState.FAILED))

        create(alice, payload())
        assertFailsWith<CloudSessionLimitException> { create(alice, payload()) }
    }

    @Test
    fun createRefusesWhenTheMonthsHoursAreUsedUp() = runTest {
        repository.usage[ORG_A to "2026-10"] = 300 * 3600L

        assertFailsWith<CloudHoursExhaustedException> { create(alice, payload()) }
        // A new month starts again.
        clock.now = Instant.parse("2026-11-01T00:00:00Z")
        create(alice, payload())
    }

    @Test
    fun createHasNoHourLimitOnAnUnlimitedPlan() = runTest {
        plans.plan = CloudPlan.FREE.copy(monthlyHours = null)
        repository.usage[ORG_A to "2026-10"] = 10_000 * 3600L
        create(alice, payload())
    }

    @Test
    fun getReturnsOnlyTheCallersSessions() = runTest {
        val get = GetCloudSessionUseCaseImpl(repository, FakeRuntimeDriver())
        val created = create(alice, payload())

        assertEquals(created, get(alice, created.id))
        assertFailsWith<CloudSessionNotFoundException> { get(Caller(ORG_A, "mallory"), created.id) }
        assertFailsWith<CloudSessionNotFoundException> { get(Caller(ORG_B, "alice"), created.id) }
        assertFailsWith<CloudSessionNotFoundException> { get(alice, Uuid.random()) }
    }

    @Test
    fun getNamesTheRegisteredRunnerTheSessionRunsOn() = runTest {
        // the contract's CloudSession.runnerId: where a member (or `ostrom claude --cloud`) sees its session placed
        val machine = Uuid.random()
        val driver = FakeRuntimeDriver().apply { holder = machine }
        val created = create(alice, payload())

        val read = GetCloudSessionUseCaseImpl(repository, driver)(alice, created.id)
        assertEquals(machine, read.runnerId)
        assertEquals(machine.toString(), read.toResponse().runnerId)
        assertNull(GetCloudSessionUseCaseImpl(repository, FakeRuntimeDriver())(alice, created.id).runnerId)
        for (state in listOf(SessionState.PAUSED, SessionState.TERMINATED, SessionState.FAILED)) {
            repository.sessions[created.id] = repository.sessions.getValue(created.id).copy(state = state)
            val nowhere = GetCloudSessionUseCaseImpl(repository, driver)(alice, created.id)
            assertNull(nowhere.runnerId, "$state runs nowhere")
        }
    }

    @Test
    fun listPagesTheCallersSessionsNewestFirst() = runTest {
        plans.plan = CloudPlan.FREE.copy(maxRunningSessions = 100)
        val list = ListCloudSessionsUseCaseImpl(repository)
        val created = (0 until 3).map { index ->
            clock.now = Instant.parse("2026-10-30T10:00:00Z") + index.minutes
            create(alice, payload(name = "s$index"))
        }
        create(Caller(ORG_A, "bob"), payload())

        assertEquals(listOf("s2", "s1", "s0"), list(alice, null, null).map { it.name })
        assertEquals(listOf("s2", "s1"), list(alice, null, 2).map { it.name })
        assertEquals(listOf("s0"), list(alice, created[1].id, 2).map { it.name })
        assertEquals(emptyList(), list(alice, created[0].id, 2), "After the oldest")
        assertFailsWith<InvalidPageException> { list(alice, Uuid.random(), 2) }
        assertFailsWith<InvalidPageException> { list(Caller(ORG_B, "alice"), created[0].id, 2) }
        assertFailsWith<InvalidCloudSessionRequestException> { list(alice, null, 0) }
        assertFailsWith<InvalidCloudSessionRequestException> { list(alice, null, 51) }
    }

    @Test
    fun pauseSetsTheDesiredStateOnce() = runTest {
        val created = create(alice, payload())

        val paused = change.pause(alice, created.id)
        assertEquals(DesiredState.PAUSED, paused.desiredState)
        // A second pause writes nothing.
        assertSame(paused, change.pause(alice, created.id))
    }

    @Test
    fun pauseAndResumeRefuseAFinishedSession() = runTest {
        val over = session(state = SessionState.TERMINATED, desired = DesiredState.TERMINATED)
        repository.put(over)

        assertFailsWith<CloudSessionStateException> { change.pause(alice, over.id) }
        assertFailsWith<CloudSessionStateException> { change.resume(alice, over.id) }
        val deleting = session(state = SessionState.RUNNING, desired = DesiredState.TERMINATED)
        repository.put(deleting)
        assertFailsWith<CloudSessionStateException> { change.resume(alice, deleting.id) }
    }

    @Test
    fun resumeOfARunningSessionChangesNothing() = runTest {
        val created = create(alice, payload())
        assertSame(repository.sessions[created.id], change.resume(alice, created.id))
    }

    @Test
    fun resumeWithinTheCapSetsTheDesiredState() = runTest {
        val paused = session(state = SessionState.PAUSED, desired = DesiredState.PAUSED)
        repository.put(paused)

        val resumed = change.resume(alice, paused.id)
        assertEquals(DesiredState.RUNNING, resumed.desiredState)
        assertEquals(clock.now, repository.next[paused.id])
    }

    @Test
    fun resumeRefusesBeyondTheCapAndWithoutHours() = runTest {
        val paused = session(state = SessionState.PAUSED, desired = DesiredState.PAUSED)
        repository.put(paused)
        repeat(5) { create(alice, payload()) }

        assertFailsWith<CloudSessionLimitException> { change.resume(alice, paused.id) }
        repository.usage[ORG_A to "2026-10"] = 300 * 3600L
        assertFailsWith<CloudHoursExhaustedException> { change.resume(alice, paused.id) }
        plans.plan = CloudPlan.FREE.copy(available = false)
        assertFailsWith<CloudSessionsClosedException> { change.resume(alice, paused.id) }
        assertFailsWith<CloudSessionsClosedException> { create(alice, payload()) }
    }

    @Test
    fun aPausingSessionResumesWithoutCountingTwice() = runTest {
        repeat(4) { create(alice, payload()) }
        val pausing = session(state = SessionState.PAUSING, desired = DesiredState.PAUSED)
        repository.put(pausing)

        assertEquals(DesiredState.RUNNING, change.resume(alice, pausing.id).desiredState)
    }

    @Test
    fun resumeOfASessionDeletedMeanwhileIsNotFound() = runTest {
        val paused = session(state = SessionState.PAUSED, desired = DesiredState.PAUSED)
        repository.put(paused)
        val vanishing = object : run.ostrom.server.runners.domain.CloudSessionsRepository by repository {
            override suspend fun resumeWithinCap(orgId: Uuid, id: Uuid, maxRunning: Int, now: Instant) =
                run.ostrom.server.runners.domain.ResumeOutcome.NotFound
        }

        assertFailsWith<CloudSessionNotFoundException> {
            val meter = RepositoryCloudUsageMeter(vanishing)
            ChangeCloudSessionUseCaseImpl(vanishing, FreeCloudPlanProvider(), meter, clock).resume(alice, paused.id)
        }
    }

    @Test
    fun deleteSetsTheDesiredStateAndIsIdempotent() = runTest {
        val created = create(alice, payload())

        val deleted = change.delete(alice, created.id)
        assertEquals(DesiredState.TERMINATED, deleted.desiredState)
        assertSame(deleted, change.delete(alice, created.id))
        val failed = session(state = SessionState.FAILED)
        repository.put(failed)
        assertSame(failed, change.delete(alice, failed.id))
    }

    @Test
    fun changesOfAnotherMembersSessionAreNotFound() = runTest {
        val created = create(alice, payload())
        val mallory = Caller(ORG_A, "mallory")

        assertFailsWith<CloudSessionNotFoundException> { change.pause(mallory, created.id) }
        assertFailsWith<CloudSessionNotFoundException> { change.resume(mallory, created.id) }
        assertFailsWith<CloudSessionNotFoundException> { change.delete(mallory, created.id) }
    }

    @Test
    fun changesOfASessionDeletedBetweenTheReadAndTheWriteAreNotFound() = runTest {
        val created = create(alice, payload())
        val vanishing = object : run.ostrom.server.runners.domain.CloudSessionsRepository by repository {
            override suspend fun setDesiredState(orgId: Uuid, id: Uuid, desiredState: DesiredState, now: Instant) = null
        }
        val racing = ChangeCloudSessionUseCaseImpl(vanishing, plans, RepositoryCloudUsageMeter(vanishing), clock)

        assertFailsWith<CloudSessionNotFoundException> { racing.pause(alice, created.id) }
        assertFailsWith<CloudSessionNotFoundException> { racing.delete(alice, created.id) }
        assertNull(vanishing.setDesiredState(ORG_A, created.id, DesiredState.PAUSED, clock.now))
    }
}
