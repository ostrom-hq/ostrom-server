// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.infrastructure

import run.ostrom.server.kernel.database.InstantColumnType
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.usage.domain.Admission
import run.ostrom.server.usage.domain.HoldWindow
import run.ostrom.server.usage.domain.SpendHold
import run.ostrom.server.usage.domain.SpendLimits
import run.ostrom.server.usage.domain.UsageCursor
import run.ostrom.server.usage.domain.UsageEvent
import run.ostrom.server.usage.domain.UsageGroupBy
import run.ostrom.server.usage.domain.UsageRepository
import run.ostrom.server.usage.domain.UsageTotal
import run.ostrom.server.usage.domain.UsageWindow
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [UsageRepository] on PostgreSQL through Exposed ([UsageEvents], [UsageHolds], [UsageSpendDays],
 * [UsageIngestMinutes]; the reads in `UsageSpendReads.kt` and `UsageLedgerReads.kt`, the writes in `UsageWrites.kt`).
 * Every call is one transaction confined to the org it names ([JdbcDatabase.inOrg]), each query filtered on it too;
 * the purge alone crosses orgs, through the router's function.
 *
 * @param database The JDBC pool.
 */
@Suppress("TooManyFunctions") // The one storage port of the module: its writes, holds, sums and reads.
class UsageRepositoryImpl(private val database: JdbcDatabase) : UsageRepository {
    override suspend fun insert(orgId: Uuid, events: List<UsageEvent>) {
        if (events.isEmpty()) return
        database.inOrg(orgId) { insertEvents(events) }
    }

    override suspend fun insertWithinMinute(
        orgId: Uuid,
        memberId: Uuid,
        events: List<UsageEvent>,
        perMinute: Int,
    ): Boolean {
        // A batch larger than the minute holds is refused before it is counted, as a minute not started yet.
        return when {
            events.isEmpty() -> true
            events.size > perMinute -> false
            else -> database.inOrg(orgId) {
                write { ingestMinutesPurge(orgId, memberId) }
                // Under the row's lock: two batches of the member, on any replica, count one after the other.
                val counted = ingestMinuteCount(orgId, memberId, events.size, perMinute).toList().isNotEmpty()
                if (counted) insertEvents(events)
                counted
            }
        }
    }

    override suspend fun settle(event: UsageEvent) {
        database.inOrg(event.orgId) {
            insertEvents(listOf(event))
            write { holdDrop(event.orgId, event.id) }
        }
    }

    override suspend fun dropHold(orgId: Uuid, callId: Uuid) {
        database.inOrg(orgId) { write { holdDrop(orgId, callId) } }
    }

    override suspend fun holdWithin(hold: SpendHold, limits: SpendLimits, window: HoldWindow): Admission =
        // One transaction on one connection: the lock is held until its end, and no read waits for another connection.
        database.inOrg(hold.orgId) {
            lockUntilCommit(holdsLockKey(hold.orgId))
            write { holdsRetire(hold.orgId, window) }
            // A call of no launch has no launch budget, whatever the limits say.
            val applying = if (hold.launchId == null) limits.copy(launchMicros = null) else limits
            val totals = holdTotalsRead(hold, applying, window).single()
            applying.admit(totals, hold.micros).also { admission ->
                if (admission == Admission.Admitted) write { holdPut(hold, window) }
            }
        }

    override suspend fun spentMicros(orgId: Uuid, memberId: Uuid?, since: Instant): Long =
        database.inOrg(orgId) { spentRead(orgId, memberId, since).single() }

    override suspend fun accountSpentMicros(orgId: Uuid, accountId: Uuid, since: Instant): Long =
        database.inOrg(orgId) { accountSpentRead(orgId, accountId, since).single() }

    override suspend fun workflowSpentMicros(orgId: Uuid, workflowId: String, since: Instant): Long =
        database.inOrg(orgId) { workflowSpentRead(orgId, workflowId, since).single() }

    override suspend fun launchSpentMicros(orgId: Uuid, launchId: String, from: Instant, to: Instant): Long? =
        database.inOrg(orgId) { launchSpentRead(orgId, launchId, from, to).single() }

    override suspend fun summary(window: UsageWindow, groupBy: UsageGroupBy, limit: Int): List<UsageTotal> =
        database.inOrg(window.orgId) { summaryRead(window, groupBy, limit).rows() }

    override suspend fun list(orgId: Uuid, memberId: Uuid?, before: UsageCursor?, limit: Int): List<UsageEvent> =
        database.inOrg(orgId) { listRead(orgId, memberId, before, limit).rows() }

    // The one operation across orgs, through the function of UsageMigrations version 4: one batch of old events.
    override suspend fun purge(before: Instant, limit: Int): Long = database.query {
        val purged = CustomFunction(
            "usage_purge",
            LongColumnType(),
            QueryParameter(before, InstantColumnType()),
            QueryParameter(limit, IntegerColumnType()),
        )
        Table.Dual.select(purged).single()[purged]
    }
}

/** The advisory lock key of an org's holds: its calls are admitted one at a time, on every replica. */
internal fun holdsLockKey(orgId: Uuid): String = "usage_holds:$orgId"
