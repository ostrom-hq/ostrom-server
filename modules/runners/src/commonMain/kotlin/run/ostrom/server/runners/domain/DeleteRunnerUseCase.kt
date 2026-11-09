// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners.domain

import kotlin.uuid.Uuid

/** Removes a runner of the caller's organization, and its key with it (owners and admins). */
interface DeleteRunnerUseCase {
    /**
     * Removes it.
     *
     * @param caller The member.
     * @param id The runner.
     *
     * @throws RunnerAdminRequiredException When the caller is neither an owner nor an admin.
     * @throws RunnerNotFoundException When the organization has no such runner.
     */
    suspend operator fun invoke(caller: Caller, id: Uuid)
}

/**
 * [DeleteRunnerUseCase] on the repository.
 *
 * @param repository The runners.
 */
class DeleteRunnerUseCaseImpl(private val repository: RegisteredRunnersRepository) : DeleteRunnerUseCase {
    override suspend fun invoke(caller: Caller, id: Uuid) {
        caller.requireAdmin()
        if (!repository.delete(caller.orgId, id)) throw RunnerNotFoundException()
    }
}
