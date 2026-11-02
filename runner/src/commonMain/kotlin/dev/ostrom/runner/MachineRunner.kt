// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * A registered runner's loop: a heartbeat at once, then one every [interval]. A failed heartbeat (the server
 * unreachable, a 5xx) is logged and the next one tried. [REFUSALS_TO_STOP] refused keys in a row end the loop: a
 * removed runner never comes back, while a single 401 may come from a proxy or a server restarting.
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
     * Runs until the server refuses the key [REFUSALS_TO_STOP] times in a row.
     *
     * @return [EXIT_KEY_REFUSED].
     */
    suspend fun run(): Int {
        var refusals = 0
        while (true) {
            try {
                api.heartbeat()
                refusals = 0
            } catch (error: CancellationException) {
                throw error
            } catch (expected: RunnerKeyRefusedException) {
                refusals++
                log("key refused ($refusals/$REFUSALS_TO_STOP)")
                if (refusals == REFUSALS_TO_STOP) {
                    log("key refused: this runner was removed or its key replaced")
                    return EXIT_KEY_REFUSED
                }
            } catch (error: Exception) {
                log("heartbeat failed: ${error::class.simpleName}: ${error.message}")
            }
            delay(interval)
        }
    }

    /** Constants of the enclosing type. */
    companion object {
        /** The exit code once the server refused the key (2 is a bad configuration). */
        const val EXIT_KEY_REFUSED = 3

        /** Refused keys in a row that end the loop. */
        const val REFUSALS_TO_STOP = 3
    }
}
