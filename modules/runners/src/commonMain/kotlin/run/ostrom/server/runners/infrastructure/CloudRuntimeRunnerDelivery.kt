// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.cloud.domain.CloudRuntime
import run.ostrom.server.cloud.domain.CloudRuntimeSource
import run.ostrom.server.runners.domain.RunnerDelivery
import run.ostrom.server.runners.domain.RunnerDeliverySource

/**
 * The delivery the console's cloud runtime sets (`runnerImage`, `runnerBinary`, `sessionImage`, `serverUrl`),
 * [fallbackImage] while no runner image is stored and [publicUrl] while no server URL is.
 *
 * A Kubernetes session's containers run its profile's image. A Docker or registered session has no profile: its
 * containers run the runtime's session image (docker.io/ostromhq/ostrom-session:latest unless an administrator sets
 * another), the agents, git and the session's accounts; the runner image only delivers the binary. A Kubernetes
 * workspace claim gets the runtime's access mode.
 *
 * @param runtime The cloud runtime; null on a server without the cloud module, which then uses its defaults.
 * @param fallbackImage The runner image while the runtime names none (`OSTROM_RUNNERS_IMAGE`).
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
        val session = current.sessionImage.takeIf { it.isNotBlank() } ?: CloudRuntime.DEFAULT_SESSION_IMAGE
        val serverUrl = current.serverUrl?.takeIf { it.isNotBlank() } ?: publicUrl.trimEnd('/')
        return RunnerDelivery(image, current.runnerBinary, session, serverUrl, current.workspaceAccessMode)
    }
}
