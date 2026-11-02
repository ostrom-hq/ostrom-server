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

/**
 * The body of `POST /api/v1/runners` (ostrom-core's operation `registerRunner`).
 *
 * @property name The runner's name, unique in the organization.
 * @property labels What it offers.
 * @property version The version it declares.
 */
@Serializable
data class RegisterRunnerRequest(
    val name: String,
    val labels: List<String>,
    val version: String? = null,
)

/**
 * A runner just registered: the contract's `Runner`, plus its key, shown this once.
 *
 * @property id The runner.
 * @property name Its name.
 * @property labels What it offers.
 * @property status `offline` until its first sign of life.
 * @property lastSeenAt Its last sign of life, absent before the first one.
 * @property version The version it declared.
 * @property token Its key, `ostrom_runner_` and 64 hex characters: returned only here, stored only as a hash.
 */
@Serializable
data class RegisteredRunnerDto(
    val id: String,
    val name: String,
    val labels: List<String>,
    val status: String,
    val lastSeenAt: String? = null,
    val version: String? = null,
    val token: String,
) {
    override fun toString(): String = "RegisteredRunnerDto(id=$id, name=$name, token=***)"
}
