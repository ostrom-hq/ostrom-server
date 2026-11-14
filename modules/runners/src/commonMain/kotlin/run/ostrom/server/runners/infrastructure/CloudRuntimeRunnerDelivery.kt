// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.cloud.domain.CloudRuntime
import run.ostrom.server.cloud.domain.CloudRuntimeSource
import run.ostrom.server.runners.domain.RunnerDelivery
import run.ostrom.server.runners.domain.RunnerDeliverySource

/**
 * The delivery the console's cloud runtime sets (`runnerImage`, `runnerBinary`), [fallbackImage] while no runner image
 * is stored. Until sessions start from a profile, the session's containers run the runner image too: TeamCode's
 * template image is both (docker/cloud-session-image of ostrom-cli).
 *
 * @param runtime The cloud runtime; null on a server without the cloud module, which then uses [fallbackImage].
 * @param fallbackImage The image used while the runtime names none.
 */
class CloudRuntimeRunnerDelivery(
    private val runtime: CloudRuntimeSource?,
    private val fallbackImage: String,
) : RunnerDeliverySource {
    override suspend fun current(): RunnerDelivery {
        val current = if (runtime == null) CloudRuntime() else runtime.current()
        val image = current.runnerImage?.takeIf { it.isNotBlank() } ?: fallbackImage
        return RunnerDelivery(image, current.runnerBinary, image)
    }
}
