// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.domain

import run.ostrom.server.gateway.domain.AdmissionQueueRepository
import run.ostrom.server.gateway.domain.MemberRequests
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.usage.domain.SpendReader
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A member's capacity today (TeamCode's `MemberCapacity`, in USD millionths rather than credits).
 *
 * @property login The member's login.
 * @property share Their pool, weight, the pool's weights and budget, and the share they give.
 * @property dailyLimitMicros Their daily limit that stops in force now: the share, their own cap or the default
 *   member's, or a temporary override; null when none applies.
 * @property spentTodayMicros What they spent since 00:00 UTC (settled calls).
 * @property requests The calls they run and have waiting now.
 * @property resetsAt The next 00:00 UTC.
 */
data class MemberCapacity(
    val login: String,
    val share: MemberShare,
    val dailyLimitMicros: Long?,
    val spentTodayMicros: Long,
    val requests: MemberRequests,
    val resetsAt: Instant,
)

/** Reads a member's capacity today. */
interface GetMemberCapacityUseCase {
    /**
     * Reads the capacity of the member [login] names: an owner or an admin any member's, a member their own.
     *
     * @throws run.ostrom.server.secrets.domain.AdminRequiredException When a member who is no admin asks for another
     *   login, a member's or not.
     * @throws BudgetOverrideNotFoundException When no member of the organization has this login (for an admin).
     */
    suspend operator fun invoke(caller: Caller, login: String): MemberCapacity
}

/**
 * [GetMemberCapacityUseCase] on the limits (the share, the caps and the overrides applied), the pools, the ledger and
 * the admission queue.
 *
 * @param repository The limits, as every call reads them.
 * @param shares The members' shares, or null without the sharing module.
 * @param spend The ledger.
 * @param queue The admission queue, or null without the gateway (no call then runs or waits through it).
 * @param logins The orgs module's logins.
 * @param clock The time.
 */
@Suppress("LongParameterList") // The limits, the shares, the ledger, the queue, the logins and the time.
class GetMemberCapacityUseCaseImpl(
    private val repository: BudgetsRepository,
    private val shares: MemberShares?,
    private val spend: SpendReader,
    private val queue: AdmissionQueueRepository?,
    private val logins: MemberLogins,
    private val clock: Clock,
) : GetMemberCapacityUseCase {
    override suspend fun invoke(caller: Caller, login: String): MemberCapacity {
        // A member who is no admin learns nothing of another login, not even whether it names a member: 403 for both.
        val memberId = logins.idsOf(caller.orgId, listOf(login))[login]
        if (memberId != caller.memberId) caller.requireAdmin()
        memberId ?: throw BudgetOverrideNotFoundException("Member")
        val day = BudgetPeriod.day(clock.now())
        val limits = repository.limits(caller.orgId, memberId)
        return MemberCapacity(
            login = login,
            share = if (shares == null) MemberShare.UNKNOWN else shares.of(caller.orgId, memberId, limits.org, true),
            dailyLimitMicros = limits.member.firstOrNull { it.stopsDaily }?.micros,
            spentTodayMicros = spend.spentMicros(caller.orgId, memberId, day.start),
            requests = if (queue == null) MemberRequests(0, 0) else queue.memberRequests(caller.orgId, memberId),
            resetsAt = day.end,
        )
    }
}
