// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.Read
import run.ostrom.server.kernel.database.lockKeyUntilCommit
import run.ostrom.server.kernel.database.write
import run.ostrom.server.sharing.domain.MemberPoolRepository
import run.ostrom.server.sharing.domain.PoolPolicy
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.DeleteStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.UpsertStatement
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/**
 * [MemberPoolRepository] on `sharing_member_pools` ([SharingMigrations] 14 and 15) through Exposed. Every query runs
 * confined to the organization it names (`inOrg`): row-level security hides the rows of every other one. A change
 * takes the organization's pools lock ([lockPools]), as a pool budget's does.
 *
 * @param database The serving role's pool.
 */
class MemberPoolRepositoryImpl(private val database: JdbcDatabase) : MemberPoolRepository {
    override suspend fun policyOf(orgId: Uuid, memberId: Uuid): PoolPolicy = database.inOrg(orgId) {
        Read(memberPoolOf(orgId, memberId)) { it.toPolicy() }.singleOrNull() ?: PoolPolicy.NONE
    }

    override suspend fun policiesOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, PoolPolicy> {
        if (memberIds.isEmpty()) return emptyMap()
        return database.inOrg(orgId) {
            memberPoolsOf(orgId, memberIds).associate { it[SharingMemberPools.memberId] to it.toPolicy() }
        }
    }

    override suspend fun setPolicy(
        orgId: Uuid,
        memberId: Uuid,
        poolId: String?,
        allowSharedOverflow: Boolean?,
        weight: Int?,
    ): PoolPolicy? = database.inOrg(orgId) {
        lockPools(orgId)
        if (poolId != null && sharedPool(orgId, poolId).any()) return@inOrg null
        // What is not given is kept.
        val kept = Read(memberPoolOf(orgId, memberId)) { it.toPolicy() }.singleOrNull() ?: PoolPolicy.NONE
        val policy = PoolPolicy(poolId, allowSharedOverflow ?: kept.allowSharedOverflow, weight ?: kept.weight)
        if (policy == PoolPolicy.NONE) {
            write { memberPoolClear(orgId, memberId) }
        } else {
            write { memberPoolPut(orgId, memberId, policy) }
        }
        policy
    }
}

private fun ResultRow.toPolicy() = PoolPolicy(
    this[SharingMemberPools.poolId],
    this[SharingMemberPools.allowSharedOverflow],
    this[SharingMemberPools.weight],
)

/** Takes the organization's pools lock until the transaction ends: member policies and pool budgets take turns. */
internal fun JdbcTransaction.lockPools(orgId: Uuid) =
    lockKeyUntilCommit("sharing_pools:$orgId")

/** The policy of one member (the request path of a lease: one read on the key). */
internal fun memberPoolOf(orgId: Uuid, memberId: Uuid): Query {
    val p = SharingMemberPools
    return p.select(p.poolId, p.allowSharedOverflow, p.weight).where { (p.orgId eq orgId) and (p.memberId eq memberId) }
}

/** The policies of some members (the capacity controller's pass over the members with a recent session). */
internal fun memberPoolsOf(orgId: Uuid, memberIds: Collection<Uuid>): Query {
    val p = SharingMemberPools
    return p.select(p.memberId, p.poolId, p.allowSharedOverflow, p.weight)
        .where { (p.orgId eq orgId) and (p.memberId inList memberIds) }
}

/** Whether [poolId] is a shared overflow pool of [orgId] (by the budgets' key). */
internal fun sharedPool(orgId: Uuid, poolId: String): Query {
    val b = SharingPoolBudgets
    return b.select(b.poolId)
        .where { (b.orgId eq orgId) and (b.poolId eq poolId) and (b.sharedOverflow eq true) }
}

/** A member's policy written, in a pool or not. */
internal fun StatementBuilder.memberPoolPut(orgId: Uuid, memberId: Uuid, policy: PoolPolicy): UpsertStatement<Long> =
    SharingMemberPools.upsert(SharingMemberPools.orgId, SharingMemberPools.memberId) {
        it[this.orgId] = orgId
        it[this.memberId] = memberId
        it[this.poolId] = policy.poolId
        it[this.allowSharedOverflow] = policy.allowSharedOverflow
        it[this.weight] = policy.weight
    }

/** A member back to no policy: in no pool, no overflow, weight 1. */
internal fun StatementBuilder.memberPoolClear(orgId: Uuid, memberId: Uuid): DeleteStatement =
    SharingMemberPools.deleteWhere { (SharingMemberPools.orgId eq orgId) and (SharingMemberPools.memberId eq memberId) }
