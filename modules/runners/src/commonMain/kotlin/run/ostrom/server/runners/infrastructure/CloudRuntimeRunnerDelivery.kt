// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.cloud.domain.CloudRuntime
import run.ostrom.server.cloud.domain.CloudRuntimeSource
import run.ostrom.server.runners.domain.RunnerDelivery
import run.ostrom.server.runners.domain.RunnerDeliverySource

/**
 * The delivery the console's cloud runtime sets (`runnerImage`, `runnerBinary`, `serverUrl`), [fallbackImage] while
 * no runner image is stored and [publicUrl] while no server URL is. A Kubernetes session's containers run its
 * profile's image; [RunnerDelivery.sessionImage] is the runner image, TeamCode's template image being both
 * (docker/cloud-session-image of ostrom-cli).
 *
 * @param runtime The cloud runtime; null on a server without the cloud module, which then uses [fallbackImage].
 * @param fallbackImage The image used while the runtime names none.
 * @param publicUrl The URL used while the runtime names no server URL: `OSTROM_RUNNERS_SERVER_URL`, else the server's
 *   public URL (`OSTROM_PUBLIC_URL`).
 */
class CloudRuntimeRunnerDelivery(
    private val runtime: CloudRuntimeSource?,
    private val fallbackImage: String,
    private val publicUrl: String = "",
) : RunnerDeliverySource {
    override suspend fun current(): RunnerDelivery {
        val current = if (runtime == null) CloudRuntime() else runtime.current()
        val image = current.runnerImage?.takeIf { it.isNotBlank() } ?: fallbackImage
        val serverUrl = current.serverUrl?.takeIf { it.isNotBlank() } ?: publicUrl.trimEnd('/')
        return RunnerDelivery(image, current.runnerBinary, image, serverUrl)
    }
}
