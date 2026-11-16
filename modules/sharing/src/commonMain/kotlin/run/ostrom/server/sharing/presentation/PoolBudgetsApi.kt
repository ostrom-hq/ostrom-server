// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/** `/api/v1/pools/budgets`: the named pools' daily budgets and which lend to other pools (TeamCode's). */
@Serializable
@Resource("/api/v1/pools/budgets")
class PoolBudgetsApi

/**
 * A pool's budget (TeamCode's `PoolBudgetView`, in USD millionths rather than credits), read and written alike.
 *
 * @property poolId The named pool: 1 to 36 letters, digits, underscores or hyphens.
 * @property dailyMicros What it may lend a UTC day, USD millionths: 1 to 100,000,000,000.
 * @property sharedOverflow Whether members of other pools allowed overflow may borrow its accounts.
 */
@Serializable
data class PoolBudgetDto(val poolId: String, val dailyMicros: Long, val sharedOverflow: Boolean = false)
