package dev.ostrom.server.runners.infrastructure

import dev.ostrom.server.runners.domain.ExecutionSpec

/**
 * The environment of the runner process, the same for every runtime. It holds identifiers and the server URL; the
 * token is in a file (Kubernetes Secret) or, for the Docker runtime only, in `OSTROM_RUNNER_TOKEN`.
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
        if (tokenFile != null) add("OSTROM_RUNNER_TOKEN_FILE" to tokenFile) else add("OSTROM_RUNNER_TOKEN" to spec.runnerToken)
        add("HOME" to "/workspace/.home")
        add("TERM" to "xterm-256color")
    }
}
