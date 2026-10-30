package dev.ostrom.server.runners.infrastructure

import dev.ostrom.server.runners.domain.RuntimeDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.HttpTimeout
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
     * @param readFile Reads a text file (the Kubernetes service-account token).
     *
     * @return The driver; [DisabledRuntimeDriver] when no runtime is configured.
     */
    fun create(config: RunnersConfig, readFile: (String) -> String = ::readTextFile): RuntimeDriver = when (config.driver) {
        RunnersConfig.DriverKind.NONE -> DisabledRuntimeDriver()
        RunnersConfig.DriverKind.KUBERNETES -> KubernetesRuntimeDriver(
            client = HttpClient(Curl) {
                expectSuccess = false
                engine { caInfo = config.kubernetes.caFile }
                install(HttpTimeout) { requestTimeoutMillis = KUBERNETES_TIMEOUT_MILLIS }
            },
            settings = config.kubernetes,
            serverUrl = config.serverUrl,
            runnerImage = config.runnerImage,
            token = { readFile(config.kubernetes.tokenFile).trim() },
        )
        RunnersConfig.DriverKind.DOCKER -> DockerRuntimeDriver(
            client = HttpClient(Curl) {
                expectSuccess = false
                install(HttpTimeout) { requestTimeoutMillis = DOCKER_TIMEOUT_MILLIS }
            },
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
