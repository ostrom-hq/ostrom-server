// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/** `/api/v1/pools/size`: the org's automatic daily budget and the estimate behind it (`getPoolSize`). */
@Serializable
@Resource("/api/v1/pools/size")
class PoolSizeApi

/** The contract's `PoolSize`: USD millionths at the gateway's prices, instants in ISO 8601. */
@Serializable
data class PoolSizeResponse(
    val enabled: Boolean,
    val manualDailyMicros: Long? = null,
    val automaticDailyMicros: Long? = null,
    val automaticComputedAt: String? = null,
    val effectiveDailyMicros: Long? = null,
    val source: String,
    val estimatedDailyMicros: Long,
    val reason: String? = null,
    val unpricedModels: List<String>,
    val accounts: List<PoolSizeAccountResponse>,
    val capacityDailyMicros: Long,
    val capacityUnknownAccounts: Int,
    val capacityDailyTokens: Long? = null,
    val tokensPerMicro: Double? = null,
    val outsideWeeklyMicros: Long? = null,
    val outsideUnknownAccounts: Int,
    val models: List<PoolSizeModelResponse>,
)

/** The contract's `PoolSizeAccount`. */
@Serializable
data class PoolSizeAccountResponse(
    val accountId: String,
    val status: String,
    val dailyMicros: Long,
    val binding: String? = null,
    val capacityDailyMicros: Long? = null,
    val windows: List<PoolSizeWindowCapacityResponse>,
    val usage: List<PoolSizeWindowUsageResponse>,
)

/** The contract's `PoolSizeWindowCapacity`. */
@Serializable
data class PoolSizeWindowCapacityResponse(
    val window: String,
    val fullWindowMicros: Long? = null,
    val capacityMicros: Long? = null,
    val samples: Int,
)

/** The contract's `PoolSizeWindowUsage`. */
@Serializable
data class PoolSizeWindowUsageResponse(
    val window: String,
    val since: String,
    val observedAt: String,
    val resetsAt: String,
    val observedUtilization: Double,
    val ostromMicros: Long? = null,
    val ostromUtilization: Double? = null,
    val outsideUtilization: Double? = null,
    val outsideMicros: Long? = null,
    val remainingMicros: Long? = null,
)

/** The contract's `PoolSizeModel`. */
@Serializable
data class PoolSizeModelResponse(
    val provider: String,
    val model: String,
    val inputTokens: Long,
    val cacheWriteTokens: Long,
    val cacheReadTokens: Long,
    val outputTokens: Long,
    val inputMicros: Long? = null,
    val cacheWriteMicros: Long? = null,
    val cacheReadMicros: Long? = null,
    val outputMicros: Long? = null,
    val totalMicros: Long? = null,
    val unpricedTokens: Long,
)
