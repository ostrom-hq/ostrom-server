// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.write
import run.ostrom.server.sharing.domain.MemberPoolRepository
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.DeleteStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.UpsertStatement
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/**
 * [MemberPoolRepository] on `sharing_member_pools` ([SharingMigrations] 14) through Exposed. Every query runs confined
 * to the organization it names (`inOrg`): row-level security hides the rows of every other one.
 *
 * @param database The serving role's pool.
 */
class MemberPoolRepositoryImpl(private val database: JdbcDatabase) : MemberPoolRepository {
    override suspend fun poolOf(orgId: Uuid, memberId: Uuid): String? = database.inOrg(orgId) {
        memberPoolOf(orgId, memberId).singleOrNull()?.get(SharingMemberPools.poolId)
    }

    override suspend fun poolsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> {
        if (memberIds.isEmpty()) return emptyMap()
        val p = SharingMemberPools
        return database.inOrg(orgId) {
            memberPoolsOf(orgId, memberIds).associate { it[p.memberId] to it[p.poolId] }
        }
    }

    override suspend fun setPool(orgId: Uuid, memberId: Uuid, poolId: String?) {
        database.inOrg(orgId) {
            if (poolId == null) {
                write { memberPoolClear(orgId, memberId) }
            } else {
                write { memberPoolPut(orgId, memberId, poolId) }
            }
        }
    }
}

/** The home pool of one member (the request path of a lease: one read on the key). */
internal fun memberPoolOf(orgId: Uuid, memberId: Uuid): Query {
    val p = SharingMemberPools
    return p.select(p.poolId).where { (p.orgId eq orgId) and (p.memberId eq memberId) }
}

/** The home pools of some members (the capacity controller's pass over the members with a recent session). */
internal fun memberPoolsOf(orgId: Uuid, memberIds: Collection<Uuid>): Query {
    val p = SharingMemberPools
    return p.select(p.memberId, p.poolId).where { (p.orgId eq orgId) and (p.memberId inList memberIds) }
}

/** A member put in a pool, or moved to another. */
internal fun StatementBuilder.memberPoolPut(orgId: Uuid, memberId: Uuid, poolId: String): UpsertStatement<Long> =
    SharingMemberPools.upsert(SharingMemberPools.orgId, SharingMemberPools.memberId) {
        it[this.orgId] = orgId
        it[this.memberId] = memberId
        it[this.poolId] = poolId
    }

/** A member taken out of their pool. */
internal fun StatementBuilder.memberPoolClear(orgId: Uuid, memberId: Uuid): DeleteStatement =
    SharingMemberPools.deleteWhere { (SharingMemberPools.orgId eq orgId) and (SharingMemberPools.memberId eq memberId) }
