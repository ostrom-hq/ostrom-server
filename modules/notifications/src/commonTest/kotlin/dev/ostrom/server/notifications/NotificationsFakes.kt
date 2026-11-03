// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications

import dev.ostrom.server.notifications.domain.PushDevice
import dev.ostrom.server.notifications.domain.PushDevicesRepository
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The phones in memory, and how many the last save let a member keep. */
class InMemoryPushDevicesRepository : PushDevicesRepository {
    val devices = mutableListOf<PushDevice>()
    var kept: Int? = null

    override suspend fun save(device: PushDevice, keep: Int): PushDevice {
        kept = keep
        devices += device
        return device
    }

    override suspend fun delete(orgId: Uuid, memberId: Uuid, id: Uuid): Boolean =
        devices.removeAll { it.orgId == orgId && it.memberId == memberId && it.id == id }
}

/** A clock that says [now]. */
class TestClock(var now: Instant = Instant.parse("2026-11-03T08:00:00Z")) : Clock {
    override fun now(): Instant = now
}
