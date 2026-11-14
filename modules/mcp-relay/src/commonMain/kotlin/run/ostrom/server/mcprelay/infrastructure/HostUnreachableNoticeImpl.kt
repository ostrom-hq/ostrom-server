// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay.infrastructure

import run.ostrom.server.kernel.database.ScheduledWork
import run.ostrom.server.kernel.events.OrgEventDraft
import run.ostrom.server.kernel.events.OrgEventPublisher
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.mcprelay.domain.HostUnreachableNotice
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * [HostUnreachableNotice] on the feed: `mcp.unreachable` for each org, an event of the whole organization (no subject
 * id, no member). A lease per org, shared by the replicas ([ScheduledWork]), keeps it to one every
 * [HostUnreachableNotice.INTERVAL], as TeamCode's `mcp/unreachable` does.
 *
 * @param work The leases.
 * @param events The organizations' feeds.
 */
class HostUnreachableNoticeImpl(
    private val work: ScheduledWork,
    private val events: OrgEventPublisher,
) : HostUnreachableNotice {
    override suspend fun hostUnreachable(orgIds: Set<Uuid>, reason: String) {
        // Sorted: every replica takes the leases in the same order.
        for (orgId in orgIds.sortedBy { it.toString() }) {
            work.runDue("mcp/unreachable/$orgId", HostUnreachableNotice.INTERVAL, PUBLISH_TIMEOUT) {
                val data = buildJsonObject { put("reason", reason) }
                events.publish(orgId, listOf(OrgEventDraft(OrgEventType.MCP_UNREACHABLE, "", null, data)))
            }
        }
    }

    private companion object {
        val PUBLISH_TIMEOUT = 30.seconds
    }
}
