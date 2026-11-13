// SPDX-License-Identifier: Apache-2.0
package run.ostrom.runner

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
 * @property interval Between heartbeats (`OSTROM_RUNNER_INTERVAL_SECONDS`, 1 to 30 s, 20 s by default): one lost
 *   heartbeat, its 10 s timeout included, stays inside the 90 s after which the server shows the runner offline.
 * @property docker The machine's Docker Engine, where it runs cloud sessions; null for a runner that only signs of
 *   life.
 */
data class MachineConfig(
    val serverUrl: String,
    val runnerId: Uuid,
    val key: String,
    val interval: Duration = DEFAULT_INTERVAL,
    val docker: MachineDocker? = null,
) {
    /** The key never appears in logs. */
    override fun toString(): String =
        "MachineConfig(serverUrl=$serverUrl, runnerId=$runnerId, interval=$interval, docker=$docker)"

    /** Constants and readers of the enclosing type. */
    companion object {
        /** Between heartbeats by default. */
        val DEFAULT_INTERVAL = 20.seconds

        /**
         * The longest interval: the server shows a runner offline 90 s after its last heartbeat, and a lost one (two
         * intervals and a 10 s timeout) must stay inside.
         */
        private const val MAX_INTERVAL_SECONDS = 30

        /** The most sessions a machine may declare it runs at once, as the server accepts. */
        private const val MAX_SESSIONS = 256

        /**
         * Reads the configuration.
         *
         * @param environment The process environment.
         * @param readFile Reads the key file; an [IllegalArgumentException] it throws (an unreadable file) is a bad
         *   configuration too.
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
            val maxSessions = optional("OSTROM_RUNNER_MAX_SESSIONS")?.let { it.toIntOrNull() ?: -1 }
            require(maxSessions == null || maxSessions in 0..MAX_SESSIONS) {
                "OSTROM_RUNNER_MAX_SESSIONS must be 0 to $MAX_SESSIONS."
            }
            val docker = optional("OSTROM_DOCKER_API_URL")?.let { raw ->
                val url = raw.trimEnd('/')
                require(url.startsWith("http://") || url.startsWith("https://")) {
                    "OSTROM_DOCKER_API_URL must be the http(s) URL of the Docker Engine API (a socket proxy)."
                }
                MachineDocker(
                    url,
                    optional("OSTROM_DOCKER_NETWORK"),
                    optional("OSTROM_RUNNER_SESSION_IMAGE"),
                    maxSessions,
                )
            }
            val interval = if (seconds == null) DEFAULT_INTERVAL else seconds.seconds
            return MachineConfig(serverUrl, runnerId, key, interval, docker)
        }
    }
}

/**
 * Where a registered runner runs cloud sessions.
 *
 * @property apiUrl The Docker Engine API (`OSTROM_DOCKER_API_URL`, `http(s)://`; for the local socket, a socket proxy
 *   on the loopback).
 * @property network The network the sessions join (`OSTROM_DOCKER_NETWORK`); the engine's default when null.
 * @property image The only image a session may run (`OSTROM_RUNNER_SESSION_IMAGE`); the server's choice when null.
 * @property maxSessions The sessions it runs at once at most (`OSTROM_RUNNER_MAX_SESSIONS`, 0 to 256; 0 drains the
 *   machine); what the machine holds when null.
 */
data class MachineDocker(val apiUrl: String, val network: String?, val image: String?, val maxSessions: Int?)
