// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.InvalidConfigException

/**
 * The runners module's configuration (keys `OSTROM_RUNNERS_…`, docs/MODULES.md). It chooses infrastructure (which
 * runtime runs the pods and how a runner reaches the server), never a product behaviour.
 *
 * @property driver Where executions run: nowhere (`OSTROM_RUNNERS_DRIVER` unset, as before cloud profiles: an
 *   upgrade never switches a Docker or bare-metal install), the console's Kubernetes clusters (`kubernetes`, with the
 *   cloud module), the server's Docker Engine, or the organizations' registered runners.
 * @property serverUrl The URL runners reach the server at (`OSTROM_RUNNERS_SERVER_URL`), required for Docker and
 *   registered runners; a Kubernetes session's is the cloud runtime's, else this one (the in-cluster service URL of
 *   an install from before cloud profiles, which a restricted session can reach), else `OSTROM_PUBLIC_URL`.
 * @property runnerImage The image of a Docker or registered session: the runner, the agents and the `ostrom` CLI; the
 *   runner image of a Kubernetes one while the cloud runtime names none.
 * @property kubernetes The pods' priority class and the server pods' labels, for the Kubernetes sessions.
 * @property docker The Docker runtime's settings, when [driver] is `docker`.
 * @property replicaUrl The URL the other replicas reach this one at (`OSTROM_RUNNERS_REPLICA_URL`, the pod's address
 *   with the chart), so that a member attached on one replica sees the terminal whose runner another holds. Unset (one
 *   replica), every terminal is served where it is attached.
 */
data class RunnersConfig(
    val driver: DriverKind,
    val serverUrl: String,
    val runnerImage: String,
    val kubernetes: KubernetesSettings,
    val docker: DockerSettings,
    val replicaUrl: String? = null,
) {
    /** The runtimes. */
    enum class DriverKind { NONE, KUBERNETES, DOCKER, REGISTERED }

    /** The runtime's name runners are labelled with (`kubernetes`, `docker`), null when cloud sessions are off. */
    val runtimeLabel: String? get() = if (driver == DriverKind.NONE) null else driver.name.lowercase()

    /** Constants and readers of the enclosing type. */
    companion object {
        /** The runner image published by ostrom-server's CI. */
        const val DEFAULT_RUNNER_IMAGE = "ghcr.io/ostrom-hq/ostrom-runner:latest"

        /**
         * Reads the configuration.
         *
         * @param source The configuration source.
         * @param cloudModule Whether the cloud module is there: its clusters run the Kubernetes sessions.
         *
         * @return The configuration.
         *
         * @throws InvalidConfigException When a value is invalid, or a driver lacks a required value.
         */
        fun from(source: ConfigSource, cloudModule: Boolean = false): RunnersConfig {
            val driver = driverKind(source["OSTROM_RUNNERS_DRIVER"]?.lowercase(), cloudModule)
            val serverUrl = source["OSTROM_RUNNERS_SERVER_URL"]?.trimEnd('/').orEmpty()
            val needsUrl = driver == DriverKind.DOCKER || driver == DriverKind.REGISTERED || serverUrl.isNotEmpty()
            if (needsUrl && !serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
                throw InvalidConfigException(
                    "OSTROM_RUNNERS_SERVER_URL must be the http(s) URL runners reach the server at.",
                )
            }
            return RunnersConfig(
                driver = driver,
                serverUrl = serverUrl,
                runnerImage = source["OSTROM_RUNNERS_IMAGE"] ?: DEFAULT_RUNNER_IMAGE,
                kubernetes = KubernetesSettings(
                    priorityClass = source["OSTROM_RUNNERS_KUBERNETES_PRIORITY_CLASS"],
                    serverSelector = serverSelector(source["OSTROM_RUNNERS_KUBERNETES_SERVER_SELECTOR"]),
                ),
                docker = DockerSettings(
                    apiUrl = source["OSTROM_RUNNERS_DOCKER_API_URL"] ?: "http://127.0.0.1:2375",
                    network = source["OSTROM_RUNNERS_DOCKER_NETWORK"],
                ),
                replicaUrl = replicaUrl(source["OSTROM_RUNNERS_REPLICA_URL"]),
            )
        }

        /** This replica's URL without its trailing slash, checked; null when unset or blank. */
        private fun replicaUrl(raw: String?): String? {
            val url = raw.orEmpty().trimEnd('/')
            if (url.isEmpty()) return null
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw InvalidConfigException("OSTROM_RUNNERS_REPLICA_URL must be the http(s) URL replicas reach it at.")
            }
            return url
        }

        /** `key=value` labels separated by commas; the chart's server labels when unset. */
        private fun serverSelector(raw: String?): Map<String, String> {
            if (raw == null) return KubernetesSettings.DEFAULT_SERVER_SELECTOR
            return raw.split(',').associate { entry ->
                val match = LABEL.matchEntire(entry.trim()) ?: throw InvalidConfigException(
                    "OSTROM_RUNNERS_KUBERNETES_SERVER_SELECTOR must be key=value labels separated by commas.",
                )
                match.groupValues[1] to match.groupValues[2]
            }
        }

        private val LABEL = Regex("([A-Za-z0-9./_-]+)=([A-Za-z0-9._-]+)")

        // Unset is nowhere, as it always was; `kubernetes` is the console's clusters, where the cloud module is.
        private fun driverKind(raw: String?, cloudModule: Boolean): DriverKind = when (raw) {
            null, "none" -> DriverKind.NONE
            "kubernetes" -> if (cloudModule) DriverKind.KUBERNETES else DriverKind.NONE
            "docker" -> DriverKind.DOCKER
            "registered" -> DriverKind.REGISTERED
            else -> throw InvalidConfigException(
                "OSTROM_RUNNERS_DRIVER must be none, kubernetes, docker or registered, not $raw.",
            )
        }
    }
}

/**
 * What every Kubernetes session pod shares, whatever its cluster: the rest (cluster, namespace, image, pull secrets,
 * resources, workspace) is its profile's.
 *
 * @property priorityClass The session pods' priority class, an existing low one so they never preempt the cluster's
 *   other pods; none when null (priority 0, which preempts nothing either).
 * @property serverSelector The labels of the server's pods, the only pods sessions may reach.
 */
data class KubernetesSettings(
    val priorityClass: String? = null,
    val serverSelector: Map<String, String> = DEFAULT_SERVER_SELECTOR,
) {
    /** Constants of the enclosing type. */
    companion object {
        /** The labels the Helm chart puts on the server's pods. */
        val DEFAULT_SERVER_SELECTOR = mapOf(
            "app.kubernetes.io/name" to "ostrom",
            "app.kubernetes.io/component" to "server",
        )
    }
}

/**
 * How the server reaches a Docker engine (tests and self-hosting).
 *
 * @property apiUrl The engine's HTTP API (`tcp://` exposed as `http://host:2375`, or a socket proxy).
 * @property network The Docker network containers join; the default bridge when null.
 */
data class DockerSettings(
    val apiUrl: String,
    val network: String?,
)
