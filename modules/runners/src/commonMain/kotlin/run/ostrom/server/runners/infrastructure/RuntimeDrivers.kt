// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.runners.domain.RuntimeDriver
import io.ktor.client.HttpClient
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/** Builds the [RuntimeDriver] the configuration asks for. */
object RuntimeDrivers {
    /** A Kubernetes API call never takes long; a Docker image pull can. */
    private const val KUBERNETES_TIMEOUT_MILLIS = 30_000L
    private const val DOCKER_TIMEOUT_MILLIS = 600_000L

    /**
     * Builds the driver.
     *
     * @param config The module's configuration.
     * @param registered The driver on the registered runners, which needs the database.
     * @param readFile Reads a text file (the Kubernetes service-account token).
     *
     * @return The driver; [DisabledRuntimeDriver] when no runtime is configured.
     */
    fun create(
        config: RunnersConfig,
        registered: () -> RuntimeDriver,
        readFile: (String) -> String = ::readTextFile,
    ): RuntimeDriver =
        when (config.driver) {
            RunnersConfig.DriverKind.NONE -> DisabledRuntimeDriver()
            RunnersConfig.DriverKind.REGISTERED -> registered()
            RunnersConfig.DriverKind.KUBERNETES -> KubernetesRuntimeDriver(
                client = runtimeHttpClient(config.kubernetes.caFile, KUBERNETES_TIMEOUT_MILLIS),
                settings = config.kubernetes,
                serverUrl = config.serverUrl,
                runnerImage = config.runnerImage,
                token = { readFile(config.kubernetes.tokenFile).trim() },
            )
            RunnersConfig.DriverKind.DOCKER -> DockerRuntimeDriver(
                client = runtimeHttpClient(null, DOCKER_TIMEOUT_MILLIS),
                settings = config.docker,
                serverUrl = config.serverUrl,
                runnerImage = config.runnerImage,
            )
        }

    /**
     * Reads a whole text file.
     *
     * @param path The file.
     *
     * @return Its content.
     */
    fun readTextFile(path: String): String = SystemFileSystem.source(Path(path)).buffered().use { it.readString() }
}

/**
 * The HTTP client of a runtime's API: answers are read whatever their status, and a call never hangs.
 *
 * @param caFile A CA bundle to trust instead of the system's (the Kubernetes cluster CA), or null.
 * @param timeoutMillis The request timeout.
 *
 * @return The client: CIO, trusting [caFile] only when it is given.
 */
internal expect fun runtimeHttpClient(caFile: String?, timeoutMillis: Long): HttpClient
