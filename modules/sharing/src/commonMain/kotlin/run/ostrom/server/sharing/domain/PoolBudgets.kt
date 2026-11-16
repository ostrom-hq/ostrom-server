// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.kernel.sharing.SharedAccountSpend
import run.ostrom.server.secrets.domain.AdminRequiredException
import run.ostrom.server.secrets.domain.Caller
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * A named pool's daily budget (TeamCode's `PoolBudget`, in USD millionths rather than credits).
 *
 * @property poolId The pool.
 * @property dailyMicros What it may lend a UTC day, USD millionths.
 * @property sharedOverflow Whether members of other pools allowed overflow may borrow its accounts.
 */
data class PoolBudget(val poolId: String, val dailyMicros: Long, val sharedOverflow: Boolean)

/**
 * A shared overflow pool a member may borrow (TeamCode's `sharedOverflowBudgets`): shared, and no member's home pool.
 *
 * @property poolId The pool.
 * @property dailyMicros Its daily budget, USD millionths.
 * @property accountIds Its accounts, of every provider and state: what any of them served today counts.
 */
data class OverflowReserve(val poolId: String, val dailyMicros: Long, val accountIds: Set<Uuid>)

/** What saving a pool budget did. */
enum class PoolBudgetSave {
    /** Saved. */
    SAVED,

    /** Not saved: the pool is shared and some member's home pool. */
    PRIMARY_POOL,

    /** Not saved: the pool is shared and [PoolBudgets.MAX_OVERFLOW_POOLS] other pools already are. */
    LIMIT,
}

/** The pools' budgets of each organization; every call is confined to the organization it names. */
interface PoolBudgetRepository {
    /** The budgets of [orgId], by pool. */
    suspend fun list(orgId: Uuid): List<PoolBudget>

    /**
     * Creates or replaces [budget] under the organization's pools lock, which [MemberPoolRepository.setPolicy] takes
     * too, so that a pool never becomes both shared and someone's home.
     */
    suspend fun save(orgId: Uuid, budget: PoolBudget): PoolBudgetSave

    /** The shared overflow pools of [orgId] no member calls home, at most [PoolBudgets.MAX_OVERFLOW_POOLS]. */
    suspend fun reserves(orgId: Uuid): List<OverflowReserve>
}

/** The rules of the pool budgets (TeamCode's). */
object PoolBudgets {
    /** The most shared overflow pools of an organization. */
    const val MAX_OVERFLOW_POOLS: Int = 16

    /** The highest daily budget: USD 100,000. */
    const val MAX_DAILY_MICROS: Long = 100_000_000_000
}

/**
 * The shared overflow pools that still cover a request (TeamCode's admission in `accountPlacement`): a pool is open
 * while its daily budget minus what its accounts served today covers what the request may cost.
 */
fun interface OverflowPools {
    /**
     * The pools of [orgId] whose remainder today covers [needMicros], asked only when a pool is shared.
     *
     * @param needMicros What the request may cost, USD millionths; 1 is counted at least, so a spent pool is closed.
     */
    suspend fun funded(orgId: Uuid, needMicros: suspend () -> Long): Set<String>
}

/** No overflow pool: what the lease and the controller see when sharing's repositories are not given. */
object NoOverflowPools : OverflowPools {
    override suspend fun funded(orgId: Uuid, needMicros: suspend () -> Long): Set<String> = emptySet()
}

/**
 * [OverflowPools] on the reserves and the ledger: two reads of the reserves and one of the spend of their accounts
 * since the start of the UTC day (TeamCode's `poolSpends`; no member calls a reserve home, so only what it lent
 * counts).
 *
 * @param budgets The pools' budgets.
 * @param spend What the accounts served.
 * @param clock The time.
 */
class OverflowPoolsImpl(
    private val budgets: PoolBudgetRepository,
    private val spend: SharedAccountSpend,
    private val clock: Clock,
) : OverflowPools {
    override suspend fun funded(orgId: Uuid, needMicros: suspend () -> Long): Set<String> {
        val reserves = budgets.reserves(orgId)
        if (reserves.isEmpty()) return emptySet()
        val need = needMicros().coerceAtLeast(1)
        val spent = spend.spentMicros(orgId, reserves.flatMap { it.accountIds }, CivilCalendar.dayStart(clock.now()))
        return reserves.filter { reserve -> need <= reserve.dailyMicros - reserve.accountIds.sumOf { spent[it] ?: 0 } }
            .mapTo(mutableSetOf()) { it.poolId }
    }
}

/** The pools' budgets: read by an admin (or a `team:read` key), changed by an admin. */
interface PoolBudgetsUseCase {
    /**
     * The budgets, by pool.
     *
     * @throws AdminRequiredException When the caller is not an owner or an admin.
     */
    suspend fun list(caller: Caller): List<PoolBudget>

    /**
     * Creates or replaces a pool's budget.
     *
     * @throws AdminRequiredException When the caller is not an owner or an admin.
     * @throws InvalidPoolBudgetException When the pool or the budget is out of its form, or names the home pool.
     * @throws InvalidOverflowPoolException When the home pool would be shared.
     * @throws OverflowPrimaryPoolException When a shared pool is some member's home pool.
     * @throws OverflowPoolLimitException When [PoolBudgets.MAX_OVERFLOW_POOLS] other pools are shared.
     */
    suspend fun save(caller: Caller, budget: PoolBudget): PoolBudget
}

/**
 * [PoolBudgetsUseCase] on the repository (TeamCode's `configurePool`).
 *
 * @param budgets The pools' budgets.
 */
class PoolBudgetsUseCaseImpl(private val budgets: PoolBudgetRepository) : PoolBudgetsUseCase {
    override suspend fun list(caller: Caller): List<PoolBudget> {
        caller.requireAdmin()
        return budgets.list(caller.orgId)
    }

    override suspend fun save(caller: Caller, budget: PoolBudget): PoolBudget {
        caller.requireAdmin()
        val named = budget.poolId.isEmpty() || SharingRules.isPool(budget.poolId)
        if (budget.dailyMicros !in 1..PoolBudgets.MAX_DAILY_MICROS || !named) {
            throw InvalidPoolBudgetException("Invalid pool budget or identifier.")
        }
        if (budget.poolId.isEmpty()) {
            if (budget.sharedOverflow) throw InvalidOverflowPoolException()
            // The home pool's daily budget is the organization's (budgets module), never a second figure here.
            throw InvalidPoolBudgetException(
                "The home pool's daily budget is the organization's (PUT /api/v1/budgets).",
            )
        }
        return when (budgets.save(caller.orgId, budget)) {
            PoolBudgetSave.SAVED -> budget
            PoolBudgetSave.PRIMARY_POOL ->
                throw OverflowPrimaryPoolException(
                    "Remove every primary member assignment before sharing this reserve.",
                )
            PoolBudgetSave.LIMIT -> throw OverflowPoolLimitException()
        }
    }
}

/** A pool budget out of its form: a 400 `invalid_pool_budget` (TeamCode's). */
class InvalidPoolBudgetException(message: String) :
    OstromException(ErrorKind.INVALID_REQUEST, message, "invalid_pool_budget")

/** The home pool cannot be shared: a 400 `invalid_overflow_pool` (TeamCode's). */
class InvalidOverflowPoolException :
    OstromException(ErrorKind.INVALID_REQUEST, "Shared overflow requires a named pool.", "invalid_overflow_pool")

/** A pool both shared and someone's home: a 409 `overflow_primary_pool` (TeamCode's). */
class OverflowPrimaryPoolException(message: String) :
    OstromException(ErrorKind.CONFLICT, message, "overflow_primary_pool")

/** Too many shared pools: a 409 `overflow_pool_limit` (TeamCode's). */
class OverflowPoolLimitException :
    OstromException(
        ErrorKind.CONFLICT,
        "At most ${PoolBudgets.MAX_OVERFLOW_POOLS} shared overflow pools are allowed.",
        "overflow_pool_limit",
    )
