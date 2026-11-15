// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A temporary daily budget granted to a member (TeamCode's temporary quota override, docs/budget-overrides.md): while
 * it lasts, [micros] is the member's own daily limit that stops the calls, the total they may spend today, never an
 * addition nor a refund. Kept after it ends or is revoked: a revocation adds its actor, time and reason.
 *
 * @property id Its id.
 * @property memberId The member.
 * @property micros The member's total daily allowance while it lasts, USD millionths.
 * @property createdAt When it was granted, by the database's clock.
 * @property expiresAt The earlier of the requested duration and the next midnight UTC.
 * @property reason Why, one line.
 * @property createdBy The administrator who granted it.
 * @property replacesId The override it replaced, if any.
 * @property revokedAt When it was revoked (or replaced), if it was.
 * @property revokedBy Who revoked it.
 * @property revocationReason Why.
 */
data class BudgetOverride(
    val id: Uuid,
    val memberId: Uuid,
    val micros: Long,
    val createdAt: Instant,
    val expiresAt: Instant,
    val reason: String,
    val createdBy: Uuid,
    val replacesId: Uuid? = null,
    val revokedAt: Instant? = null,
    val revokedBy: Uuid? = null,
    val revocationReason: String? = null,
) {
    /** What its member sees of it. */
    fun summary(): ActiveBudgetOverride = ActiveBudgetOverride(id, micros, expiresAt)
}

/**
 * The override in force, as its member sees it: no reason, no grantor.
 *
 * @property id Its id.
 * @property micros The member's daily allowance while it lasts.
 * @property expiresAt When it ends.
 */
data class ActiveBudgetOverride(val id: Uuid, val micros: Long, val expiresAt: Instant)

/**
 * A member's overrides, newest first.
 *
 * @property items At most [BudgetOverrideRules.PAGE_SIZE].
 * @property activeId The one in force now, if any.
 * @property next The `before` of the following page, absent on the last.
 */
data class BudgetOverridePage(val items: List<BudgetOverride>, val activeId: Uuid?, val next: Uuid?)

/**
 * A grant asked by an administrator.
 *
 * @property micros The member's total daily allowance.
 * @property durationMinutes 1 to 1440.
 * @property reason One line.
 * @property replacesId The override in force, or null when there is none.
 */
data class BudgetOverrideGrant(val micros: Long, val durationMinutes: Int, val reason: String, val replacesId: Uuid?)

/** What a grant did, in the transaction that read the override in force. */
sealed interface GrantOutcome {
    /**
     * Granted.
     *
     * @property override The new override.
     * @property superseded The override it replaced, revoked in the same transaction.
     */
    data class Granted(val override: BudgetOverride, val superseded: Uuid?) : GrantOutcome

    /** `replacesId` is not the override in force (another administrator changed it, or it expired). */
    data object Stale : GrantOutcome

    /** The member or the organization keeps as many overrides as the bounds allow. */
    data object HistoryFull : GrantOutcome
}

/** What a revocation did. */
sealed interface RevokeOutcome {
    /**
     * Revoked now.
     *
     * @property override The override, revoked.
     */
    data class Revoked(val override: BudgetOverride) : RevokeOutcome

    /**
     * Already revoked: the first revocation is kept.
     *
     * @property override The override as it is.
     */
    data class AlreadyRevoked(val override: BudgetOverride) : RevokeOutcome

    /** It already expired. */
    data object Expired : RevokeOutcome

    /** No override of that member has this id. */
    data object NotFound : RevokeOutcome
}

/** The overrides, every read and write scoped by org; times are the database's. */
interface BudgetOverridesRepository {
    /**
     * The member's override in force now.
     *
     * @param orgId The org.
     * @param memberId The member.
     *
     * @return It, or null.
     */
    suspend fun active(orgId: Uuid, memberId: Uuid): ActiveBudgetOverride?

    /**
     * A page of the member's overrides, newest first.
     *
     * @param orgId The org.
     * @param memberId The member.
     * @param before The id of the last item of the previous page, or null for the first.
     *
     * @return The page, or null when [before] is not one of the member's overrides.
     */
    suspend fun history(orgId: Uuid, memberId: Uuid, before: Uuid?): BudgetOverridePage?

    /**
     * Grants an override under the org's lock: checks `replacesId` against the override in force and the bounds,
     * revokes the one it replaces, then inserts the new one, all in one transaction.
     *
     * @param orgId The org.
     * @param memberId The member.
     * @param actor The administrator.
     * @param grant The grant, its reason already checked.
     * @param expiry The end of a grant made at the database's time given.
     *
     * @return What it did.
     */
    suspend fun grant(
        orgId: Uuid,
        memberId: Uuid,
        actor: Uuid,
        grant: BudgetOverrideGrant,
        expiry: (Instant) -> Instant,
    ): GrantOutcome

    /**
     * Revokes an override that is in force.
     *
     * @param orgId The org.
     * @param memberId The member.
     * @param id The override.
     * @param actor The administrator.
     * @param reason Why, already checked.
     *
     * @return What it did.
     */
    suspend fun revoke(orgId: Uuid, memberId: Uuid, id: Uuid, actor: Uuid, reason: String): RevokeOutcome
}

/** The bounds of overrides (TeamCode's `CheckBudgetOverrideUseCase` and `BudgetOverrideServiceImpl`). */
object BudgetOverrideRules {
    /** The largest daily allowance: USD 100 000 000, in millionths. */
    const val MAX_MICROS: Long = 100_000_000_000_000L

    /** The longest duration, a day. */
    const val MAX_MINUTES: Int = 1440

    /** The longest reason. */
    const val MAX_REASON: Int = 240

    /** Overrides a page lists at most. */
    const val PAGE_SIZE: Int = 50

    /** Overrides kept per member: past them, grants are refused. */
    const val MAX_MEMBER_HISTORY: Long = 5000

    /** Overrides kept per organization. */
    const val MAX_ORG_HISTORY: Long = 100_000

    /**
     * Refuses an allowance or a duration out of range.
     *
     * @param grant The grant.
     *
     * @throws InvalidBudgetOverrideException When out of range.
     */
    fun requireGrant(grant: BudgetOverrideGrant) {
        if (grant.micros !in 0..MAX_MICROS || grant.durationMinutes !in 1..MAX_MINUTES) {
            throw InvalidBudgetOverrideException(
                "Use a daily allowance of 0 to $MAX_MICROS USD millionths and a duration of 1 to $MAX_MINUTES minutes.",
            )
        }
    }

    /**
     * The reason, trimmed: one line of 1 to [MAX_REASON] characters, without a control character.
     *
     * @param value The reason as sent.
     *
     * @return It, trimmed.
     *
     * @throws InvalidBudgetOverrideException Otherwise.
     */
    fun reason(value: String): String = value.trim().also { trimmed ->
        if (trimmed.length !in 1..MAX_REASON || trimmed.any { it < ' ' || it == '\u007f' }) {
            throw InvalidBudgetOverrideException(
                "Give a single-line reason of 1 to $MAX_REASON characters, without secrets.",
            )
        }
    }

    /**
     * When a grant made at [now] ends: after [minutes], or at the next midnight UTC if sooner.
     *
     * @param now The database's time.
     * @param minutes The duration asked.
     *
     * @return The end.
     */
    fun expiresAt(now: Instant, minutes: Int): Instant = minOf(now + minutes.minutes, BudgetPeriod.day(now).end)
}

/** An override the bounds refuse (400 `invalid_budget_override`). */
class InvalidBudgetOverrideException(message: String) :
    OstromException(ErrorKind.INVALID_REQUEST, message, "invalid_budget_override")

/** `replacesId` is stale, or the override to revoke already expired (409 `stale_budget_override`). */
class StaleBudgetOverrideException(message: String) :
    OstromException(ErrorKind.CONFLICT, message, "stale_budget_override")

/** No such override of that member, or no such member (404). */
class BudgetOverrideNotFoundException(what: String) : OstromException(ErrorKind.NOT_FOUND, "$what not found.")
