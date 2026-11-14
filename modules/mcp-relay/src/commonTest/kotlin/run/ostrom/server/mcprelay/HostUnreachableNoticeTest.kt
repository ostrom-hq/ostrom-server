// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay

import run.ostrom.server.kernel.database.ScheduledWork
import run.ostrom.server.kernel.events.OrgEventDraft
import run.ostrom.server.kernel.events.OrgEventPublisher
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.mcprelay.infrastructure.HostUnreachableNoticeImpl
import io.github.smyrgeorge.sqlx4k.Transaction
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** `mcp.unreachable`: one event per org, whole-organization, at most one every ten minutes. */
class HostUnreachableNoticeTest {
    private class Leases(private val held: Set<String> = emptySet()) : ScheduledWork {
        val taken = mutableListOf<Pair<String, Duration>>()

        override suspend fun runDue(name: String, interval: Duration, timeout: Duration, block: suspend () -> Unit):
            Boolean {
            taken += name to interval
            if (name in held) return false
            block()
            return true
        }
    }

    private class Published : OrgEventPublisher {
        val events = mutableListOf<Pair<Uuid, List<OrgEventDraft>>>()

        override suspend fun publish(orgId: Uuid, events: List<OrgEventDraft>) {
            this.events += orgId to events
        }

        override suspend fun publishIn(transaction: Transaction, orgId: Uuid, events: List<OrgEventDraft>) =
            error("Its own transaction")
    }

    @Test
    fun eachOrgGetsOneEventOfTheWholeOrganizationUnderItsOwnTenMinuteLease() = runTest {
        val first = Uuid.parse("00000000-0000-0000-0000-00000000000a")
        val second = Uuid.parse("00000000-0000-0000-0000-00000000000b")
        val leases = Leases(held = setOf("mcp/unreachable/$second"))
        val published = Published()
        HostUnreachableNoticeImpl(leases, published).hostUnreachable(setOf(second, first), "refused")
        assertEquals(
            listOf("mcp/unreachable/$first" to 10.minutes, "mcp/unreachable/$second" to 10.minutes),
            leases.taken,
            "In org order, whichever replica sweeps",
        )
        val (org, events) = published.events.single()
        assertEquals(first, org, "The org whose lease another replica holds is left to it")
        assertEquals(
            OrgEventDraft(
                OrgEventType.MCP_UNREACHABLE, "", null, JsonObject(mapOf("reason" to JsonPrimitive("refused"))),
            ),
            events.single(),
        )
    }
}
