// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.infrastructure

import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.InvalidConfigException
import dev.ostrom.server.runners.domain.AllowedOrgs
import kotlin.uuid.Uuid

/**
 * The runners module's configuration (keys `OSTROM_RUNNERS_…`, docs/MODULES.md). It chooses infrastructure (which
 * runtime runs the pods and how a runner reaches the server), never a product behaviour.
 *
 * @property driver Where executions run.
 * @property serverUrl The URL runners use to reach the server (in-cluster service URL on Kubernetes).
 * @property runnerImage The image of the session pod: the runner, the agents and the `ostrom` CLI.
 * @property kubernetes The Kubernetes runtime's settings, when [driver] is `kubernetes`.
 * @property docker The Docker runtime's settings, when [driver] is `docker`.
 * @property allowedOrgs The organizations cloud sessions are open to (`OSTROM_RUNNERS_ALLOWED_ORGS`: `*`, or ids
 *   separated by commas; none when unset, audit S2-10).
 */
data class RunnersConfig(
    val driver: DriverKind,
    val serverUrl: String,
    val runnerImage: String,
    val kubernetes: KubernetesSettings,
    val docker: DockerSettings,
    val allowedOrgs: AllowedOrgs = AllowedOrgs.NONE,
) {
    /** The runtimes. */
    enum class DriverKind { NONE, KUBERNETES, DOCKER }

    /** Constants and readers of the enclosing type. */
    companion object {
        /** The runner image published by ostrom-server's CI. */
        const val DEFAULT_RUNNER_IMAGE = "ghcr.io/ostrom-hq/ostrom-runner:latest"

        /**
         * Reads the configuration.
         *
         * @param source The configuration source.
         *
         * @return The configuration.
         *
         * @throws InvalidConfigException When a value is invalid, or a driver lacks a required value.
         */
        fun from(source: ConfigSource): RunnersConfig {
            val driver = driverKind(source["OSTROM_RUNNERS_DRIVER"]?.lowercase())
            val serverUrl = source["OSTROM_RUNNERS_SERVER_URL"]?.trimEnd('/').orEmpty()
            if (driver != DriverKind.NONE && !serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
                throw InvalidConfigException(
                    "OSTROM_RUNNERS_SERVER_URL must be the http(s) URL runners reach the server at.",
                )
            }
            return RunnersConfig(
                driver = driver,
                serverUrl = serverUrl,
                runnerImage = source["OSTROM_RUNNERS_IMAGE"]?.takeIf { it.isNotBlank() } ?: DEFAULT_RUNNER_IMAGE,
                kubernetes = KubernetesSettings(
                    apiUrl = source["OSTROM_RUNNERS_KUBERNETES_API_URL"] ?: KubernetesSettings.IN_CLUSTER_API_URL,
                    namespace = source["OSTROM_RUNNERS_KUBERNETES_NAMESPACE"] ?: "ostrom",
                    tokenFile = source["OSTROM_RUNNERS_KUBERNETES_TOKEN_FILE"]
                        ?: KubernetesSettings.IN_CLUSTER_TOKEN_FILE,
                    caFile = source["OSTROM_RUNNERS_KUBERNETES_CA_FILE"] ?: KubernetesSettings.IN_CLUSTER_CA_FILE,
                    storageClass = source["OSTROM_RUNNERS_KUBERNETES_STORAGE_CLASS"]?.takeIf { it.isNotBlank() },
                    workspaceSize = source["OSTROM_RUNNERS_WORKSPACE_SIZE"] ?: "10Gi",
                    imagePullSecret = source["OSTROM_RUNNERS_KUBERNETES_IMAGE_PULL_SECRET"]?.takeIf { it.isNotBlank() },
                ),
                docker = DockerSettings(
                    apiUrl = source["OSTROM_RUNNERS_DOCKER_API_URL"] ?: "http://127.0.0.1:2375",
                    network = source["OSTROM_RUNNERS_DOCKER_NETWORK"]?.takeIf { it.isNotBlank() },
                ),
                allowedOrgs = allowedOrgs(source["OSTROM_RUNNERS_ALLOWED_ORGS"].orEmpty()),
            )
        }

        private fun driverKind(raw: String?): DriverKind = when (raw) {
            null, "", "none" -> DriverKind.NONE
            "kubernetes" -> DriverKind.KUBERNETES
            "docker" -> DriverKind.DOCKER
            else -> throw InvalidConfigException("OSTROM_RUNNERS_DRIVER must be none, kubernetes or docker, not $raw.")
        }

        private fun allowedOrgs(raw: String): AllowedOrgs {
            val entries = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (entries == listOf("*")) return AllowedOrgs(all = true, orgs = emptySet())
            val orgs = entries.map {
                Uuid.parseOrNull(it) ?: throw InvalidConfigException(
                    "OSTROM_RUNNERS_ALLOWED_ORGS must be * or organization ids separated by commas.",
                )
            }
            return AllowedOrgs(all = false, orgs = orgs.toSet())
        }
    }
}

/**
 * How the server reaches its Kubernetes namespace. In the cluster, the defaults read the pod's service account.
 *
 * @property apiUrl The API server URL.
 * @property namespace The namespace every object is created in; the server never touches another one.
 * @property tokenFile The service-account token file, read at each call (the kubelet rotates it).
 * @property caFile The cluster's CA bundle.
 * @property storageClass The workspace volumes' storage class; the cluster default when null.
 * @property workspaceSize The size of a workspace volume.
 * @property imagePullSecret The pull secret of a private runner image.
 */
data class KubernetesSettings(
    val apiUrl: String,
    val namespace: String,
    val tokenFile: String,
    val caFile: String,
    val storageClass: String?,
    val workspaceSize: String,
    val imagePullSecret: String?,
) {
    /** Constants and readers of the enclosing type. */
    companion object {
        /** The API server, seen from a pod. */
        const val IN_CLUSTER_API_URL = "https://kubernetes.default.svc"

        /** The projected service-account token of a pod. */
        const val IN_CLUSTER_TOKEN_FILE = "/var/run/secrets/kubernetes.io/serviceaccount/token"

        /** The cluster CA of a pod. */
        const val IN_CLUSTER_CA_FILE = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"
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
