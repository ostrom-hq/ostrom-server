// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

/**
 * How a session gets the `ostrom` binary its containers run (TeamCode's "Delivery", docs/cloud-sessions.md): an init
 * container from [runnerImage] copies [runnerBinary] into a volume every other container mounts read-only at
 * `/opt/ostrom`, and runs `/opt/ostrom/ostrom cloud …`; the session's containers run [sessionImage], the team's
 * toolchain.
 *
 * @property runnerImage The image holding the `ostrom` Linux binary (ghcr.io/ostrom-hq/ostrom-runner).
 * @property runnerBinary Where the binary is in [runnerImage].
 * @property sessionImage The image of the session's containers, where the session names none (its profile does).
 * @property serverUrl The URL a Kubernetes session's runner calls back: the runtime's, else the server's public URL.
 */
data class RunnerDelivery(
    val runnerImage: String,
    val runnerBinary: String,
    val sessionImage: String,
    val serverUrl: String = "",
) {
    /** Constants of the delivery. */
    companion object {
        /** Where the init container puts the binary, in the volume every container mounts. */
        const val DIRECTORY = "/opt/ostrom"

        /** The binary the session's containers run. */
        const val BINARY = "$DIRECTORY/ostrom"

        /** Where the CLI's runner image keeps the binary. */
        const val DEFAULT_RUNNER_BINARY = "/usr/local/bin/ostrom"
    }
}

/** Reads the [RunnerDelivery] a new execution uses: the instance's cloud runtime, as administrators set it. */
fun interface RunnerDeliverySource {
    /**
     * The delivery for an execution starting now.
     *
     * @return The images and the binary's path.
     */
    suspend fun current(): RunnerDelivery
}
