// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications.domain

import kotlin.uuid.Uuid

/** A member stops push notifications to one of their phones. */
interface DeletePushDeviceUseCase {
    /**
     * Removes the phone.
     *
     * @param orgId The organization the member acts in.
     * @param memberId The member.
     * @param id The device.
     *
     * @throws PushDeviceNotFoundException When it is not one of the member's phones in this organization.
     */
    suspend operator fun invoke(orgId: Uuid, memberId: Uuid, id: Uuid)
}

/**
 * [DeletePushDeviceUseCase] on the repository.
 *
 * @param repository The phones.
 */
class DeletePushDeviceUseCaseImpl(private val repository: PushDevicesRepository) : DeletePushDeviceUseCase {
    override suspend fun invoke(orgId: Uuid, memberId: Uuid, id: Uuid) {
        if (!repository.delete(orgId, memberId, id)) throw PushDeviceNotFoundException()
    }
}
