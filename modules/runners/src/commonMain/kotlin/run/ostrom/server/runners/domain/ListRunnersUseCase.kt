// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A runner at work: the pod (or Docker container) of the current execution of one of the caller's cloud sessions.
 * The server starts one per execution; there is no machine to register.
 *
 * @property sessionId The session it runs.
 * @property name The session's name.
 * @property agent The agent it runs.
 * @property epoch The execution.
 * @property lastSeenAt Its last heartbeat, null before the first one.
 * @property online Whether it reported within [ReconcileCloudSessionUseCaseImpl.HEARTBEAT_LOSS], the time after which
 *   the reconciler counts it lost.
 */
data class CloudRunner(
    val sessionId: Uuid,
    val name: String,
    val agent: AgentKind,
    val epoch: Long,
    val lastSeenAt: Instant?,
    val online: Boolean,
)

/** Lists the runners at work for the caller. */
interface ListRunnersUseCase {
    /**
     * Lists them.
     *
     * @param caller The member.
     *
     * @return The runners of the caller's sessions that hold an execution, newest session first.
     */
    suspend operator fun invoke(caller: Caller): List<CloudRunner>
}

/**
 * [ListRunnersUseCase] on the repository.
 *
 * @param repository The sessions.
 * @param clock The time.
 */
class ListRunnersUseCaseImpl(
    private val repository: CloudSessionsRepository,
    private val clock: Clock,
) : ListRunnersUseCase {
    override suspend fun invoke(caller: Caller): List<CloudRunner> {
        val now = clock.now()
        return repository.executions(caller.orgId, caller.memberId, MAX_RUNNERS).map { session ->
            val seen = session.lastHeartbeatAt
            CloudRunner(
                sessionId = session.id,
                name = session.name,
                agent = session.agent,
                epoch = session.epoch,
                lastSeenAt = seen,
                online = isOnline(seen, now),
            )
        }
    }

    private companion object {
        // Above every plan's cap of sessions holding an execution: the list is never cut.
        const val MAX_RUNNERS = 100
    }
}
