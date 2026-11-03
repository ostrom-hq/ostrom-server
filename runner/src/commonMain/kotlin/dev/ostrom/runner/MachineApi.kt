// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.runner

/** What a registered runner says to the server. */
interface MachineApi {
    /**
     * Signs of life.
     *
     * @throws RunnerKeyRefusedException When the server no longer knows this runner with this key.
     */
    suspend fun heartbeat()
}

/** The server refused the runner's key (401): the runner was removed, or its key replaced. */
class RunnerKeyRefusedException : Exception("The server refused this runner's key.")
