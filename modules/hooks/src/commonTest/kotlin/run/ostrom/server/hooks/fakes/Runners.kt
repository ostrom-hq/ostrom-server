// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.hooks.fakes

import run.ostrom.server.runners.domain.AgentKind
import run.ostrom.server.runners.domain.AuthenticateRunnerUseCase
import run.ostrom.server.runners.domain.CheckRunnerTokenUseCase
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.ExecutionFencedException
import run.ostrom.server.runners.domain.RunnerUnauthenticatedException
import run.ostrom.server.runners.domain.SessionLimits
import run.ostrom.server.runners.domain.SessionState
import org.koin.core.module.Module
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Ada's running cloud session, at its first execution. */
val ADA_SESSION: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000c0da")

/** A cloud session of a member who left the organization. */
val GONE_SESSION: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000c0de")

/** Ben's paused cloud session: it holds no execution. */
val PAUSED_SESSION: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000c0be")

private fun session(id: Uuid, owner: String, state: SessionState) = CloudSession(
    id = id, orgId = ORG_A, ownerId = owner, name = "work", agent = AgentKind.CLAUDE, repository = null,
    state = state, desiredState = DesiredState.RUNNING, phase = null, epoch = 1, generation = 1, failure = null,
    limits = SessionLimits(idleTimeoutMinutes = 30, maxRunMinutes = 600, restrictedEgress = false),
    createdAt = Instant.fromEpochSeconds(0), updatedAt = Instant.fromEpochSeconds(0),
)

/** The runner tokens of the tests and the sessions their current execution belongs to. */
private val RUNNERS = mapOf(
    "runner-ada" to session(ADA_SESSION, "$ADA", SessionState.RUNNING),
    "runner-gone" to session(GONE_SESSION, "00000000-0000-0000-0000-0000000000ff", SessionState.RUNNING),
    "runner-paused" to session(PAUSED_SESSION, "$BEN", SessionState.PAUSED),
)

/** The runners module's token checks, on [RUNNERS]. */
fun Module.testRunners() {
    single<CheckRunnerTokenUseCase> {
        object : CheckRunnerTokenUseCase {
            override suspend fun invoke(token: String) {
                if (token !in RUNNERS) throw RunnerUnauthenticatedException()
            }
        }
    }
    single<AuthenticateRunnerUseCase> {
        object : AuthenticateRunnerUseCase {
            override suspend fun invoke(token: String, sessionId: Uuid, epoch: Long): CloudSession {
                val session = RUNNERS[token] ?: throw RunnerUnauthenticatedException()
                if (session.id != sessionId || session.epoch != epoch) throw ExecutionFencedException()
                return session
            }
        }
    }
}
