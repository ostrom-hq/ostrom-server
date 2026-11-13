// SPDX-License-Identifier: Apache-2.0
package run.ostrom.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A registered runner's loop: a heartbeat at once, then one every [interval]. A failed heartbeat (the server
 * unreachable, a 5xx) is logged and the next one tried. [REFUSALS_TO_STOP] refused keys in a row end the loop: a
 * removed runner never comes back, while a single 401 may come from a proxy or a server restarting.
 *
 * With [sessions], each heartbeat reports what the machine runs and its answer is applied in the background, one
 * answer at a time and the latest only, so that a long creation (an image pull) never delays a heartbeat. A machine
 * whose engine cannot be read heartbeats without a report, and is given no session.
 *
 * A heartbeat with a report may wait [wait] for work: the server holds it until an execution is handed over, so a
 * session starts within seconds rather than at the next interval. The next heartbeat then goes at once after an answer
 * held for the whole wait or one that hands an execution over, and after [interval] otherwise: a server that does not
 * wait, or a machine without room, answers at once and is asked again at the interval, as before.
 *
 * @param api The server.
 * @param interval Between heartbeats.
 * @param log Writes a line to the process's log (never the key).
 * @param sessions The cloud sessions it runs, or null for a runner that runs none.
 * @param wait How long a heartbeat with a report may wait for work; zero never waits.
 * @param time Measures how long the server held an answer.
 */
class MachineRunner(
    private val api: MachineApi,
    private val interval: Duration,
    private val log: (String) -> Unit,
    private val sessions: MachineSessions? = null,
    private val wait: Duration = Duration.ZERO,
    private val time: TimeSource = TimeSource.Monotonic,
) {
    /**
     * Runs until the server refuses the key [REFUSALS_TO_STOP] times in a row.
     *
     * @return [EXIT_KEY_REFUSED].
     */
    suspend fun run(): Int = coroutineScope {
        val answers = Channel<MachineWork>(Channel.CONFLATED)
        // A failed or cancelled loop cancels it with the scope; a refused key ends it here.
        val applier = sessions?.let { machine -> launch { applyAnswers(machine, answers) } }
        val code = beat(answers)
        applier?.cancel()
        code
    }

    private suspend fun beat(answers: Channel<MachineWork>): Int {
        var refusals = 0
        val machine = sessions
        while (true) {
            var next = interval
            try {
                val report = if (machine == null) null else report(machine)
                val waits = if (report == null) Duration.ZERO else wait
                val asked = time.markNow()
                val work = api.heartbeat(report, waits)
                refusals = 0
                if (machine != null && report != null) {
                    machine.receive(work)
                    answers.trySend(work)
                }
                val handed = work.executions.any { it.plan != null }
                if (waits.isPositive() && (handed || asked.elapsedNow() >= waits)) next = Duration.ZERO
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
            delay(next)
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
        // Until the loop ends and cancels it: nothing closes the channel.
        while (true) {
            val work = answers.receive()
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

        /**
         * How long a machine's heartbeat waits for work: at most the default interval, so that a waiting heartbeat lost
         * on the way (its timeout of this wait and [HttpMachineApi.WAIT_SLACK], then the interval) stays inside the
         * 90 s after which the server shows the runner offline.
         */
        val WAIT: Duration = 20.seconds
    }
}
