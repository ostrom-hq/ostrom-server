// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.domain

import kotlin.time.Clock

/**
 * A registered runner as listed.
 *
 * @property runner The runner.
 * @property online Whether it gave a sign of life within [ReconcileCloudSessionUseCaseImpl.HEARTBEAT_LOSS], the
 *   window the cloud sessions' runners are judged by.
 */
data class ListedRunner(val runner: RegisteredRunner, val online: Boolean)

/** Lists the runners the caller's organization registered (any member). */
interface ListRegisteredRunnersUseCase {
    /**
     * Lists them.
     *
     * @param caller The member.
     *
     * @return The organization's runners, by name.
     */
    suspend operator fun invoke(caller: Caller): List<ListedRunner>
}

/**
 * [ListRegisteredRunnersUseCase] on the repository.
 *
 * @param repository The runners.
 * @param clock The time.
 */
class ListRegisteredRunnersUseCaseImpl(
    private val repository: RegisteredRunnersRepository,
    private val clock: Clock,
) : ListRegisteredRunnersUseCase {
    override suspend fun invoke(caller: Caller): List<ListedRunner> {
        val now = clock.now()
        return repository.list(caller.orgId, RegisterRunnerUseCaseImpl.MAX_RUNNERS).map { runner ->
            val seen = runner.lastSeenAt
            ListedRunner(runner, seen != null && now - seen <= ReconcileCloudSessionUseCaseImpl.HEARTBEAT_LOSS)
        }
    }
}
