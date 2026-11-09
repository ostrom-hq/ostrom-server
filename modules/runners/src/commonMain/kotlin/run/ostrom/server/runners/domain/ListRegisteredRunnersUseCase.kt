// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners.domain

import kotlin.time.Clock
import kotlin.time.Instant

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
            ListedRunner(runner, isOnline(runner.lastSeenAt, now))
        }
    }
}

/**
 * Whether a runner that last gave a sign of life at [lastSeenAt] is online at [now]: within
 * [ReconcileCloudSessionUseCaseImpl.HEARTBEAT_LOSS], the window after which the reconciler counts a runner lost. Every
 * runners list judges by it.
 *
 * @param lastSeenAt Its last sign of life, null before the first one.
 * @param now The time.
 *
 * @return True when online.
 */
fun isOnline(lastSeenAt: Instant?, now: Instant): Boolean =
    lastSeenAt != null && now - lastSeenAt <= ReconcileCloudSessionUseCaseImpl.HEARTBEAT_LOSS
