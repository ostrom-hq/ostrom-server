// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.domain

import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.secrets.domain.AdminRequiredException
import run.ostrom.server.secrets.domain.Caller
import kotlin.uuid.Uuid

/**
 * A member's pools (TeamCode's `MemberPolicy.poolId` and `allowSharedOverflow`).
 *
 * @property poolId Their home pool, or null for none.
 * @property allowSharedOverflow Whether they may borrow the accounts of the shared overflow pools once their own are
 *   unavailable, while each pool's daily budget lasts.
 */
data class PoolPolicy(val poolId: String?, val allowSharedOverflow: Boolean) {
    /** The defaults. */
    companion object {
        /** A member without a policy: in no pool, no overflow. */
        val NONE: PoolPolicy = PoolPolicy(null, false)
    }
}

/**
 * The home pool of each member (TeamCode's `MemberPolicy.poolId`): a member is served only by the shared accounts of
 * their pool, and a member with none only by the accounts in no pool (TeamCode's placement,
 * `Accounts.poolId eq policy.poolId`), plus the shared overflow pools when they are allowed to borrow. Every call is
 * confined to the organization it names.
 */
interface MemberPoolRepository {
    /** The policy of [memberId] in [orgId], [PoolPolicy.NONE] without one. */
    suspend fun policyOf(orgId: Uuid, memberId: Uuid): PoolPolicy

    /** The policies of [memberIds] in [orgId]; a member without one is absent. */
    suspend fun policiesOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, PoolPolicy>

    /**
     * Puts [memberId] in [poolId] (none for null), and allows their overflow when [allowSharedOverflow] says so (kept
     * as it is for null), under the lock the pool budgets take ([PoolBudgetRepository.save]).
     *
     * @return The policy written, or null, with nothing written, when [poolId] is a shared overflow pool.
     */
    suspend fun setPolicy(orgId: Uuid, memberId: Uuid, poolId: String?, allowSharedOverflow: Boolean?): PoolPolicy?
}

/** Every member in no pool: what the pool sees when no [MemberPoolRepository] is given (tests of other features). */
object NoMemberPools : MemberPoolRepository {
    override suspend fun policyOf(orgId: Uuid, memberId: Uuid): PoolPolicy = PoolPolicy.NONE

    override suspend fun policiesOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, PoolPolicy> = emptyMap()

    override suspend fun setPolicy(orgId: Uuid, memberId: Uuid, poolId: String?, allowSharedOverflow: Boolean?) =
        PoolPolicy(poolId, allowSharedOverflow == true)
}

/** Whether [account] serves a member whose home pool is [home]: the same pool, or both in none (TeamCode's). */
fun SharedAccount.inPool(home: String?): Boolean = poolId == home

/**
 * Whether [account] may serve a member whose home pool is [home] and who may borrow [overflow]: their own pool's
 * account, or an account of one of those shared overflow pools (TeamCode's `poolId eq policy.poolId or poolId inList
 * overflow`).
 */
fun SharedAccount.inPools(home: String?, overflow: Set<String>): Boolean = inPool(home) || poolId in overflow

/**
 * A member's routing policy as the API names it (TeamCode's `MemberPolicy`, its pools only).
 *
 * @property login The member's login.
 * @property memberId The member.
 * @property poolId Their home pool, or null for none.
 * @property allowSharedOverflow Whether they may borrow the shared overflow pools.
 */
data class MemberPolicy(val login: String, val memberId: Uuid, val poolId: String?, val allowSharedOverflow: Boolean)

/** The members' policies: read by an admin or the member themself, changed by an admin. */
interface MemberPoliciesUseCase {
    /**
     * The policy of the member [login] names.
     *
     * @throws AdminRequiredException When a member who is no admin asks for another login's policy, a member's or not.
     * @throws OrgMemberNotFoundException When no member of the organization has this login (for an admin).
     */
    suspend fun get(caller: Caller, login: String): MemberPolicy

    /**
     * Puts the member [login] names in [poolId]; an empty string takes them out of their pool. [allowSharedOverflow]
     * lets them borrow the shared overflow pools, or not; null keeps what it was.
     *
     * @throws AdminRequiredException When the caller is not an owner or an admin.
     * @throws InvalidPolicyException When [poolId] is not a pool identifier.
     * @throws OrgMemberNotFoundException When no member of the organization has this login.
     * @throws OverflowPrimaryPoolException When [poolId] is a shared overflow pool.
     */
    suspend fun setPolicy(caller: Caller, login: String, poolId: String, allowSharedOverflow: Boolean?): MemberPolicy
}

/**
 * [MemberPoliciesUseCase] on the members' pools and logins.
 *
 * @param pools The members' pools.
 * @param logins The orgs module's logins.
 */
class MemberPoliciesUseCaseImpl(private val pools: MemberPoolRepository, private val logins: MemberLogins) :
    MemberPoliciesUseCase {
    override suspend fun get(caller: Caller, login: String): MemberPolicy {
        // A member who is no admin learns nothing of another login, not even whether it names a member: 403 for both.
        val memberId = logins.idsOf(caller.orgId, listOf(login))[login]
        if (memberId != caller.memberId) caller.requireAdmin()
        memberId ?: throw OrgMemberNotFoundException()
        return pools.policyOf(caller.orgId, memberId).named(login, memberId)
    }

    override suspend fun setPolicy(
        caller: Caller,
        login: String,
        poolId: String,
        allowSharedOverflow: Boolean?,
    ): MemberPolicy {
        caller.requireAdmin()
        val pool = poolId.ifEmpty { null }
        if (pool != null && !SharingRules.isPool(pool)) throw InvalidPolicyException()
        val memberId = memberOf(caller, login)
        val written = pools.setPolicy(caller.orgId, memberId, pool, allowSharedOverflow)
            ?: throw OverflowPrimaryPoolException("A shared overflow reserve cannot be a member's primary pool.")
        return written.named(login, memberId)
    }

    private suspend fun memberOf(caller: Caller, login: String): Uuid =
        logins.idsOf(caller.orgId, listOf(login))[login] ?: throw OrgMemberNotFoundException()
}

private fun PoolPolicy.named(login: String, memberId: Uuid) =
    MemberPolicy(login, memberId, poolId, allowSharedOverflow)

/** A policy out of its form: a 400 `invalid_policy` (TeamCode's). */
class InvalidPolicyException :
    OstromException(
        ErrorKind.INVALID_REQUEST,
        "Use a pool identifier containing letters, digits, underscores or hyphens (up to 36 characters).",
        "invalid_policy",
    )
