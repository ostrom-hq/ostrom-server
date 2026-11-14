// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * `/api/v1/forecasts`: the quota forecasts of one shared account (`getForecasts`, `capacity.forecasts.v1`). Its
 * `account` query parameter is read by the route, which refuses it given twice.
 */
@Serializable
@Resource("/api/v1/forecasts")
class ForecastsApi

/** The contract's `ForecastSnapshot`. */
@Serializable
data class ForecastSnapshotResponse(
    val generatedAt: String,
    val accounts: List<ForecastAccountResponse>,
    val account: ForecastAccountResponse? = null,
    val windows: List<ForecastWindowResponse>,
    val truncated: Boolean,
    val pressureThreshold: Double,
)

/** The contract's `ForecastAccount`: never the account's tokens nor its provider account id. */
@Serializable
data class ForecastAccountResponse(
    val id: String,
    val provider: String,
    val label: String,
    val status: String,
    val paused: Boolean,
    val email: String? = null,
)

/** The contract's `ForecastWindow`. */
@Serializable
data class ForecastWindowResponse(
    val window: String,
    val utilization: Double,
    val observedAt: String,
    val resetsAt: String? = null,
    val state: String,
    val pointsPerHour: Double? = null,
    val reachesThresholdAt: String? = null,
    val segments: List<List<ForecastSampleResponse>>,
    val learningSamples: Int,
    val learningSpanMillis: Long,
)

/** The contract's `ForecastSample`: one real reading. */
@Serializable
data class ForecastSampleResponse(val utilization: Double, val observedAt: String, val resetsAt: String? = null)
