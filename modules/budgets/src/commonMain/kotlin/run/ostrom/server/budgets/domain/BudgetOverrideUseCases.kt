// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.domain

import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.secrets.domain.Caller
import kotlin.uuid.Uuid

/**
 * Overrides with the logins of the people they name (their member, grantor and revoker), for the API.
 *
 * @property items The overrides.
 * @property logins The login of each member id they name that is still a member.
 * @property activeId The override in force, for a history.
 * @property next The `before` of the following page.
 * @property superseded For a grant, the override it replaced.
 * @property changed False for a revocation of an override already revoked: nothing was written.
 */
data class NamedBudgetOverrides(
    val items: List<BudgetOverride>,
    val logins: Map<Uuid, String>,
    val activeId: Uuid? = null,
    val next: Uuid? = null,
    val superseded: Uuid? = null,
    val changed: Boolean = true,
)

/** Reads a member's overrides. */
interface ListBudgetOverridesUseCase {
    /**
     * Reads a page, newest first.
     *
     * @param caller An owner or admin.
     * @param login The member's login.
     * @param before The id of the last item of the previous page.
     *
     * @return The page.
     *
     * @throws run.ostrom.server.secrets.domain.AdminRequiredException When the caller is not an admin.
     * @throws BudgetOverrideNotFoundException No such member, or [before] is not one of their overrides.
     */
    suspend operator fun invoke(caller: Caller, login: String, before: String?): NamedBudgetOverrides
}

/** Grants a temporary daily budget. */
interface GrantBudgetOverrideUseCase {
    /**
     * Grants it.
     *
     * @param caller An owner or admin.
     * @param login The member's login.
     * @param grant The grant.
     *
     * @return The new override (one item), and the one it replaced.
     *
     * @throws InvalidBudgetOverrideException Out of bounds, or the history is full.
     * @throws StaleBudgetOverrideException `replacesId` is not the override in force.
     */
    suspend operator fun invoke(caller: Caller, login: String, grant: BudgetOverrideGrant): NamedBudgetOverrides
}

/** Revokes a temporary daily budget. */
interface RevokeBudgetOverrideUseCase {
    /**
     * Revokes it; one already revoked is answered as it is.
     *
     * @param caller An owner or admin.
     * @param login The member's login.
     * @param id The override.
     * @param reason Why.
     *
     * @return The override (one item), and whether this call revoked it ([NamedBudgetOverrides.changed]).
     *
     * @throws StaleBudgetOverrideException It already expired.
     */
    suspend operator fun invoke(caller: Caller, login: String, id: String, reason: String): NamedBudgetOverrides
}

/**
 * The member named by [login] in the caller's org, after checking the caller administers it.
 *
 * @throws BudgetOverrideNotFoundException No such member.
 */
private suspend fun MemberLogins.memberOf(caller: Caller, login: String): Uuid {
    caller.requireAdmin()
    return idsOf(caller.orgId, listOf(login))[login] ?: throw BudgetOverrideNotFoundException("Member")
}

private suspend fun MemberLogins.named(
    caller: Caller,
    items: List<BudgetOverride>,
    activeId: Uuid? = null,
    next: Uuid? = null,
    superseded: Uuid? = null,
): NamedBudgetOverrides {
    val ids = items.flatMap { listOfNotNull(it.memberId, it.createdBy, it.revokedBy) }.toSet()
    val logins = if (ids.isEmpty()) emptyMap() else loginsOf(caller.orgId, ids)
    return NamedBudgetOverrides(items, logins, activeId, next, superseded)
}

/**
 * [ListBudgetOverridesUseCase] on the repository.
 *
 * @param repository The overrides.
 * @param logins The members' logins.
 */
class ListBudgetOverridesUseCaseImpl(
    private val repository: BudgetOverridesRepository,
    private val logins: MemberLogins,
) : ListBudgetOverridesUseCase {
    override suspend fun invoke(caller: Caller, login: String, before: String?): NamedBudgetOverrides {
        val memberId = logins.memberOf(caller, login)
        val cursor = before?.let { Uuid.parseOrNull(it) ?: throw BudgetOverrideNotFoundException("Override") }
        val page = repository.history(caller.orgId, memberId, cursor)
            ?: throw BudgetOverrideNotFoundException("Override")
        return logins.named(caller, page.items, page.activeId, page.next)
    }
}

/**
 * [GrantBudgetOverrideUseCase] on the repository.
 *
 * @param repository The overrides.
 * @param logins The members' logins.
 */
class GrantBudgetOverrideUseCaseImpl(
    private val repository: BudgetOverridesRepository,
    private val logins: MemberLogins,
) : GrantBudgetOverrideUseCase {
    override suspend fun invoke(caller: Caller, login: String, grant: BudgetOverrideGrant): NamedBudgetOverrides {
        val memberId = logins.memberOf(caller, login)
        BudgetOverrideRules.requireGrant(grant)
        val checked = grant.copy(reason = BudgetOverrideRules.reason(grant.reason))
        val outcome = repository.grant(caller.orgId, memberId, caller.memberId, checked) { now ->
            BudgetOverrideRules.expiresAt(now, grant.durationMinutes)
        }
        return when (outcome) {
            is GrantOutcome.Granted ->
                logins.named(caller, listOf(outcome.override), superseded = outcome.superseded)
            GrantOutcome.Stale -> throw StaleBudgetOverrideException(
                "The override in force changed or ended: read the member's overrides again before replacing it.",
            )
            GrantOutcome.HistoryFull -> throw InvalidBudgetOverrideException(
                "This member or organization keeps as many overrides as allowed " +
                    "(${BudgetOverrideRules.MAX_MEMBER_HISTORY} a member, ${BudgetOverrideRules.MAX_ORG_HISTORY} " +
                    "an organization).",
            )
        }
    }
}

/**
 * [RevokeBudgetOverrideUseCase] on the repository.
 *
 * @param repository The overrides.
 * @param logins The members' logins.
 */
class RevokeBudgetOverrideUseCaseImpl(
    private val repository: BudgetOverridesRepository,
    private val logins: MemberLogins,
) : RevokeBudgetOverrideUseCase {
    override suspend fun invoke(caller: Caller, login: String, id: String, reason: String): NamedBudgetOverrides {
        val memberId = logins.memberOf(caller, login)
        val overrideId = Uuid.parseOrNull(id) ?: throw BudgetOverrideNotFoundException("Override")
        val checked = BudgetOverrideRules.reason(reason)
        return when (val outcome = repository.revoke(caller.orgId, memberId, overrideId, caller.memberId, checked)) {
            is RevokeOutcome.Revoked -> logins.named(caller, listOf(outcome.override))
            is RevokeOutcome.AlreadyRevoked -> logins.named(caller, listOf(outcome.override)).copy(changed = false)
            RevokeOutcome.Expired -> throw StaleBudgetOverrideException("This override already ended.")
            RevokeOutcome.NotFound -> throw BudgetOverrideNotFoundException("Override")
        }
    }
}
