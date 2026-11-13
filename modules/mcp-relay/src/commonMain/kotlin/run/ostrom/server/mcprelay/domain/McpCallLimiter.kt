// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.mcprelay.domain

import kotlin.uuid.Uuid

/**
 * Concurrency and per-minute limits of relayed operations, per member and server and per server across the org. They
 * hold across every replica: the running operations and the minute's count are in the database
 * ([McpCallSlotRepository]), not in a replica's memory.
 *
 * @property memberConcurrency Running operations per member and server.
 * @property serverConcurrency Running operations per server.
 * @property memberPerMinute Operations per member and server per minute.
 * @property serverPerMinute Operations per server per minute.
 */
data class McpLimits(
    val memberConcurrency: Int = 4,
    val serverConcurrency: Int = 32,
    val memberPerMinute: Int = 120,
    val serverPerMinute: Int = 1200,
)

/**
 * Admits operations under [McpLimits].
 *
 * @param limits The limits.
 * @param slots The running operations and the minutes' counts.
 */
class McpCallLimiter(private val limits: McpLimits, private val slots: McpCallSlotRepository) {
    /**
     * Takes a slot.
     *
     * @return The slot when admitted; the caller then calls [release] with it exactly once.
     */
    suspend fun acquire(orgId: Uuid, memberId: Uuid, serverId: Uuid): McpCallSlot? =
        slots.acquire(orgId, memberId, serverId) { usage ->
            usage.memberRunning < limits.memberConcurrency &&
                usage.serverRunning < limits.serverConcurrency &&
                usage.memberThisMinute < limits.memberPerMinute &&
                usage.serverThisMinute < limits.serverPerMinute
        }

    /** Returns a slot taken by [acquire]. */
    suspend fun release(orgId: Uuid, slot: McpCallSlot) = slots.release(orgId, slot)
}
