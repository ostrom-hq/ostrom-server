// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * A registered runner's loop: a heartbeat at once, then one every [interval]. A failed heartbeat (the server
 * unreachable, a 5xx) is logged and the next one tried. [REFUSALS_TO_STOP] refused keys in a row end the loop: a
 * removed runner never comes back, while a single 401 may come from a proxy or a server restarting.
 *
 * With [sessions], each heartbeat reports what the machine runs and its answer is applied in the background, one
 * answer at a time and the latest only, so that a long creation (an image pull) never delays a heartbeat. A machine
 * whose engine cannot be read heartbeats without a report, and is given no session.
 *
 * @param api The server.
 * @param interval Between heartbeats.
 * @param log Writes a line to the process's log (never the key).
 * @param sessions The cloud sessions it runs, or null for a runner that runs none.
 */
class MachineRunner(
    private val api: MachineApi,
    private val interval: Duration,
    private val log: (String) -> Unit,
    private val sessions: MachineSessions? = null,
) {
    /**
     * Runs until the server refuses the key [REFUSALS_TO_STOP] times in a row.
     *
     * @return [EXIT_KEY_REFUSED].
     */
    suspend fun run(): Int = coroutineScope {
        val answers = Channel<MachineWork>(Channel.CONFLATED)
        val applier = sessions?.let { machine -> launch { applyAnswers(machine, answers) } }
        try {
            beat(answers)
        } finally {
            applier?.cancel()
        }
    }

    private suspend fun beat(answers: Channel<MachineWork>): Int {
        var refusals = 0
        val machine = sessions
        while (true) {
            try {
                val report = if (machine == null) null else report(machine)
                val work = api.heartbeat(report)
                refusals = 0
                if (machine != null && report != null) {
                    machine.receive(work)
                    answers.trySend(work)
                }
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

    /** What the machine runs; null when its engine cannot be read. */
    private suspend fun report(machine: MachineSessions): MachineReport? =
        try {
            machine.report()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log("docker unreadable, no session this time: ${error::class.simpleName}: ${error.message}")
            null
        }

    private suspend fun applyAnswers(machine: MachineSessions, answers: Channel<MachineWork>) {
        for (work in answers) {
            try {
                machine.apply(work)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log("sessions not applied: ${error::class.simpleName}: ${error.message}")
            }
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
