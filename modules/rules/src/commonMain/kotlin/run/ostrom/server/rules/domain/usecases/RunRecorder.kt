// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.rules.domain.usecases

import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.rules.domain.models.AnalysisFailure
import run.ostrom.server.rules.domain.models.AnalysisRun
import run.ostrom.server.rules.domain.repositories.AnalysisRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Writes a run's phases as the analysis or the replay goes, so the console follows it, and ends it: `ready`, or
 * `failed` with a stage and a reason. A run a newer one replaced (it stalled past the hour) stops without writing.
 *
 * @param analyses The runs.
 * @param clock The time.
 * @param logger Where a run that broke is logged.
 */
internal class RunRecorder(
    private val analyses: AnalysisRepository,
    private val clock: Clock,
    private val logger: Logger,
) {
    /**
     * Runs [work] for [run] to its end. A superseded run stops silently; a server that stops fails the run as stopped
     * and goes on stopping; a run past its deadline fails as too long and gives its worker back (GitHub slow or
     * rate-limited at every call would hold it for hours, and two workers serve every organization of the server);
     * any other error fails it as broken. Each write is a transaction of its own: a run stopped half-way leaves the
     * last results and the rules it had not proposed yet, never half of a write.
     *
     * @param run The run.
     * @param what `analysis` or `replay`, in the log and the reasons.
     * @param deadline How long the run may take.
     * @param work The run's work.
     */
    suspend fun execute(run: AnalysisRun, what: String, deadline: Duration, work: suspend () -> Unit) {
        try {
            if (withTimeoutOrNull(deadline) { work() } == null) {
                logger.warn("$what run past its deadline", mapOf("run_id" to run.id))
                fail(run, ENGINE, "The $what took longer than ${deadline.inWholeMinutes} minutes; start it again.")
            }
        } catch (_: SupersededRunException) {
            // A newer run took the organization's run over: it owns the state.
            logger.debug("$what run superseded", mapOf("run_id" to run.id))
        } catch (error: CancellationException) {
            // The server stops: the run says so rather than staying "running" until it goes stale.
            withContext(NonCancellable) { fail(run, ENGINE, "The server stopped during the $what; start it again.") }
            throw error
        } catch (error: Exception) {
            logger.error("$what failed", mapOf("run_id" to run.id), error)
            fail(run, ENGINE, "The $what stopped on an unexpected error; see the server log.")
        }
    }

    /**
     * Moves the run to a phase.
     *
     * @param run The run.
     * @param phase The phase.
     * @param progress From 0 to 1.
     * @param items Its new items, or null to keep the current ones.
     * @throws SupersededRunException When a newer run replaced it.
     */
    suspend fun advance(run: AnalysisRun, phase: String, progress: Double, items: List<JsonObject>? = null) {
        val at = clock.now()
        val ours = analyses.transaction(run.orgId) {
            val current = lock(run.kind)
            if (current == null || current.id != run.id) return@transaction false
            put(current.copy(phase = phase, progress = progress, updatedAt = at, items = items ?: current.items))
            true
        }
        if (!ours) throw SupersededRunException()
    }

    /**
     * Fails the run; its last results stay: a failed run erases nothing.
     *
     * @param run The run.
     * @param stage Where it stopped.
     * @param reason One readable sentence.
     */
    suspend fun fail(run: AnalysisRun, stage: String, reason: String) {
        val at = clock.now()
        analyses.transaction(run.orgId) {
            val current = lock(run.kind)
            if (current != null && current.id == run.id) {
                put(
                    current.copy(
                        phase = AnalysisRun.FAILED,
                        failure = AnalysisFailure(stage, reason, at, run.kind.retryPath),
                        updatedAt = at,
                    ),
                )
            }
        }
    }

    private companion object {
        const val ENGINE = "engine"
    }
}

/** A newer run replaced this one: it stops without writing. */
internal class SupersededRunException : Exception("The run was superseded.")
