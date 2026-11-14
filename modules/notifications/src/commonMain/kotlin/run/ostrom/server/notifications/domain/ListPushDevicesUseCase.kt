// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.domain

import kotlin.uuid.Uuid

/** A member sees the phones that are notified for them. */
interface ListPushDevicesUseCase {
    /**
     * The member's phones in the organization, the most recently seen first.
     *
     * @param orgId The organization the member acts in.
     * @param memberId The member.
     *
     * @return Their phones: another member's are never listed.
     */
    suspend operator fun invoke(orgId: Uuid, memberId: Uuid): List<PushDevice>
}

/**
 * [ListPushDevicesUseCase] on the repository.
 *
 * @param repository The phones.
 */
class ListPushDevicesUseCaseImpl(private val repository: PushDevicesRepository) : ListPushDevicesUseCase {
    override suspend fun invoke(orgId: Uuid, memberId: Uuid): List<PushDevice> = repository.ofMember(orgId, memberId)
}
