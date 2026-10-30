package dev.ostrom.runner

import kotlin.uuid.Uuid

/**
 * What the runner learns from its pod (`RunnerEnvironment` on the server writes these variables).
 *
 * @property serverUrl The server, as the pod reaches it (`http://`: the in-cluster or local URL).
 * @property sessionId The cloud session.
 * @property epoch The execution.
 * @property agentCommand The agent's command (`claude`, `codex`, `opencode`, `gemini`, `bash`).
 * @property repositoryUrl The repository to clone, if any.
 * @property repositoryRef The branch, tag or commit to check out, if any.
 * @property token The runner token.
 * @property workspace The workspace volume.
 */
data class RunnerConfig(
    val serverUrl: String,
    val sessionId: Uuid,
    val epoch: Long,
    val agentCommand: String,
    val repositoryUrl: String?,
    val repositoryRef: String?,
    val token: String,
    val workspace: String,
) {
    /** The token never appears in logs. */
    override fun toString(): String = "RunnerConfig(serverUrl=$serverUrl, sessionId=$sessionId, epoch=$epoch, agent=$agentCommand)"

    companion object {
        /** Variables only the runner reads; never passed to the agent. */
        val PRIVATE_VARIABLES = setOf("OSTROM_RUNNER_TOKEN", "OSTROM_RUNNER_TOKEN_FILE")

        /**
         * Reads the configuration.
         *
         * @param environment The process environment.
         * @param readFile Reads the token file.
         *
         * @return The configuration.
         *
         * @throws IllegalArgumentException When a variable is missing or malformed.
         */
        fun from(environment: Map<String, String>, readFile: (String) -> String): RunnerConfig {
            fun required(name: String) = environment[name]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("$name is required.")
            val serverUrl = required("OSTROM_SERVER_URL").trimEnd('/')
            require(serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) { "OSTROM_SERVER_URL must be an http(s) URL." }
            val token = environment["OSTROM_RUNNER_TOKEN"]?.takeIf { it.isNotBlank() }
                ?: environment["OSTROM_RUNNER_TOKEN_FILE"]?.takeIf { it.isNotBlank() }?.let { readFile(it).trim() }
                ?: throw IllegalArgumentException("OSTROM_RUNNER_TOKEN or OSTROM_RUNNER_TOKEN_FILE is required.")
            require(token.isNotEmpty()) { "The runner token is empty." }
            return RunnerConfig(
                serverUrl = serverUrl,
                sessionId = Uuid.parseOrNull(required("OSTROM_SESSION_ID")) ?: throw IllegalArgumentException("OSTROM_SESSION_ID is not a UUID."),
                epoch = required("OSTROM_EPOCH").toLongOrNull() ?: throw IllegalArgumentException("OSTROM_EPOCH is not a number."),
                agentCommand = required("OSTROM_AGENT_COMMAND"),
                repositoryUrl = environment["OSTROM_REPOSITORY_URL"]?.takeIf { it.isNotBlank() },
                repositoryRef = environment["OSTROM_REPOSITORY_REF"]?.takeIf { it.isNotBlank() },
                token = token,
                workspace = environment["OSTROM_WORKSPACE"]?.takeIf { it.isNotBlank() } ?: DEFAULT_WORKSPACE,
            )
        }

        const val DEFAULT_WORKSPACE = "/workspace"
    }
}

/** How the session's terminal starts: a shell script that prepares the workspace then runs the agent. */
object SessionScript {
    /**
     * The script of the first terminal: clone the repository once (never reset afterwards), enter it, run the
     * agent, then leave a login shell so the member can start the agent again. Values come from the
     * environment, never from the script's text.
     */
    const val FIRST = """
set -u
mkdir -p "${'$'}HOME"
if [ -n "${'$'}{OSTROM_REPOSITORY_URL:-}" ]; then
  dir="${'$'}OSTROM_WORKSPACE/${'$'}(basename "${'$'}OSTROM_REPOSITORY_URL" .git)"
  if [ ! -d "${'$'}dir/.git" ]; then
    rm -rf "${'$'}dir.clone"
    echo "Cloning ${'$'}OSTROM_REPOSITORY_URL…"
    if git clone "${'$'}OSTROM_REPOSITORY_URL" "${'$'}dir.clone"; then
      if [ -n "${'$'}{OSTROM_REPOSITORY_REF:-}" ]; then git -C "${'$'}dir.clone" checkout "${'$'}OSTROM_REPOSITORY_REF" || echo "Could not check out ${'$'}OSTROM_REPOSITORY_REF."; fi
      mv "${'$'}dir.clone" "${'$'}dir"
    else
      echo "The clone failed: the session starts in ${'$'}OSTROM_WORKSPACE."
    fi
  fi
  [ -d "${'$'}dir" ] && cd "${'$'}dir"
fi
"${'$'}OSTROM_AGENT_COMMAND"
echo
echo "The agent exited. Run ${'$'}OSTROM_AGENT_COMMAND to start it again."
exec bash -l
"""

    /** The script of a terminal started again after its shell ended. */
    const val AGAIN = """
cd "${'$'}OSTROM_WORKSPACE"
exec bash -l
"""

    /**
     * The agent's environment: the runner's, without its private variables, with the workspace and terminal.
     *
     * @param environment The runner's environment.
     * @param config The runner's configuration.
     *
     * @return The variables.
     */
    fun environment(environment: Map<String, String>, config: RunnerConfig): Map<String, String> =
        environment.filterKeys { it !in RunnerConfig.PRIVATE_VARIABLES } + mapOf(
            "OSTROM_WORKSPACE" to config.workspace,
            "HOME" to (environment["HOME"]?.takeIf { it.isNotBlank() } ?: "${config.workspace}/.home"),
            "TERM" to "xterm-256color",
            "LANG" to (environment["LANG"] ?: "C.UTF-8"),
        )
}

/**
 * Parses a NUL-separated environment block (`/proc/self/environ`).
 *
 * @param block The block.
 *
 * @return The variables; entries without a name are skipped.
 */
fun parseEnvironment(block: String): Map<String, String> = block.split('\u0000').mapNotNull { entry ->
    val separator = entry.indexOf('=')
    if (separator > 0) entry.substring(0, separator) to entry.substring(separator + 1) else null
}.toMap()
