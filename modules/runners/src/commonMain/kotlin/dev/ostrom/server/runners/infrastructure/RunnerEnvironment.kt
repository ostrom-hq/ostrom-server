// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.infrastructure

import dev.ostrom.server.runners.domain.ExecutionSpec

/**
 * The environment of the runner process, the same for every runtime. It holds identifiers and the server URL; the
 * token and the first prompt are files of a Kubernetes Secret or, for the Docker runtime only, variables.
 */
object RunnerEnvironment {
    /**
     * Builds the environment.
     *
     * @param spec The execution.
     * @param serverUrl The server URL for runners.
     * @param tokenFile Where the token file is mounted, or null to pass the token itself.
     *
     * @return The variables, in a stable order.
     */
    fun of(spec: ExecutionSpec, serverUrl: String, tokenFile: String?): List<Pair<String, String>> = buildList {
        add("OSTROM_SERVER_URL" to serverUrl)
        add("OSTROM_SESSION_ID" to spec.sessionId.toString())
        add("OSTROM_EPOCH" to spec.epoch.toString())
        add("OSTROM_AGENT" to spec.agent.wire)
        add("OSTROM_AGENT_COMMAND" to spec.agent.command)
        spec.repository?.let { repository ->
            add("OSTROM_REPOSITORY_URL" to repository.url)
            repository.ref?.let { add("OSTROM_REPOSITORY_REF" to it) }
        }
        spec.model?.let { add("OSTROM_AGENT_MODEL" to it) }
        if (tokenFile != null) {
            add("OSTROM_RUNNER_TOKEN_FILE" to tokenFile)
            // The first message is a file of the same Secret: a pod's spec is readable by more than its runner.
            if (spec.prompt != null) add("OSTROM_AGENT_PROMPT_FILE" to tokenFile.substringBeforeLast('/') + "/prompt")
        } else {
            add("OSTROM_RUNNER_TOKEN" to spec.runnerToken)
            spec.prompt?.let { add("OSTROM_AGENT_PROMPT" to it) }
        }
        add("HOME" to "/workspace/.home")
        add("TERM" to "xterm-256color")
    }
}
