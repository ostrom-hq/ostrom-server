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
 * One launch (contract `endLaunch`).
 *
 * @property launchId The launch.
 */
@Serializable
@Resource("/api/v1/launches/{launchId}")
class LaunchApi(val launchId: String)

/**
 * The CLI's report that no hook ran in a launch's session (contract `reportHooksMissing`, not in the contract yet).
 *
 * @property launchId The launch.
 */
@Serializable
@Resource("/api/v1/launches/{launchId}/hooks-missing")
class HooksMissingApi(val launchId: String)

/**
 * The organization's policy on launches (contract `checkAgentPolicy`).
 *
 * @property agent The agent's name in Ostrom.
 * @property dev The developer the verdict is for, by login: the caller when absent or their own; anyone else's
 * verdict is read by an owner or an admin of the organization only.
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
 * @property workflowId The workflow the launch plays, one of the organization's.
 */
@Serializable
data class CreateLaunchRequest(
    val repo: String? = null,
    val agent: String,
    val model: String? = null,
    val workflowId: String? = null,
)

/**
 * What the CLI does when no hook of Ostrom ran in the agent's session.
 *
 * @property afterSeconds How long it waits for the first hook before it concludes none runs (the organization's
 *   `hooksDeadlineSeconds`).
 * @property stopAgent Whether it then stops the agent: the organization enforces a gate, which the agent would skip.
 */
@Serializable
data class HooksCheckResponse(val afterSeconds: Int, val stopAgent: Boolean)

/**
 * A launch created: the only time its agent token's secret is sent.
 *
 * @property launchId The launch.
 * @property agentToken The agent token, which the CLI hands to the hooks through its relay, never the device token.
 * @property expiresAt When the token expires (ISO 8601).
 * @property hooksCheck When to look for the hooks and whether to stop an agent without them; absent from a server
 *   that does not tell, which a CLI reads as "do not check".
 */
@Serializable
data class LaunchResponse(
    val launchId: String,
    val agentToken: String,
    val expiresAt: String,
    val hooksCheck: HooksCheckResponse? = null,
)

/**
 * The body of `POST /api/v1/launches/{launchId}/hooks-missing`.
 *
 * @property waitedSeconds How long the CLI waited for the first hook, from 0 to 3600.
 * @property stopped Whether it stopped the agent.
 */
@Serializable
data class HooksMissingRequest(val waitedSeconds: Int, val stopped: Boolean)

/**
 * The answer of `GET /api/v1/policy/agents`.
 *
 * @property allowed Whether the agent may start.
 * @property reason Why not.
 * @property note What a rule of the policy tells the developer, when it has something to say.
 */
@Serializable
data class AgentPolicyResponse(val allowed: Boolean, val reason: String? = null, val note: String? = null)

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
