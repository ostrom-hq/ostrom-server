// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * A registered runner's loop: a heartbeat at once, then one every [interval]. A failed heartbeat (the server
 * unreachable, a 5xx) is logged and the next one tried; a refused key ends the loop, since a removed runner never
 * comes back.
 *
 * @param api The server.
 * @param interval Between heartbeats.
 * @param log Writes a line to the process's log (never the key).
 */
class MachineRunner(
    private val api: MachineApi,
    private val interval: Duration,
    private val log: (String) -> Unit,
) {
    /**
     * Runs until the server refuses the key.
     *
     * @return [EXIT_KEY_REFUSED].
     */
    suspend fun run(): Int {
        while (true) {
            try {
                api.heartbeat()
            } catch (error: CancellationException) {
                throw error
            } catch (error: RunnerKeyRefusedException) {
                log("key refused: this runner was removed or its key replaced")
                return EXIT_KEY_REFUSED
            } catch (error: Exception) {
                log("heartbeat failed: ${error::class.simpleName}")
            }
            delay(interval)
        }
    }

    /** Constants of the enclosing type. */
    companion object {
        /** The exit code once the server refused the key (2 is a bad configuration). */
        const val EXIT_KEY_REFUSED = 3
    }
}
