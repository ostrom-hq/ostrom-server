// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0),
// infrastructure/src/main/kotlin/me/nathanfallet/teamcode/infrastructure/database/SqlNotificationStore.kt; see NOTICE.
package run.ostrom.server.notifications.infrastructure

import run.ostrom.server.kernel.database.InstantColumnType
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.events.JournalPosition
import run.ostrom.server.kernel.events.OrgEventJournal
import run.ostrom.server.notifications.domain.DeliveryOutcome
import run.ostrom.server.notifications.domain.DeliveryRules
import run.ostrom.server.notifications.domain.DeliveryState
import run.ostrom.server.notifications.domain.NotificationDeliveriesRepository
import run.ostrom.server.notifications.domain.QueuedDelivery
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [NotificationDeliveriesRepository] on PostgreSQL through Exposed ([NotificationsMigrations] versions 6 and 7): every
 * query inside the organization (`inOrg`, with its own `org_id` filter), the paths across organizations through the
 * functions of `ostrom_router`. The router's journal cursor moves in the transaction that queues the page.
 *
 * @param database The database.
 * @param journal The event journal whose cursor the router moves, read when a page is queued.
 */
class NotificationDeliveriesRepositoryImpl(
    private val database: JdbcDatabase,
    private val journal: () -> OrgEventJournal,
) : NotificationDeliveriesRepository {
    override suspend fun enqueue(
        position: JournalPosition,
        through: Long,
        deliveries: List<QueuedDelivery>,
    ): Boolean = database.inOrg(position.orgId) {
        if (!journal().advanceIn(this, DeliveryRules.READER, position, through)) return@inOrg false
        NotificationDeliveries.batchInsert(deliveries, ignore = true, shouldReturnGeneratedValues = false) {
            fill(position.orgId, it)
        }
        true
    }

    override suspend fun workingOrgs(now: Instant, limit: Int): List<Uuid> = database.query {
        val orgs = CustomFunction(
            "notifications_delivery_orgs",
            UuidColumnType(),
            QueryParameter(now, InstantColumnType()),
            QueryParameter(limit, IntegerColumnType()),
        )
        Table.Dual.select(orgs).map { it[orgs] }
    }

    override suspend fun claimDue(
        orgId: Uuid,
        limit: Int,
        now: Instant,
        lease: Duration,
    ): List<QueuedDelivery> = database.inOrg(orgId) {
        val rows = NotificationDeliveries.select(NotificationDeliveries.columns)
            .where {
                (NotificationDeliveries.orgId eq orgId) and
                    (NotificationDeliveries.state eq DeliveryState.PENDING.wire) and
                    (NotificationDeliveries.nextAttemptAt lessEq now) and
                    (NotificationDeliveries.leaseUntil less now)
            }
            .orderBy(NotificationDeliveries.nextAttemptAt to SortOrder.ASC)
            .limit(limit)
            .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
            .toList()
            .map { it to it.toDelivery() }
        val known = rows.mapNotNull { (row, delivery) -> delivery?.let { row to it } }
        val unknown = rows.filter { it.second == null }.map { it.first }
        if (known.isNotEmpty()) {
            NotificationDeliveries.update({ owned(orgId, known.map { it.first }) }) { it[leaseUntil] = now + lease }
        }
        // A kind of a later version, rolled back since: never sent as another kind.
        if (unknown.isNotEmpty()) {
            NotificationDeliveries.update({ owned(orgId, unknown) }) {
                it[state] = DeliveryState.FAILED.wire
                it[lastError] = UNKNOWN_KIND
            }
        }
        known.map { it.second }
    }

    override suspend fun record(orgId: Uuid, id: Uuid, outcome: DeliveryOutcome, now: Instant) {
        database.inOrg(orgId) {
            NotificationDeliveries.update({
                // Only a pending row: a late replica whose lease ended never overwrites another's outcome.
                (NotificationDeliveries.orgId eq orgId) and (NotificationDeliveries.id eq id) and
                    (NotificationDeliveries.state eq DeliveryState.PENDING.wire)
            }) {
                it[state] = outcome.state.wire
                it[attempts] = outcome.attempts
                it[nextAttemptAt] = outcome.nextAttemptAt
                it[leaseUntil] = now
                it[lastError] = outcome.error?.take(MAX_ERROR)
                it[deliveredAt] = if (outcome.state == DeliveryState.DELIVERED) now else null
            }
        }
    }

    override suspend fun maintain(now: Instant, giveUpBefore: Instant, deleteBefore: Instant, limit: Int): Long =
        database.query {
            val pass = CustomFunction(
                "notifications_deliveries_maintain",
                LongColumnType(),
                QueryParameter(now, InstantColumnType()),
                QueryParameter(giveUpBefore, InstantColumnType()),
                QueryParameter(deleteBefore, InstantColumnType()),
                QueryParameter(limit, IntegerColumnType()),
            )
            Table.Dual.select(pass).single()[pass]
        }

    override suspend fun deleteAll(orgId: Uuid) {
        database.inOrg(orgId) { NotificationDeliveries.deleteWhere { NotificationDeliveries.orgId eq orgId } }
    }

    private fun owned(orgId: Uuid, rows: List<ResultRow>) = (NotificationDeliveries.orgId eq orgId) and
        (NotificationDeliveries.id inList rows.map { it[NotificationDeliveries.id] })

    private companion object {
        const val UNKNOWN_KIND = "unknown_kind"
        const val MAX_ERROR = 40
    }
}
