// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.runner

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * A registered runner on a machine (`ostrom-runner machine`): the runner registered by `POST /api/v1/runners`, its key
 * (shown once by that answer) and how often it signs of life.
 *
 * @property serverUrl The server (`http(s)://`).
 * @property runnerId The registered runner (`OSTROM_RUNNER_ID`).
 * @property key Its key (`OSTROM_RUNNER_KEY` or the file `OSTROM_RUNNER_KEY_FILE`).
 * @property interval Between heartbeats (`OSTROM_RUNNER_INTERVAL_SECONDS`, 1 to 60 s, 30 s by default): well inside
 *   the 90 s after which the server shows the runner offline.
 */
data class MachineConfig(
    val serverUrl: String,
    val runnerId: Uuid,
    val key: String,
    val interval: Duration = DEFAULT_INTERVAL,
) {
    /** The key never appears in logs. */
    override fun toString(): String = "MachineConfig(serverUrl=$serverUrl, runnerId=$runnerId, interval=$interval)"

    /** Constants and readers of the enclosing type. */
    companion object {
        /** Between heartbeats by default. */
        val DEFAULT_INTERVAL = 30.seconds

        /** The longest interval: the server shows a runner offline 90 s after its last heartbeat. */
        private const val MAX_INTERVAL_SECONDS = 60

        /**
         * Reads the configuration.
         *
         * @param environment The process environment.
         * @param readFile Reads the key file.
         *
         * @return The configuration.
         *
         * @throws IllegalArgumentException When a variable is missing or malformed.
         */
        fun from(environment: Map<String, String>, readFile: (String) -> String): MachineConfig {
            fun optional(name: String) = environment[name]?.takeIf { it.isNotBlank() }
            val serverUrl = requireNotNull(optional("OSTROM_SERVER_URL")) { "OSTROM_SERVER_URL is required." }
                .trimEnd('/')
            require(serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) {
                "OSTROM_SERVER_URL must be an http(s) URL."
            }
            val runnerId = requireNotNull(optional("OSTROM_RUNNER_ID")?.let(Uuid::parseOrNull)) {
                "OSTROM_RUNNER_ID is required, a UUID."
            }
            val key = requireNotNull(
                optional("OSTROM_RUNNER_KEY") ?: optional("OSTROM_RUNNER_KEY_FILE")?.let { readFile(it).trim() },
            ) { "OSTROM_RUNNER_KEY or OSTROM_RUNNER_KEY_FILE is required." }
            require(key.isNotEmpty()) { "The runner key is empty." }
            val seconds = optional("OSTROM_RUNNER_INTERVAL_SECONDS")?.let { it.toIntOrNull() ?: 0 }
            require(seconds == null || seconds in 1..MAX_INTERVAL_SECONDS) {
                "OSTROM_RUNNER_INTERVAL_SECONDS must be 1 to $MAX_INTERVAL_SECONDS."
            }
            return MachineConfig(serverUrl, runnerId, key, if (seconds == null) DEFAULT_INTERVAL else seconds.seconds)
        }
    }
}
