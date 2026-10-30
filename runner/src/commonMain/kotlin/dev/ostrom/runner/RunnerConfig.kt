// SPDX-License-Identifier: AGPL-3.0-only
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
 * @property model The model the agent starts with, if chosen.
 * @property prompt The agent's first message, if any (first execution only).
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
    val model: String? = null,
    val prompt: String? = null,
) {
    /** The token never appears in logs. */
    override fun toString(): String =
        "RunnerConfig(serverUrl=$serverUrl, sessionId=$sessionId, epoch=$epoch, agent=$agentCommand)"

    /** Constants and readers of the enclosing type. */
    companion object {
        /** Variables only the runner reads; never passed to the agent. */
        val PRIVATE_VARIABLES = setOf(
            "OSTROM_RUNNER_TOKEN",
            "OSTROM_RUNNER_TOKEN_FILE",
            "OSTROM_AGENT_PROMPT",
            "OSTROM_AGENT_PROMPT_FILE",
        )

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
            fun optional(name: String) = environment[name]?.takeIf { it.isNotBlank() }
            fun required(name: String) = requireNotNull(optional(name)) { "$name is required." }
            val serverUrl = required("OSTROM_SERVER_URL").trimEnd('/')
            require(serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) {
                "OSTROM_SERVER_URL must be an http(s) URL."
            }
            val token = requireNotNull(
                optional("OSTROM_RUNNER_TOKEN") ?: optional("OSTROM_RUNNER_TOKEN_FILE")?.let { readFile(it).trim() },
            ) { "OSTROM_RUNNER_TOKEN or OSTROM_RUNNER_TOKEN_FILE is required." }
            require(token.isNotEmpty()) { "The runner token is empty." }
            return RunnerConfig(
                serverUrl = serverUrl,
                sessionId = requireNotNull(Uuid.parseOrNull(required("OSTROM_SESSION_ID"))) {
                    "OSTROM_SESSION_ID is not a UUID."
                },
                epoch = requireNotNull(required("OSTROM_EPOCH").toLongOrNull()) { "OSTROM_EPOCH is not a number." },
                agentCommand = required("OSTROM_AGENT_COMMAND"),
                repositoryUrl = optional("OSTROM_REPOSITORY_URL"),
                repositoryRef = optional("OSTROM_REPOSITORY_REF"),
                token = token,
                workspace = optional("OSTROM_WORKSPACE") ?: DEFAULT_WORKSPACE,
                model = optional("OSTROM_AGENT_MODEL"),
                prompt = optional("OSTROM_AGENT_PROMPT")
                    ?: optional("OSTROM_AGENT_PROMPT_FILE")?.let { readFile(it).takeIf(String::isNotBlank) },
            )
        }

        const val DEFAULT_WORKSPACE = "/workspace"
    }
}

/** How the session's terminal starts: a shell script that prepares the workspace then runs the agent. */
object SessionScript {
    /**
     * The script of the first terminal: clone the repository once (never reset afterwards), enter it, run the
     * agent (with its model and first message, if any), then leave a login shell so the member can start the
     * agent again. Values come from the environment, never from the script's text.
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
set --
if [ -n "${'$'}{OSTROM_AGENT_MODEL:-}" ]; then
  case "${'$'}OSTROM_AGENT_COMMAND" in
    bash) ;;
    gemini) set -- -m "${'$'}OSTROM_AGENT_MODEL" ;;
    *) set -- --model "${'$'}OSTROM_AGENT_MODEL" ;;
  esac
fi
if [ -n "${'$'}{OSTROM_AGENT_PROMPT:-}" ]; then
  case "${'$'}OSTROM_AGENT_COMMAND" in
    bash) ;;
    gemini) set -- "${'$'}@" -i "${'$'}OSTROM_AGENT_PROMPT" ;;
    opencode) set -- "${'$'}@" --prompt "${'$'}OSTROM_AGENT_PROMPT" ;;
    *) set -- "${'$'}@" "${'$'}OSTROM_AGENT_PROMPT" ;;
  esac
fi
"${'$'}OSTROM_AGENT_COMMAND" "${'$'}@"
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
        environment.filterKeys { it !in RunnerConfig.PRIVATE_VARIABLES } +
            listOfNotNull(config.prompt?.let { "OSTROM_AGENT_PROMPT" to it }) + mapOf(
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
