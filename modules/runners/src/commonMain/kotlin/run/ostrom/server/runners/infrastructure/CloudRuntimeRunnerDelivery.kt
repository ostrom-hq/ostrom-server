// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.cloud.domain.CloudRuntime
import run.ostrom.server.cloud.domain.CloudRuntimeSource
import run.ostrom.server.runners.domain.RunnerDelivery
import run.ostrom.server.runners.domain.RunnerDeliverySource

/**
 * The delivery the console's cloud runtime sets (`runnerImage`, `runnerBinary`, `serverUrl`), [fallbackImage] while
 * no runner image is stored and [publicUrl] while no server URL is.
 *
 * A Kubernetes session's containers run its profile's image. A Docker or registered session has no profile: its
 * containers run [fallbackImage] (`OSTROM_RUNNERS_IMAGE`), the instance's session image, whatever runner image the
 * runtime names. The runner image only delivers the binary: the CLI's slim `ostrom-runner` has no agent, git nor the
 * session's accounts. While the runtime names none, [fallbackImage] is both, as TeamCode's template image is
 * (docker/cloud-session-image of ostrom-cli, with `ostrom` at /usr/local/bin). A Kubernetes workspace claim gets the
 * runtime's access mode.
 *
 * @param runtime The cloud runtime; null on a server without the cloud module, which then uses [fallbackImage].
 * @param fallbackImage The session image of Docker and registered sessions, and the runner image while the runtime
 *   names none.
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
        return RunnerDelivery(image, current.runnerBinary, fallbackImage, serverUrl, current.workspaceAccessMode)
    }
}
