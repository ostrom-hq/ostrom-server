// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.domain

import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.secrets.domain.AdminRequiredException
import run.ostrom.server.secrets.domain.Caller
import kotlin.uuid.Uuid

/**
 * The home pool of each member (TeamCode's `MemberPolicy.poolId`): a member is served only by the shared accounts of
 * their pool, and a member with none only by the accounts in no pool (TeamCode's placement,
 * `Accounts.poolId eq policy.poolId`). Every call is confined to the organization it names.
 */
interface MemberPoolRepository {
    /** The pool of [memberId] in [orgId], or null for none. */
    suspend fun poolOf(orgId: Uuid, memberId: Uuid): String?

    /** The pools of [memberIds] in [orgId]; a member in no pool is absent. */
    suspend fun poolsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String>

    /** Puts [memberId] in [poolId], or in no pool for null. */
    suspend fun setPool(orgId: Uuid, memberId: Uuid, poolId: String?)
}

/** Every member in no pool: what the pool sees when no [MemberPoolRepository] is given (tests of other features). */
object NoMemberPools : MemberPoolRepository {
    override suspend fun poolOf(orgId: Uuid, memberId: Uuid): String? = null

    override suspend fun poolsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> = emptyMap()

    override suspend fun setPool(orgId: Uuid, memberId: Uuid, poolId: String?) = Unit
}

/** Whether [account] serves a member whose home pool is [home]: the same pool, or both in none (TeamCode's). */
fun SharedAccount.inPool(home: String?): Boolean = poolId == home

/**
 * A member's routing policy as the API names it (TeamCode's `MemberPolicy`, its pool only for now).
 *
 * @property login The member's login.
 * @property memberId The member.
 * @property poolId Their home pool, or null for none.
 */
data class MemberPolicy(val login: String, val memberId: Uuid, val poolId: String?)

/** The members' policies: read by an admin or the member themself, changed by an admin. */
interface MemberPoliciesUseCase {
    /**
     * The policy of the member [login] names.
     *
     * @throws OrgMemberNotFoundException When no member of the organization has this login.
     * @throws AdminRequiredException When a member asks for another member's policy.
     */
    suspend fun get(caller: Caller, login: String): MemberPolicy

    /**
     * Puts the member [login] names in [poolId]; an empty string takes them out of their pool.
     *
     * @throws AdminRequiredException When the caller is not an owner or an admin.
     * @throws InvalidPolicyException When [poolId] is not a pool identifier.
     * @throws OrgMemberNotFoundException When no member of the organization has this login.
     */
    suspend fun setPool(caller: Caller, login: String, poolId: String): MemberPolicy
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
        val memberId = memberOf(caller, login)
        if (memberId != caller.memberId) caller.requireAdmin()
        return MemberPolicy(login, memberId, pools.poolOf(caller.orgId, memberId))
    }

    override suspend fun setPool(caller: Caller, login: String, poolId: String): MemberPolicy {
        caller.requireAdmin()
        val pool = poolId.ifEmpty { null }
        if (pool != null && !SharingRules.isPool(pool)) throw InvalidPolicyException()
        val memberId = memberOf(caller, login)
        pools.setPool(caller.orgId, memberId, pool)
        return MemberPolicy(login, memberId, pool)
    }

    private suspend fun memberOf(caller: Caller, login: String): Uuid =
        logins.idsOf(caller.orgId, listOf(login))[login] ?: throw OrgMemberNotFoundException()
}

/** A policy out of its form: a 400 `invalid_policy` (TeamCode's). */
class InvalidPolicyException :
    OstromException(
        ErrorKind.INVALID_REQUEST,
        "Use a pool identifier containing letters, digits, underscores or hyphens (up to 36 characters).",
        "invalid_policy",
    )
