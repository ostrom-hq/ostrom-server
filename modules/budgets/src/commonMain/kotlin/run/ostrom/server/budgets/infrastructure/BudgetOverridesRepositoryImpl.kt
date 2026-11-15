// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.infrastructure

import run.ostrom.server.budgets.domain.ActiveBudgetOverride
import run.ostrom.server.budgets.domain.BudgetOverride
import run.ostrom.server.budgets.domain.BudgetOverrideGrant
import run.ostrom.server.budgets.domain.BudgetOverridePage
import run.ostrom.server.budgets.domain.BudgetOverrideRules
import run.ostrom.server.budgets.domain.BudgetOverridesRepository
import run.ostrom.server.budgets.domain.GrantOutcome
import run.ostrom.server.budgets.domain.RevokeOutcome
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.databaseNow
import run.ostrom.server.kernel.database.lockUntilCommit
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [BudgetOverridesRepository] on PostgreSQL through Exposed ([BudgetsOverrides]). Every query runs confined to the
 * org it names ([JdbcDatabase.inOrg]) and filters on it. Grants and revocations of an org take its advisory lock, so
 * that two administrators never both replace the same override; the override in force is read by the database's
 * clock at each read, with no job to end it.
 *
 * @param database The JDBC pool.
 */
class BudgetOverridesRepositoryImpl(private val database: JdbcDatabase) : BudgetOverridesRepository {
    override suspend fun active(orgId: Uuid, memberId: Uuid): ActiveBudgetOverride? =
        database.inOrg(orgId) { activeRead(orgId, memberId).singleOrNull()?.toOverride()?.summary() }

    override suspend fun history(orgId: Uuid, memberId: Uuid, before: Uuid?): BudgetOverridePage? =
        database.inOrg(orgId) {
            val t = BudgetsOverrides
            val cursor = before?.let { id ->
                t.select(t.createdAt, t.id)
                    .where { (t.orgId eq orgId) and (t.memberId eq memberId) and (t.id eq id) }
                    .singleOrNull() ?: return@inOrg null
            }
            val rows = historyRead(orgId, memberId, cursor?.let { it[t.createdAt] to it[t.id] })
                .map { it.toOverride() }
            val active = activeRead(orgId, memberId).singleOrNull()?.get(t.id)
            BudgetOverridePage(
                items = rows.take(BudgetOverrideRules.PAGE_SIZE),
                activeId = active,
                next = rows.takeIf { it.size > BudgetOverrideRules.PAGE_SIZE }
                    ?.get(BudgetOverrideRules.PAGE_SIZE - 1)?.id,
            )
        }

    override suspend fun grant(
        orgId: Uuid,
        memberId: Uuid,
        actor: Uuid,
        grant: BudgetOverrideGrant,
        expiry: (Instant) -> Instant,
    ): GrantOutcome = database.inOrg(orgId) {
        lockUntilCommit(lockKey(orgId))
        val t = BudgetsOverrides
        val now = Table.Dual.select(databaseNow()).single()[databaseNow()]
        val current = activeRead(orgId, memberId).singleOrNull()?.get(t.id)
        when {
            current != grant.replacesId -> GrantOutcome.Stale
            historyFull(orgId, memberId) -> GrantOutcome.HistoryFull
            else -> {
                if (current != null) {
                    revokeRow(orgId, memberId, current, Revocation(actor, now, "Replaced by a new override."))
                }
                val granted = BudgetOverride(
                    id = Uuid.random(),
                    memberId = memberId,
                    micros = grant.micros,
                    createdAt = now,
                    expiresAt = expiry(now),
                    reason = grant.reason,
                    createdBy = actor,
                    replacesId = current,
                )
                t.insert {
                    it[t.id] = granted.id
                    it[t.orgId] = orgId
                    it[t.memberId] = memberId
                    it[t.micros] = granted.micros
                    it[t.createdBy] = actor
                    it[t.reason] = granted.reason
                    it[t.createdAt] = now
                    it[t.expiresAt] = granted.expiresAt
                    it[t.replacesId] = current
                }
                GrantOutcome.Granted(granted, current)
            }
        }
    }

    override suspend fun revoke(orgId: Uuid, memberId: Uuid, id: Uuid, actor: Uuid, reason: String): RevokeOutcome =
        database.inOrg(orgId) {
            lockUntilCommit(lockKey(orgId))
            val now = Table.Dual.select(databaseNow()).single()[databaseNow()]
            val found = read(orgId, memberId, id)
            when {
                found == null -> RevokeOutcome.NotFound
                found.revokedAt != null -> RevokeOutcome.AlreadyRevoked(found)
                found.expiresAt <= now -> RevokeOutcome.Expired
                else -> {
                    revokeRow(orgId, memberId, id, Revocation(actor, now, reason))
                    RevokeOutcome.Revoked(found.copy(revokedAt = now, revokedBy = actor, revocationReason = reason))
                }
            }
        }

    private fun JdbcTransaction.historyFull(orgId: Uuid, memberId: Uuid): Boolean {
        val t = BudgetsOverrides
        val member = t.select(t.id).where { (t.orgId eq orgId) and (t.memberId eq memberId) }.count()
        return member >= BudgetOverrideRules.MAX_MEMBER_HISTORY ||
            t.select(t.id).where { t.orgId eq orgId }.count() >= BudgetOverrideRules.MAX_ORG_HISTORY
    }

    private fun revokeRow(orgId: Uuid, memberId: Uuid, id: Uuid, revocation: Revocation) {
        val t = BudgetsOverrides
        t.update({ (t.orgId eq orgId) and (t.memberId eq memberId) and (t.id eq id) and t.revokedAt.isNull() }) {
            it[t.revokedAt] = revocation.at
            it[t.revokedBy] = revocation.actor
            it[t.revocationReason] = revocation.reason
        }
    }

    private fun read(orgId: Uuid, memberId: Uuid, id: Uuid): BudgetOverride? {
        val t = BudgetsOverrides
        return t.select(t.columns).where { (t.orgId eq orgId) and (t.memberId eq memberId) and (t.id eq id) }
            .singleOrNull()?.toOverride()
    }

    private fun lockKey(orgId: Uuid) = "budgets_overrides:$orgId"

    /** Who revokes an override, when (the database's time) and why. */
    private data class Revocation(val actor: Uuid, val at: Instant, val reason: String)
}

/**
 * The member's override in force by the database's clock, through the partial index `budgets_overrides_active`: read
 * before each model call of a member with limits that stop.
 */
internal fun activeRead(orgId: Uuid, memberId: Uuid): Query {
    val t = BudgetsOverrides
    return t.select(t.columns)
        .where {
            (t.orgId eq orgId) and (t.memberId eq memberId) and t.revokedAt.isNull() and
                (t.expiresAt greater databaseNow()) and (t.createdAt lessEq databaseNow())
        }
        .orderBy(t.createdAt to SortOrder.DESC)
        .limit(1)
}

/** A page of the member's overrides after [after] (its creation time and id), one more than a page. */
internal fun historyRead(orgId: Uuid, memberId: Uuid, after: Pair<Instant, Uuid>?): Query {
    val t = BudgetsOverrides
    val older: Op<Boolean> = after?.let { (at, id) ->
        (t.createdAt less at) or ((t.createdAt eq at) and (t.id less id))
    } ?: Op.TRUE
    return t.select(t.columns)
        .where { (t.orgId eq orgId) and (t.memberId eq memberId) and older }
        .orderBy(t.createdAt to SortOrder.DESC, t.id to SortOrder.DESC)
        .limit(BudgetOverrideRules.PAGE_SIZE + 1)
}

private fun ResultRow.toOverride(): BudgetOverride {
    val t = BudgetsOverrides
    return BudgetOverride(
        id = this[t.id],
        memberId = this[t.memberId],
        micros = this[t.micros],
        createdAt = this[t.createdAt],
        expiresAt = this[t.expiresAt],
        reason = this[t.reason],
        createdBy = this[t.createdBy],
        replacesId = this[t.replacesId],
        revokedAt = this[t.revokedAt],
        revokedBy = this[t.revokedBy],
        revocationReason = this[t.revocationReason],
    )
}
