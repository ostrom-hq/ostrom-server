// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.domain

import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.secrets.domain.AdminRequiredException
import run.ostrom.server.secrets.domain.Caller
import kotlin.uuid.Uuid

/**
 * A member's pools (TeamCode's `MemberPolicy.poolId`, `allowSharedOverflow` and `weight`).
 *
 * @property poolId Their home pool, or null for none.
 * @property allowSharedOverflow Whether they may borrow the accounts of the shared overflow pools once their own are
 *   unavailable, while each pool's daily budget lasts.
 * @property weight Their part of their pool's daily budget, against the weights of its other members
 *   ([SharingRules.MAX_WEIGHT] at most); 0 gives them nothing of it.
 */
data class PoolPolicy(
    val poolId: String?,
    val allowSharedOverflow: Boolean,
    val weight: Int = SharingRules.DEFAULT_WEIGHT,
) {
    /** The defaults. */
    companion object {
        /** A member without a policy: in no pool, no overflow, weight 1. */
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
     * Puts [memberId] in [poolId] (none for null), allows their overflow when [allowSharedOverflow] says so and gives
     * them [weight] (each kept as it is for null), under the lock the pool budgets take ([PoolBudgetRepository.save]).
     *
     * @return The policy written, or null, with nothing written, when [poolId] is a shared overflow pool.
     */
    suspend fun setPolicy(
        orgId: Uuid,
        memberId: Uuid,
        poolId: String?,
        allowSharedOverflow: Boolean?,
        weight: Int? = null,
    ): PoolPolicy?
}

/** Every member in no pool: what the pool sees when no [MemberPoolRepository] is given (tests of other features). */
object NoMemberPools : MemberPoolRepository {
    override suspend fun policyOf(orgId: Uuid, memberId: Uuid): PoolPolicy = PoolPolicy.NONE

    override suspend fun policiesOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, PoolPolicy> = emptyMap()

    override suspend fun setPolicy(
        orgId: Uuid,
        memberId: Uuid,
        poolId: String?,
        allowSharedOverflow: Boolean?,
        weight: Int?,
    ) = PoolPolicy(poolId, allowSharedOverflow == true, weight ?: SharingRules.DEFAULT_WEIGHT)
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
 * The pools whose accounts a member may use (TeamCode's `poolAccounts` for a member): their home pool (null for none)
 * and, when they may borrow, every shared overflow pool, funded today or not.
 *
 * @property home The member's home pool, or null for none.
 * @property overflow The shared overflow pools they may borrow.
 */
data class PoolScope(val home: String?, val overflow: Set<String>) {
    /** Whether [account] is in it. */
    fun covers(account: SharedAccount): Boolean = account.inPools(home, overflow)
}

/** Reads a member's [PoolScope]. */
fun interface MemberPoolScope {
    /** The pools of [memberId] in [orgId]: one read of their policy, and of the reserves when they may borrow. */
    suspend fun of(orgId: Uuid, memberId: Uuid): PoolScope
}

/** Every member in no pool, without overflow: what the list sees when no scope is given (tests of other features). */
val NoPoolScope: MemberPoolScope = MemberPoolScope { _, _ -> PoolScope(null, emptySet()) }

/**
 * [MemberPoolScope] on the members' policies and the pools' budgets (TeamCode's `readMemberPolicy` and
 * `sharedOverflowBudgets`).
 *
 * @param pools The members' policies.
 * @param budgets The pools' budgets, whose shared reserves a member allowed overflow sees.
 */
class MemberPoolScopeImpl(
    private val pools: MemberPoolRepository,
    private val budgets: PoolBudgetRepository,
) : MemberPoolScope {
    override suspend fun of(orgId: Uuid, memberId: Uuid): PoolScope {
        val policy = pools.policyOf(orgId, memberId)
        if (!policy.allowSharedOverflow) return PoolScope(policy.poolId, emptySet())
        return PoolScope(policy.poolId, budgets.reserves(orgId).mapTo(mutableSetOf()) { it.poolId })
    }
}

/**
 * A member's routing policy as the API names it (TeamCode's `MemberPolicy`, its pools and weight).
 *
 * @property login The member's login.
 * @property memberId The member.
 * @property poolId Their home pool, or null for none.
 * @property allowSharedOverflow Whether they may borrow the shared overflow pools.
 * @property weight Their weight in their pool's split of its daily budget.
 */
data class MemberPolicy(
    val login: String,
    val memberId: Uuid,
    val poolId: String?,
    val allowSharedOverflow: Boolean,
    val weight: Int = SharingRules.DEFAULT_WEIGHT,
)

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
     * lets them borrow the shared overflow pools, or not, and [weight] is their weight in their pool's split of its
     * daily budget (0 to [SharingRules.MAX_WEIGHT]); null keeps what each was.
     *
     * @throws AdminRequiredException When the caller is not an owner or an admin.
     * @throws InvalidPolicyException When the pool is not a pool identifier, or the weight is out of 0 to
     *   [SharingRules.MAX_WEIGHT].
     * @throws OrgMemberNotFoundException When no member of the organization has this login.
     * @throws OverflowPrimaryPoolException When the pool is a shared overflow pool.
     */
    suspend fun setPolicy(
        caller: Caller,
        login: String,
        poolId: String,
        allowSharedOverflow: Boolean?,
        weight: Int? = null,
    ): MemberPolicy
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
        weight: Int?,
    ): MemberPolicy {
        caller.requireAdmin()
        val pool = poolId.ifEmpty { null }
        if (pool != null && !SharingRules.isPool(pool)) throw InvalidPolicyException()
        if (weight != null && weight !in 0..SharingRules.MAX_WEIGHT) {
            throw InvalidPolicyException("Use a weight from 0 to ${SharingRules.MAX_WEIGHT}.")
        }
        val memberId = memberOf(caller, login)
        val written = pools.setPolicy(caller.orgId, memberId, pool, allowSharedOverflow, weight)
            ?: throw OverflowPrimaryPoolException("A shared overflow reserve cannot be a member's primary pool.")
        return written.named(login, memberId)
    }

    private suspend fun memberOf(caller: Caller, login: String): Uuid =
        logins.idsOf(caller.orgId, listOf(login))[login] ?: throw OrgMemberNotFoundException()
}

private fun PoolPolicy.named(login: String, memberId: Uuid) =
    MemberPolicy(login, memberId, poolId, allowSharedOverflow, weight)

/** A policy out of its form: a 400 `invalid_policy` (TeamCode's). */
class InvalidPolicyException(
    message: String = "Use a pool identifier containing letters, digits, underscores or hyphens (up to 36 characters).",
) : OstromException(ErrorKind.INVALID_REQUEST, message, "invalid_policy")
