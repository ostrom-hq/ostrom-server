// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

// The routes and bodies of `ostrom-core/api/openapi.yaml` (operations `createLaunch` and `checkAgentPolicy`).

/** The launches (contract `createLaunch`). */
@Serializable
@Resource("/api/v1/launches")
class LaunchesApi

/**
 * The organization's policy on launches (contract `checkAgentPolicy`).
 *
 * @property agent The agent's name in Ostrom.
 * @property dev Sent by the CLI; never read: the person is the credential's.
 * @property model The model.
 */
@Serializable
@Resource("/api/v1/policy/agents")
class AgentPolicyApi(val agent: String? = null, val dev: String? = null, val model: String? = null)

/**
 * The body of `POST /api/v1/launches`.
 *
 * @property repo The repository (`owner/name`, or the folder's name).
 * @property agent The agent's name in Ostrom.
 * @property model The model.
 */
@Serializable
data class CreateLaunchRequest(val repo: String? = null, val agent: String, val model: String? = null)

/**
 * A launch created: the only time its agent token's secret is sent.
 *
 * @property launchId The launch.
 * @property agentToken The agent token, which the CLI hands to the hooks through its relay, never the device token.
 * @property expiresAt When the token expires (ISO 8601).
 */
@Serializable
data class LaunchResponse(val launchId: String, val agentToken: String, val expiresAt: String)

/**
 * The answer of `GET /api/v1/policy/agents`.
 *
 * @property allowed Whether the agent may start.
 * @property reason Why not.
 */
@Serializable
data class AgentPolicyResponse(val allowed: Boolean, val reason: String? = null)

/**
 * `POST /api/v1/cloud/runner/launch`: the launch of a cloud session, asked by its runner with the execution's runner
 * token. Not in the contract yet.
 */
@Serializable
@Resource("/api/v1/cloud/runner/launch")
class RunnerLaunchApi

/**
 * The runner's claim.
 *
 * @property sessionId The cloud session the runner was started for.
 * @property epoch The execution it was started for.
 */
@Serializable
data class RunnerLaunchRequest(val sessionId: String, val epoch: Long)
