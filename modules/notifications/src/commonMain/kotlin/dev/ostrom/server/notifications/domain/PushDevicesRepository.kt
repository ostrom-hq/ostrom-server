// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications.domain

import kotlin.uuid.Uuid

/** The registered phones, each organization's apart. */
interface PushDevicesRepository {
    /**
     * Registers a phone, or registers its push token again: the existing device then keeps its id and creation date
     * and takes the new member, platform, language and [PushDevice.seenAt]. The member then keeps their [keep] most
     * recently seen phones: a rotated token never piles up.
     *
     * @param device The phone.
     * @param keep How many phones a member keeps.
     *
     * @return The device as stored.
     */
    suspend fun save(device: PushDevice, keep: Int): PushDevice

    /**
     * Removes one of a member's phones.
     *
     * @param orgId The organization.
     * @param memberId The member.
     * @param id The device.
     *
     * @return Whether it was the member's and is now removed.
     */
    suspend fun delete(orgId: Uuid, memberId: Uuid, id: Uuid): Boolean
}
