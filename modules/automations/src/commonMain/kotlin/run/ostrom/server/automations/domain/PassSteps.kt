// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.domain

import kotlinx.coroutines.CancellationException

/**
 * The steps of one scheduler pass, each under its own handler (TeamCode d7cc0b49): a step that fails never stops the
 * steps after it, and [end] throws the first failure once every step ran, for the scheduler to report.
 * Cancellation is never held back.
 */
internal class PassSteps {
    private var failure: Exception? = null

    /**
     * Runs one step.
     *
     * @param block The step.
     * @return What it returned, or null when it failed.
     */
    suspend fun <T> step(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        failure = failure ?: error
        null
    }

    /** Throws the first failure of the pass, if a step failed. */
    fun end() {
        failure?.let { throw it }
    }
}
