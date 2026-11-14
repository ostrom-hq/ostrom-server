// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.runners.domain.CloudEnvironmentSpec
import run.ostrom.server.runners.domain.CloudRepository
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CloudSetup
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.ExecutionFencedException
import run.ostrom.server.runners.domain.GitHubAccessMode
import run.ostrom.server.runners.domain.GitIdentity
import run.ostrom.server.runners.domain.InvalidCloudSessionRequestException
import run.ostrom.server.runners.domain.NoActiveMembers
import run.ostrom.server.runners.domain.NotPreparingException
import run.ostrom.server.runners.domain.PreparationFailedException
import run.ostrom.server.runners.domain.PreparationFailure
import run.ostrom.server.runners.domain.PreparationPhase
import run.ostrom.server.runners.domain.PreparationStep
import run.ostrom.server.runners.domain.RunnerEnvironmentUseCaseImpl
import run.ostrom.server.runners.domain.RunnerExecutionRequest
import run.ostrom.server.runners.domain.RunnerGitHubToken
import run.ostrom.server.runners.domain.RunnerGitHubTokens
import run.ostrom.server.runners.domain.RunnerInstruction
import run.ostrom.server.runners.domain.RunnerPreparationReport
import run.ostrom.server.runners.domain.RunnerUnauthenticatedException
import run.ostrom.server.runners.domain.RunnerVariableValues
import run.ostrom.server.runners.domain.SessionFailure
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.ALICE
import run.ostrom.server.runners.fakes.FakeRunnerTokens
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The runner's preparation, as TeamCode's `RunnerChannelUseCase.environment` and `progress`. */
class RunnerEnvironmentUseCaseTest {
    private val repository = InMemoryCloudSessionsRepository()
    private val clock = SettableClock()
    private var values = RunnerVariableValues(mapOf("NPM_TOKEN" to "npm-alice"), listOf("BROKEN"))
    private var access: RunnerGitHubToken = RunnerGitHubToken.Linked("ghu", null, "octo", 7)
    private val githubAsked = mutableListOf<String>()
    private var active = true
    private var login: String? = "alice"

    private val github = object : RunnerGitHubTokens {
        override suspend fun available(): Boolean = true

        override suspend fun token(orgId: Uuid, memberId: String): RunnerGitHubToken {
            githubAsked += memberId
            return access
        }
    }
    private val members = object : MemberDirectory {
        override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean = active
    }
    private val logins = object : MemberLogins {
        override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> = emptyMap()

        override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> =
            login?.let { name -> memberIds.associateWith { name } }.orEmpty()
    }
    private val use = RunnerEnvironmentUseCaseImpl(
        repository,
        FakeRunnerTokens(),
        { _, _ -> values },
        github,
        members,
        logins,
        clock,
    )

    private val recipe = CloudEnvironmentSpec(
        repositories = listOf(CloudRepository("https://github.com/acme/api", "api", "main")),
        setup = CloudSetup(create = listOf("npm ci"), start = listOf("npm run dev")),
        requiredVariables = listOf("NPM_TOKEN"),
    )

    private fun preparing(
        state: SessionState = SessionState.CREATING,
        desired: DesiredState = DesiredState.RUNNING,
        environment: CloudEnvironmentSpec? = recipe,
        owner: String = ALICE.toString(),
    ): CloudSession = session(state = state, desired = desired, ownerId = owner)
        .let { it.copy(epoch = 2, runnerTokenHash = "hash(${it.id})", executionStartedAt = clock.now) }
        .copy(environment = environment)
        .also { repository.put(it) }

    /** Each session's runner token: its id. */
    private val CloudSession.token: String get() = id.toString()

    private fun CloudSession.ask() = RunnerExecutionRequest(id, epoch)

    private suspend fun prepare(session: CloudSession) = use.environment(session.token, session.ask())

    private suspend fun report(
        session: CloudSession,
        phase: PreparationPhase,
        failure: PreparationFailure? = null,
    ) = use.progress(session.token, RunnerPreparationReport(session.id, session.epoch, phase, failure))

    private fun stored(session: CloudSession) = repository.sessions.getValue(session.id)

    @Test
    fun theRunnerGetsTheRecipeTheGitIdentityAndTheOwnersValues() = runTest {
        val session = preparing()
        val prepared = prepare(session)
        assertEquals(recipe.copy(workingDirectory = "api"), prepared.spec)
        assertEquals(GitIdentity("octo", "7+octo@users.noreply.github.com"), prepared.git)
        assertEquals(mapOf("NPM_TOKEN" to "npm-alice"), prepared.variables.variables)
        assertEquals(listOf("BROKEN"), prepared.variables.unavailable)
        assertEquals(listOf(ALICE.toString()), githubAsked)
        assertEquals(session, stored(session), "Nothing written")
    }

    @Test
    fun anOwnerWithoutGitHubCommitsWithTheirLoginOrTheirIdWhenNoLoginIsKnown() = runTest {
        access = RunnerGitHubToken.NotLinked
        assertEquals(GitIdentity("alice", "alice@users.noreply.ostrom.invalid"), prepare(preparing()).git)
        login = null
        assertEquals(ALICE.toString(), prepare(preparing()).git.name)
        values = RunnerVariableValues(mapOf("NPM_TOKEN" to "x"), emptyList())
        val notAMember = preparing(state = SessionState.RESUMING, owner = "service")
        assertEquals("service", prepare(notAMember).git.name)
    }

    @Test
    fun aSessionWithGitHubAccessOffNeverAsksForTheToken() = runTest {
        val off = preparing(environment = null).copy(githubAccess = GitHubAccessMode.NONE).also { repository.put(it) }
        val prepared = prepare(off)
        assertEquals("alice@users.noreply.ostrom.invalid", prepared.git.email)
        assertEquals(CloudEnvironmentSpec(), prepared.spec)
        assertEquals(emptyList(), githubAsked)
    }

    private suspend fun assertFailed(session: CloudSession, retryable: Boolean, words: String) {
        assertFailsWith<PreparationFailedException> { prepare(session) }
        val failed = stored(session)
        val failure = failed.failure ?: error("No failure recorded")
        assertEquals("preparation_failed", failure.code)
        assertEquals(retryable, failure.retryable, failure.message)
        assertTrue(words in failure.message, failure.message)
        assertEquals("preparation_failed", failed.phase)
        assertEquals(SessionState.CREATING, failed.state, "The reconciler moves it")
        assertEquals(clock.now, repository.next[session.id], "The reconciler looks at it now")
        assertTrue(failed.preparationFailed)
        assertFailsWith<NotPreparingException> { prepare(session) }
    }

    @Test
    fun aMissingRequiredVariableFailsThePreparationForGood() = runTest {
        values = RunnerVariableValues(emptyMap(), emptyList())
        assertFailed(preparing(), retryable = false, words = "not assigned to the owner: NPM_TOKEN")
    }

    @Test
    fun anUnreadableRequiredVariableFailsThePreparationForNow() = runTest {
        values = RunnerVariableValues(emptyMap(), listOf("NPM_TOKEN"))
        assertFailed(preparing(), retryable = true, words = "unavailable right now: NPM_TOKEN")
    }

    @Test
    fun anOwnerWhoLeftGetsNothingPrepared() = runTest {
        active = false
        assertFailed(preparing(), retryable = false, words = "no longer an active member")
        assertEquals(emptyList(), githubAsked)
    }

    @Test
    fun aRecipeThatRequiresGitHubFailsWithoutAUsableLink() = runTest {
        val requires = recipe.copy(requireGitHub = true)
        val off = preparing(environment = requires).copy(githubAccess = GitHubAccessMode.NONE)
            .also { repository.put(it) }
        assertFailed(off, retryable = true, words = "ostrom cloud github ${off.id} --access owner")
        access = RunnerGitHubToken.NotLinked
        assertFailed(preparing(environment = requires), retryable = true, words = "has not linked GitHub")
        access = RunnerGitHubToken.Unavailable
        assertFailed(preparing(environment = requires), retryable = true, words = "unavailable right now")
        access = RunnerGitHubToken.Linked("ghu", null, "octo", 7)
        assertEquals("octo", prepare(preparing(environment = requires)).git.name)
    }

    @Test
    fun aFailureLostToEveryRaceIsFencedAndOneLostOnceIsWrittenAgain() = runTest {
        values = RunnerVariableValues(emptyMap(), emptyList())
        val lost = preparing()
        repository.loseNextRaces = 3
        assertFailsWith<ExecutionFencedException> { prepare(lost) }
        assertNull(stored(lost).failure)
        repository.loseNextRaces = 1
        assertFailsWith<PreparationFailedException> { prepare(lost) }
        assertTrue(stored(lost).preparationFailed)
    }

    @Test
    fun onlyTheRunnerOfTheCurrentStartOfAPreparingSessionIsAnswered() = runTest {
        val session = preparing()
        assertFailsWith<RunnerUnauthenticatedException> { use.environment("forged", session.ask()) }
        val otherSession = RunnerExecutionRequest(Uuid.random(), 2)
        assertFailsWith<ExecutionFencedException> { use.environment(session.token, otherSession) }
        val olderExecution = RunnerExecutionRequest(session.id, 1)
        assertFailsWith<ExecutionFencedException> { use.environment(session.token, olderExecution) }
        for (other in listOf(
            preparing(state = SessionState.RUNNING),
            preparing(desired = DesiredState.PAUSED),
            preparing().copy(failure = SessionFailure("preparation_failed", "x")).also { repository.put(it) },
        )) {
            assertFailsWith<NotPreparingException> { prepare(other) }
            assertFailsWith<NotPreparingException> { report(other, PreparationPhase.RUNNING_SETUP) }
        }
        val otherFailure = preparing().copy(failure = SessionFailure("start_failed", "x")).also { repository.put(it) }
        assertEquals(recipe.requiredVariables, prepare(otherFailure).spec.requiredVariables)
    }

    @Test
    fun aPhaseIsShownOnTheSessionAndWrittenOnce() = runTest {
        val session = preparing()
        val answer = report(session, PreparationPhase.CLONING_REPOSITORIES)
        assertEquals(RunnerInstruction.RUN, answer.instruction)
        assertEquals(SessionState.CREATING, answer.state)
        val shown = stored(session)
        assertEquals("cloning_repositories", shown.phase)
        assertEquals(1, shown.generation)
        report(session, PreparationPhase.CLONING_REPOSITORIES)
        assertEquals(1, stored(session).generation, "The same phase again writes nothing")
    }

    @Test
    fun aReportedFailureFailsThePreparationWithTheServersMessage() = runTest {
        val session = preparing(state = SessionState.RESUMING)
        val failure = PreparationFailure(PreparationStep.FETCH, 0, 128)
        val answer = report(session, PreparationPhase.FETCHING_REPOSITORIES, failure)
        assertEquals(RunnerInstruction.STOP, answer.instruction)
        val failed = stored(session).failure ?: error("No failure")
        assertEquals("preparation_failed", failed.code)
        assertTrue(failed.retryable)
        assertTrue(failed.message.startsWith("Fetching api failed (exit code 128)."), failed.message)
        val setup = preparing()
        val bad = PreparationFailure(PreparationStep.SETUP_START, 0, 2)
        report(setup, PreparationPhase.RUNNING_SETUP, bad)
        assertFalse(stored(setup).failure?.retryable ?: true, "A setup command fails the same way again")
    }

    @Test
    fun aFailureNamingNoStepOfTheRecipeIsRefusedAndAReportLostToEveryRaceIsFenced() = runTest {
        val session = preparing()
        val unknown = PreparationFailure(PreparationStep.SETUP_CREATE, 5)
        assertFailsWith<InvalidCloudSessionRequestException> {
            report(session, PreparationPhase.RUNNING_SETUP, unknown)
        }
        assertNull(stored(session).failure)
        repository.loseNextRaces = 3
        assertFailsWith<ExecutionFencedException> {
            report(session, PreparationPhase.CONFIGURING_GIT)
        }
    }

    @Test
    fun withoutTheOrgsModuleNobodyIsAnActiveMember() = runTest {
        assertFalse(NoActiveMembers.isActiveMember(ORG_A, ALICE.toString()))
        assertEquals(emptyMap(), NoActiveMembers.idsOf(ORG_A, listOf("alice")))
        assertEquals(emptyMap(), NoActiveMembers.loginsOf(ORG_A, listOf(ALICE)))
    }
}
