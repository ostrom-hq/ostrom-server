// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.AuthenticateRunnerUseCase
import run.ostrom.server.runners.domain.AuthenticateRunnerUseCaseImpl
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CheckRunnerTokenUseCaseImpl
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CloudUsageMeter
import run.ostrom.server.runners.domain.CreateRemoteSessionUseCaseImpl
import run.ostrom.server.runners.domain.ExecutionFencedException
import run.ostrom.server.runners.domain.HostRemoteSessionUseCaseImpl
import run.ostrom.server.runners.domain.OwnerLoginPrincipalResolver
import run.ostrom.server.runners.domain.RemoteSessionDraft
import run.ostrom.server.runners.domain.RunnerCredentialsImpl
import run.ostrom.server.runners.domain.RunnerExecution
import run.ostrom.server.runners.domain.RunnerHeartbeat
import run.ostrom.server.runners.domain.RunnerHeartbeatUseCaseImpl
import run.ostrom.server.runners.domain.RunnerInstruction
import run.ostrom.server.runners.domain.RunnerTokenPrincipalResolver
import run.ostrom.server.runners.domain.RunnerUnauthenticatedException
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.ALICE
import run.ostrom.server.runners.fakes.FakeRunnerTokens
import run.ostrom.server.runners.fakes.FakeSignedInDevices
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.REMOTE_LOGINS
import run.ostrom.server.runners.fakes.RemoteLogins
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.TestOrgResolver
import run.ostrom.server.runners.fakes.session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A remote session's host on the runner channel: its owner's sign-in instead of a pod's token (option B). */
class RemoteRunnerAuthTest {
    private val repository = InMemoryCloudSessionsRepository()
    private val clock = SettableClock()
    private val devices = FakeSignedInDevices()
    private val credentials = RunnerCredentialsImpl(repository, FakeRunnerTokens())
    private val authenticate = AuthenticateRunnerUseCaseImpl(
        listOf(
            RunnerTokenPrincipalResolver(credentials),
            OwnerLoginPrincipalResolver(RemoteLogins, devices, TestOrgResolver, repository),
        ),
        repository,
    )
    private val records = mutableListOf<Long>()
    private val meter = object : CloudUsageMeter {
        override suspend fun usedSeconds(orgId: Uuid, now: Instant): Long = 0

        override suspend fun record(orgId: Uuid, seconds: Long, newExecution: Boolean, now: Instant) {
            records += seconds
        }
    }
    private val heartbeat = RunnerHeartbeatUseCaseImpl(repository, credentials, meter, clock, authenticate)
    private val alice = Caller(ORG_A, ALICE.toString())
    private val laptop = REMOTE_LOGINS.getValue("alice-laptop")

    private suspend fun hosted(): CloudSession {
        val created = CreateRemoteSessionUseCaseImpl(repository, devices, { 60 }, clock)(
            alice,
            laptop,
            RemoteSessionDraft(name = null, agent = "claude", directoryName = "ostrom"),
        ).session
        return HostRemoteSessionUseCaseImpl(repository, devices, clock)(alice, laptop, created.id).session
    }

    private fun beat(id: Uuid, epoch: Long, ready: Boolean = true) =
        RunnerHeartbeat(id, epoch, ready, false, null as Instant?, false)

    @Test
    fun aRemoteHeartbeatRunsTheSessionAndBillsNothing() = runTest {
        val session = hosted()
        clock.now += 30.seconds

        val answer = heartbeat("alice-laptop", beat(session.id, 1))
        clock.now += 30.seconds
        heartbeat("alice-laptop", beat(session.id, 1))

        assertEquals(RunnerInstruction.RUN, answer.instruction)
        assertEquals(SessionState.RUNNING, answer.state)
        assertEquals(clock.now, repository.sessions.getValue(session.id).lastHeartbeatAt)
        assertTrue(records.isEmpty())
        assertTrue(repository.usage.isEmpty())
    }

    @Test
    fun twoRemoteSessionsOnOneDeviceBeatEachWithItsOwnEpoch() = runTest {
        val one = hosted()
        val two = hosted()
        HostRemoteSessionUseCaseImpl(repository, devices, clock)(alice, laptop, two.id)

        assertEquals(SessionState.RUNNING, heartbeat("alice-laptop", beat(one.id, 1)).state)
        assertEquals(SessionState.RUNNING, heartbeat("alice-laptop", beat(two.id, 2)).state)
        // The execution the second host replaced is fenced, and so is another device of the owner's.
        assertFailsWith<ExecutionFencedException> { heartbeat("alice-laptop", beat(two.id, 1)) }
        assertFailsWith<ExecutionFencedException> { heartbeat("alice-desktop", beat(one.id, 1)) }
        assertFailsWith<ExecutionFencedException> { heartbeat("bob-laptop", beat(one.id, 1)) }
        assertEquals(RunnerExecution(ORG_A, one.id, 1), authenticate.resolve("alice-laptop"))
    }

    @Test
    fun aHostIsNeverAcceptedWithoutResolvers() = runTest {
        val session = hosted()
        val tokensOnly = RunnerHeartbeatUseCaseImpl(repository, credentials, meter, clock)
        assertFailsWith<RunnerUnauthenticatedException> { tokensOnly("alice-laptop", beat(session.id, 1)) }
    }

    @Test
    fun aCloudSessionIsNeverReachedThroughASignIn() = runTest {
        val cloud = session().also(repository::put)
        val anyone = object : AuthenticateRunnerUseCase {
            override suspend fun resolve(credential: String) = RunnerExecution(ORG_A, cloud.id, 0)
            override suspend fun invoke(credential: String, sessionId: Uuid, epoch: Long) = cloud
        }
        val beats = RunnerHeartbeatUseCaseImpl(repository, credentials, meter, clock, anyone)
        assertFailsWith<ExecutionFencedException> { beats("alice-laptop", beat(cloud.id, 0)) }
    }

    @Test
    fun theTokenCheckKnowsAHostByItsSignIn() = runTest {
        hosted()
        val check = CheckRunnerTokenUseCaseImpl(credentials, authenticate)
        check("alice-laptop")
        assertFailsWith<RunnerUnauthenticatedException> { check("alice-desktop") }
        assertFailsWith<RunnerUnauthenticatedException> { CheckRunnerTokenUseCaseImpl(credentials)("alice-laptop") }
    }
}
