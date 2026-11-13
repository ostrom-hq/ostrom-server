// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.mcprelay.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * What an admission sees of one server, its row locked.
 *
 * @property memberRunning The member's running operations on the server.
 * @property serverRunning The server's running operations.
 * @property memberThisMinute The member's operations on the server started this minute (database clock).
 * @property serverThisMinute The server's operations started this minute.
 */
data class McpLoad(
    val memberRunning: Int,
    val serverRunning: Int,
    val memberThisMinute: Int,
    val serverThisMinute: Int,
)

/** A running operation's place, held until it is released or [McpCallSlotRepository.SLOT_LEASE] passes. */
data class McpCallSlot(val id: Uuid)

/**
 * The running operations and the per-minute counts of the relay, shared by every replica. Every call is confined to
 * one org; the minute and the slots' expiry are the database's clock.
 */
interface McpCallSlotRepository {
    /**
     * In one transaction holding the server's row lock: shows [admit] the server's [McpLoad]; when it admits, takes a
     * slot and counts the operation in the minute.
     *
     * @return The slot, or null when [admit] refused (or the server is gone).
     */
    suspend fun acquire(orgId: Uuid, memberId: Uuid, serverId: Uuid, admit: (McpLoad) -> Boolean): McpCallSlot?

    /** Gives a slot back; nothing when it is gone. */
    suspend fun release(orgId: Uuid, slot: McpCallSlot)

    /** Extends a slot still held by [SLOT_LEASE] from now; nothing when it is gone. */
    suspend fun renew(orgId: Uuid, slot: McpCallSlot)

    /** Timing. */
    companion object {
        /**
         * A slot never released (its replica died) frees itself after this; an operation still running renews its
         * slot every [SLOT_RENEWAL], so one slower than the lease (a host's cold start, a retry) keeps counting.
         */
        val SLOT_LEASE: Duration = 15.minutes

        /** How often a running operation renews its slot. */
        val SLOT_RENEWAL: Duration = 5.minutes
    }
}
