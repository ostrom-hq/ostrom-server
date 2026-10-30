// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.infrastructure

import dev.ostrom.server.runners.domain.ExecutionSpec

/**
 * The environment of the runner process, the same for every runtime. It holds identifiers and the server URL; the
 * token and the first prompt ([secrets]) are given to the runner's container only, never to the agent's (S2-49).
 */
object RunnerEnvironment {
    /** The variable of the runner token. */
    const val TOKEN = "OSTROM_RUNNER_TOKEN"

    /** The variable of the first message. */
    const val PROMPT = "OSTROM_AGENT_PROMPT"

    /**
     * Builds the environment, without the secrets.
     *
     * @param spec The execution.
     * @param serverUrl The server URL for runners.
     *
     * @return The variables, in a stable order.
     */
    fun of(spec: ExecutionSpec, serverUrl: String): List<Pair<String, String>> = buildList {
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
        add("OSTROM_AGENT_SOCKET" to AGENT_SOCKET)
        add("HOME" to "/workspace/.home")
        add("TERM" to "xterm-256color")
    }

    /**
     * The runner's secrets: the token, and the first message when there is one.
     *
     * @param spec The execution.
     *
     * @return The variables.
     */
    fun secrets(spec: ExecutionSpec): List<Pair<String, String>> =
        listOf(TOKEN to spec.runnerToken) + listOfNotNull(spec.prompt?.let { PROMPT to it })

    /** The socket between the runner and the agent host, on the volume both containers mount. */
    const val AGENT_SOCKET_DIRECTORY = "/run/ostrom"
    const val AGENT_SOCKET = "$AGENT_SOCKET_DIRECTORY/agent.sock"
}
