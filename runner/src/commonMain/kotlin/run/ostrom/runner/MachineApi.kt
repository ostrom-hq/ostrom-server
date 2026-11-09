// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.runner

import kotlin.time.Duration

/** What a registered runner says to the server. */
interface MachineApi {
    /**
     * Signs of life, with what the runner has when it runs sessions.
     *
     * @param report What it has; null for a runner that runs no session, which the server then gives none.
     * @param wait How long the server may hold the answer for an execution to hand over (`runnerHeartbeat?wait=`); a
     *   server that does not wait answers at once.
     *
     * @return What it must hold.
     *
     * @throws RunnerKeyRefusedException When the server no longer knows this runner with this key.
     */
    suspend fun heartbeat(report: MachineReport?, wait: Duration = Duration.ZERO): MachineWork
}

/** The server refused the runner's key (401): the runner was removed, or its key replaced. */
class RunnerKeyRefusedException : Exception("The server refused this runner's key.")
