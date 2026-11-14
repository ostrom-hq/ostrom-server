// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.runners.domain.RunnerDeliverySource
import run.ostrom.server.runners.domain.RuntimeDriver
import io.ktor.client.HttpClient

/** Builds the [RuntimeDriver] the configuration asks for. */
object RuntimeDrivers {
    /** A Docker image pull can take long. */
    private const val DOCKER_TIMEOUT_MILLIS = 600_000L

    /**
     * Builds the driver.
     *
     * @param config The module's configuration.
     * @param registered The driver on the registered runners, which needs the database.
     * @param delivery The runner image and server URL of a new Kubernetes execution.
     * @param clusters The console's clusters the Kubernetes sessions run on.
     *
     * @return The driver; [DisabledRuntimeDriver] when no runtime is configured.
     */
    fun create(
        config: RunnersConfig,
        registered: () -> RuntimeDriver,
        delivery: RunnerDeliverySource,
        clusters: () -> KubernetesClusters,
    ): RuntimeDriver =
        when (config.driver) {
            RunnersConfig.DriverKind.NONE -> DisabledRuntimeDriver()
            RunnersConfig.DriverKind.REGISTERED -> registered()
            RunnersConfig.DriverKind.KUBERNETES -> KubernetesRuntimeDriver(clusters(), config.kubernetes, delivery)
            RunnersConfig.DriverKind.DOCKER -> DockerRuntimeDriver(
                client = runtimeHttpClient(null, DOCKER_TIMEOUT_MILLIS),
                settings = config.docker,
                serverUrl = config.serverUrl,
                runnerImage = config.runnerImage,
            )
        }
}

/**
 * The HTTP client of a runtime's API: answers are read whatever their status, and a call never hangs.
 *
 * @param caPem A PEM CA bundle to trust instead of the system's (the Kubernetes cluster's CA), or null.
 * @param timeoutMillis The request timeout.
 *
 * @return The client: CIO, trusting [caPem] only when it is given.
 *
 * @throws IllegalArgumentException When [caPem] holds no certificate.
 */
internal expect fun runtimeHttpClient(caPem: String?, timeoutMillis: Long): HttpClient
