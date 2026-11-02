// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/** `GET /api/v1/runners`: the runners at work for the caller (ostrom-core's operation `listRunners`). */
@Serializable
@Resource("/api/v1/runners")
class RunnersApi

/**
 * A runner, as the contract's `Runner`.
 *
 * @property id The id of the cloud session it runs: one runner per session execution.
 * @property name The session's name.
 * @property labels Where it runs (`kubernetes`, `docker`) and the agent it runs.
 * @property status `online` when it reported within the last 90 s, `offline` otherwise (starting, or lost).
 * @property lastSeenAt Its last heartbeat (ISO 8601), absent before the first one.
 * @property version The runner image's tag.
 */
@Serializable
data class RunnerDto(
    val id: String,
    val name: String,
    val labels: List<String>,
    val status: String,
    val lastSeenAt: String? = null,
    val version: String? = null,
)

/**
 * The runners, as the contract's page; never cut, so without `next`.
 *
 * @property items The runners.
 */
@Serializable
data class RunnersPage(val items: List<RunnerDto>)
