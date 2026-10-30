// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.domain.usecases

import dev.ostrom.server.launches.domain.models.Launcher
import dev.ostrom.server.launches.domain.services.LaunchModelRules

/**
 * The organization's policy on launches, read by the CLI before it starts an agent (`GET /api/v1/policy/agents`) and
 * enforced again when the launch is created: a CLI that skips the read is refused all the same.
 *
 * Every well-formed agent name is allowed (the prototype's empty agent list); a model must be well formed and allowed
 * by the organization's [LaunchModelRules].
 *
 * @param models The organization's rules on models.
 */
class LaunchPolicy(private val models: LaunchModelRules) {
    /**
     * Says why [launcher] may not run [agent] with [model].
     *
     * @param launcher The person and their organization.
     * @param agent The agent's name in Ostrom, or null when not named.
     * @param model The model, or null when not named.
     *
     * @return The refusal, or null when the launch is allowed.
     */
    suspend fun refusal(launcher: Launcher, agent: String?, model: String?): String? = when {
        agent != null && !AGENT_NAME.matches(agent) -> "The agent name is not valid."
        model == null -> null
        !MODEL_NAME.matches(model) -> "The model name is not valid."
        else -> models.refusal(launcher.orgId, launcher.login, model)
    }

    /** The names a launch accepts. */
    companion object {
        /** An agent's name in Ostrom: `claude-code`, `codex`, `opencode`… */
        val AGENT_NAME: Regex = Regex("[a-z0-9][a-z0-9._-]{0,63}")

        /** A model name: `claude-opus-4-1[1m]`, `anthropic/claude-sonnet-4.5`, `gpt-5.1-codex`… */
        val MODEL_NAME: Regex = Regex("[A-Za-z0-9][A-Za-z0-9._:/@+\\[\\]-]{0,199}")
    }
}
