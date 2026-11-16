// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.write
import run.ostrom.server.sharing.domain.OverflowReserve
import run.ostrom.server.sharing.domain.PoolBudget
import run.ostrom.server.sharing.domain.PoolBudgetRepository
import run.ostrom.server.sharing.domain.PoolBudgetSave
import run.ostrom.server.sharing.domain.PoolBudgets
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.UpsertStatement
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/**
 * [PoolBudgetRepository] on `sharing_pool_budgets` ([SharingMigrations] 15) through Exposed, confined to the
 * organization it names (`inOrg`); a save takes the organization's pools lock ([lockPools]), as a member's policy does.
 *
 * @param database The serving role's pool.
 */
class PoolBudgetRepositoryImpl(private val database: JdbcDatabase) : PoolBudgetRepository {
    override suspend fun list(orgId: Uuid): List<PoolBudget> = database.inOrg(orgId) {
        val b = SharingPoolBudgets
        poolBudgetsOf(orgId).map { PoolBudget(it[b.poolId], it[b.dailyMicros], it[b.sharedOverflow]) }
    }

    override suspend fun save(orgId: Uuid, budget: PoolBudget): PoolBudgetSave = database.inOrg(orgId) {
        lockPools(orgId)
        val shared = budget.sharedOverflow
        when {
            shared && homeOf(orgId, budget.poolId).any() -> PoolBudgetSave.PRIMARY_POOL
            shared && otherReserves(orgId, budget.poolId).count() >= PoolBudgets.MAX_OVERFLOW_POOLS ->
                PoolBudgetSave.LIMIT
            else -> {
                write { poolBudgetPut(orgId, budget) }
                PoolBudgetSave.SAVED
            }
        }
    }

    override suspend fun reserves(orgId: Uuid): List<OverflowReserve> = database.inOrg(orgId) {
        val b = SharingPoolBudgets
        val budgets = reservesOf(orgId).associate { it[b.poolId] to it[b.dailyMicros] }
        if (budgets.isEmpty()) return@inOrg emptyList()
        val a = SharingAccounts
        val accounts = reserveAccounts(orgId, budgets.keys)
            .groupBy({ it[a.poolId] }, { it[a.id] })
        budgets.map { (pool, daily) -> OverflowReserve(pool, daily, accounts[pool].orEmpty().toSet()) }
    }
}

/** The organization's budgets, by pool (its key). */
internal fun poolBudgetsOf(orgId: Uuid): Query {
    val b = SharingPoolBudgets
    return b.select(b.poolId, b.dailyMicros, b.sharedOverflow).where { b.orgId eq orgId }.orderBy(b.poolId)
}

/** The members whose home pool is [poolId] (one is enough). */
internal fun homeOf(orgId: Uuid, poolId: String): Query {
    val p = SharingMemberPools
    return p.select(intLiteral(1)).where { (p.orgId eq orgId) and (p.poolId eq poolId) }.limit(1)
}

/** The shared pools other than [poolId], counted against the limit. */
internal fun otherReserves(orgId: Uuid, poolId: String): Query {
    val b = SharingPoolBudgets
    return b.select(b.poolId).where { (b.orgId eq orgId) and (b.sharedOverflow eq true) and (b.poolId neq poolId) }
}

/**
 * The shared pools no member calls home (TeamCode's `sharedOverflowBudgets`), at most
 * [PoolBudgets.MAX_OVERFLOW_POOLS]: the first read of a lease by a member allowed overflow.
 */
internal fun reservesOf(orgId: Uuid): Query {
    val b = SharingPoolBudgets
    val p = SharingMemberPools
    val home = p.select(intLiteral(1)).where { (p.orgId eq orgId) and (p.poolId eq b.poolId) }
    return b.select(b.poolId, b.dailyMicros)
        .where { (b.orgId eq orgId) and (b.sharedOverflow eq true) and notExists(home) }
        .orderBy(b.poolId to SortOrder.ASC)
        .limit(PoolBudgets.MAX_OVERFLOW_POOLS)
}

/** The accounts of [pools], whatever their provider or state: the second read. */
internal fun reserveAccounts(orgId: Uuid, pools: Collection<String>): Query {
    val a = SharingAccounts
    return a.select(a.id, a.poolId).where { (a.orgId eq orgId) and (a.poolId inList pools) }
}

/** A pool's budget created or replaced. */
internal fun StatementBuilder.poolBudgetPut(orgId: Uuid, budget: PoolBudget): UpsertStatement<Long> =
    SharingPoolBudgets.upsert(SharingPoolBudgets.orgId, SharingPoolBudgets.poolId) {
        it[this.orgId] = orgId
        it[this.poolId] = budget.poolId
        it[this.dailyMicros] = budget.dailyMicros
        it[this.sharedOverflow] = budget.sharedOverflow
    }
