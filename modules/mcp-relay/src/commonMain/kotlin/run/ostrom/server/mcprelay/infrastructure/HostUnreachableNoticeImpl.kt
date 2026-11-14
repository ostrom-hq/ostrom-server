// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay.infrastructure

import run.ostrom.server.kernel.database.ScheduledWork
import run.ostrom.server.kernel.events.OrgEventDraft
import run.ostrom.server.kernel.events.OrgEventPublisher
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.mcprelay.domain.HostUnreachableNotice
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * [HostUnreachableNotice] on the feed: `mcp.unreachable` for each org, an event of the whole organization (no subject
 * id, no member). A lease per org and reason, shared by the replicas ([ScheduledWork]), keeps it to one every
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
            // One org's failing lease or journal never keeps the others from their event (the next sweep tries again).
            try {
                // A lease per reason: a host that goes from silent to refusing is told at once.
                work.runDue("mcp/unreachable/$orgId/$reason", HostUnreachableNotice.INTERVAL, PUBLISH_TIMEOUT) {
                    val data = buildJsonObject { put("reason", reason) }
                    events.publish(orgId, listOf(OrgEventDraft(OrgEventType.MCP_UNREACHABLE, "", null, data)))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                continue
            }
        }
    }

    private companion object {
        val PUBLISH_TIMEOUT = 30.seconds
    }
}
