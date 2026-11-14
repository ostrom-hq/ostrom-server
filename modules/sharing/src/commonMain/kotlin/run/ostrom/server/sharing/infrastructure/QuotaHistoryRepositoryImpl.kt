// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.infrastructure

import run.ostrom.server.kernel.database.InstantColumnType
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.PurgeDates
import run.ostrom.server.sharing.domain.QuotaHistoryRepository
import run.ostrom.server.sharing.domain.QuotaWindow
import run.ostrom.server.sharing.domain.isRecordable
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.UpsertStatement
import org.jetbrains.exposed.v1.core.statements.buildStatement
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.statements.UpsertBlockingExecutable
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [QuotaHistoryRepository] on PostgreSQL ([SharingMigrations] version 11), every org query naming its org and run in
 * [JdbcDatabase.inOrg]. A reading is only written for an account of that org: the account is read first, which another
 * org's confinement hides (a foreign key alone would accept another org's account).
 *
 * @param database The database.
 * @param clock The time a prune date is checked against.
 */
class QuotaHistoryRepositoryImpl(
    private val database: JdbcDatabase,
    private val clock: Clock,
) : QuotaHistoryRepository {
    override suspend fun record(orgId: Uuid, accountId: Uuid, windows: List<QuotaWindow>) {
        val valid = windows.filter { it.isRecordable() }
        if (valid.isEmpty()) return
        database.inOrg(orgId) {
            if (accountOfOrg(orgId, accountId).empty()) return@inOrg
            valid.forEach { reading ->
                UpsertBlockingExecutable(buildStatement { quotaReading(orgId, accountId, reading) }).execute(this)
            }
        }
    }

    override suspend fun recent(
        orgId: Uuid,
        accountId: Uuid,
        windows: List<String>,
        since: Instant,
        until: Instant,
        limit: Int,
    ): List<QuotaWindow> = database.inOrg(orgId) {
        recentQuotaHistory(orgId, accountId, windows, since, until, limit).map {
            QuotaWindow(
                window = it[SharingQuotaHistory.name],
                utilization = it[SharingQuotaHistory.utilization],
                resetsAt = it[SharingQuotaHistory.resetsAt],
                observedAt = it[SharingQuotaHistory.observedAt],
            )
        }
    }

    override suspend fun prune(before: Instant, limit: Int): Long {
        PurgeDates.check(before, clock.now())
        return database.query {
            val pass = CustomFunction(
                "sharing_quota_history_purge",
                LongColumnType(),
                QueryParameter(before, InstantColumnType()),
                QueryParameter(limit, IntegerColumnType()),
            )
            Table.Dual.select(pass).single()[pass]
        }
    }
}

/** The account, if it is one of the org's. */
internal fun accountOfOrg(orgId: Uuid, accountId: Uuid): Query = SharingAccounts.select(SharingAccounts.id)
    .where { (SharingAccounts.orgId eq orgId) and (SharingAccounts.id eq accountId) }

/**
 * Writes a reading as its minute's, unless the minute already holds a newer one (TeamCode's one sample per minute and
 * window, the newest kept): one statement, so two replicas recording at once keep the newest.
 */
internal fun StatementBuilder.quotaReading(orgId: Uuid, accountId: Uuid, reading: QuotaWindow): UpsertStatement<Long> =
    SharingQuotaHistory.upsert(
        SharingQuotaHistory.accountId,
        SharingQuotaHistory.name,
        SharingQuotaHistory.sampleMinute,
        where = { SharingQuotaHistory.observedAt lessEq reading.observedAt },
    ) {
        it[SharingQuotaHistory.orgId] = orgId
        it[SharingQuotaHistory.accountId] = accountId
        it[SharingQuotaHistory.name] = reading.window
        it[SharingQuotaHistory.utilization] = reading.utilization
        it[SharingQuotaHistory.resetsAt] = reading.resetsAt
        it[SharingQuotaHistory.observedAt] = reading.observedAt
        it[SharingQuotaHistory.sampleMinute] = reading.observedAt.toEpochMilliseconds() / MILLIS_PER_MINUTE
    }

/** The forecast's read: the readings of the windows from [since] to [until], newest first, by the `recent` index. */
internal fun recentQuotaHistory(
    orgId: Uuid,
    accountId: Uuid,
    windows: List<String>,
    since: Instant,
    until: Instant,
    limit: Int,
): Query = SharingQuotaHistory
    .select(
        SharingQuotaHistory.name,
        SharingQuotaHistory.utilization,
        SharingQuotaHistory.resetsAt,
        SharingQuotaHistory.observedAt,
    )
    .where {
        (SharingQuotaHistory.orgId eq orgId) and (SharingQuotaHistory.accountId eq accountId) and
            (SharingQuotaHistory.name inList windows) and (SharingQuotaHistory.observedAt greaterEq since) and
            (SharingQuotaHistory.observedAt lessEq until)
    }
    .orderBy(SharingQuotaHistory.observedAt to SortOrder.DESC, SharingQuotaHistory.name to SortOrder.ASC)
    .limit(limit)

private const val MILLIS_PER_MINUTE = 60_000L
