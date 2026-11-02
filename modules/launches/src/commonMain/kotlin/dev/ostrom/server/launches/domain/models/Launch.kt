// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.domain.models

import dev.ostrom.server.kernel.auth.OrgRole
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * One `ostrom <agent>` run: the launch its agent token is scoped to (audit A12). The sessions and gates of the agent
 * carry its id; its token acts only on them and expires with it.
 *
 * @property id The launch id (`lch_` and 32 hex characters), also the subject of its agent token.
 * @property orgId The organization it runs in.
 * @property userId The person who launched it.
 * @property dev The person's login, the `dev` of its sessions and the asker of its gates.
 * @property repo The repository the CLI names (`owner/name`, or the folder's name), or null.
 * @property agent The agent's name in Ostrom (`claude-code`, `codex`…).
 * @property model The model the launch names, or null.
 * @property createdAt When it was created.
 * @property expiresAt When its agent token expires.
 * @property cloudSessionId The cloud session whose runner opened it, or null for a launch of the CLI: only that
 *   runner reports the checks of its sessions as run by Ostrom.
 */
data class Launch(
    val id: String,
    val orgId: Uuid,
    val userId: Uuid,
    val dev: String,
    val repo: String?,
    val agent: String,
    val model: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val cloudSessionId: Uuid? = null,
)

/**
 * The person who asks for a launch, as their credential says: never a name from the request.
 *
 * @property orgId The organization the credential acts in.
 * @property userId The person.
 * @property login Their login: the `dev` of their sessions.
 * @property role Their role in the organization, read at each request.
 */
data class Launcher(val orgId: Uuid, val userId: Uuid, val login: String, val role: OrgRole)

/**
 * What a launch asks for (contract `createLaunch`).
 *
 * @property repo The repository, or null.
 * @property agent The agent's name in Ostrom.
 * @property model The model, or null.
 * @property cloudSessionId The cloud session whose runner asks for it, or null for the CLI.
 */
data class NewLaunch(val repo: String?, val agent: String, val model: String?, val cloudSessionId: Uuid? = null)

/**
 * A launch and the secret of its agent token, given once to the CLI that asked for it.
 *
 * @property launch The launch.
 * @property agentToken The agent token's secret.
 */
data class CreatedLaunch(val launch: Launch, val agentToken: String)

/**
 * The organization's answer to "may this person run this agent with this model" (contract `checkAgentPolicy`).
 *
 * @property allowed Whether the agent may start.
 * @property reason Why not, when it may not.
 */
data class AgentVerdict(val allowed: Boolean, val reason: String? = null)
