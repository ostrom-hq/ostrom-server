// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.infrastructure

import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.rules.domain.models.AnalysisKind
import dev.ostrom.server.rules.domain.models.AnalysisRun
import dev.ostrom.server.rules.domain.models.RepositoryListing
import dev.ostrom.server.rules.domain.models.RepositoryReadException
import dev.ostrom.server.rules.domain.models.RepositoryRef
import dev.ostrom.server.rules.domain.services.AnalysisEngine
import dev.ostrom.server.rules.domain.services.AnalysisStart
import dev.ostrom.server.rules.domain.services.RepositoryReader
import dev.ostrom.server.rules.domain.usecases.AnalyzeOrganizationUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel

/**
 * The deterministic analysis engine. Starting a run lists the organization's repositories at once, so a run that
 * cannot read GitHub fails where the console sees it; the run itself is queued and analysed in the background by the
 * module's workers ([work]), never in the request. The queue is bounded: past it, a start is refused visibly.
 *
 * The replay of pull requests needs their history, which this engine does not read: a replay fails visibly.
 *
 * @param reader Lists the repositories.
 * @param analyze Runs an analysis.
 * @param logger Where a run that broke the worker is logged.
 * @param capacity The runs waiting at most.
 */
class RepositoryAnalysisEngine(
    private val reader: RepositoryReader,
    private val analyze: AnalyzeOrganizationUseCase,
    private val logger: Logger,
    capacity: Int = CAPACITY,
) : AnalysisEngine {
    private val queue = Channel<Pending>(capacity)

    override suspend fun start(run: AnalysisRun): AnalysisStart =
        if (run.kind == AnalysisKind.ANALYSIS) listed(run) else AnalysisStart.Unavailable(ENGINE, NO_REPLAY)

    private suspend fun listed(run: AnalysisRun): AnalysisStart {
        val listing = try {
            reader.repositories(run.orgId)
        } catch (error: RepositoryReadException) {
            RepositoryListing.NotConnected(error.message)
        }
        return when (listing) {
            is RepositoryListing.NotConnected -> AnalysisStart.Unavailable(GITHUB, listing.reason)
            is RepositoryListing.Connected -> when {
                listing.repos.isEmpty() -> AnalysisStart.Unavailable(GITHUB, NO_REPOS)
                queue.trySend(Pending(run, listing.repos)).isSuccess -> AnalysisStart.Started
                else -> AnalysisStart.Unavailable(ENGINE, BUSY)
            }
        }
    }

    /** Analyses the next queued run, waiting for one. A run that throws never stops the worker. */
    suspend fun runNext() {
        val pending = queue.receive()
        try {
            analyze(pending.run, pending.repos)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The analysis fails its own run; this only happens when even that write failed (the database is down).
            logger.error("analysis worker caught a failure", mapOf("run_id" to pending.run.id), error)
        }
    }

    /** Analyses the queued runs one after the other until the server stops (its scope is cancelled). */
    suspend fun work(): Nothing {
        while (true) runNext()
    }

    private class Pending(val run: AnalysisRun, val repos: List<RepositoryRef>)

    /** The engine's bounds and reasons. */
    companion object {
        /** The runs waiting at most. */
        const val CAPACITY: Int = 64

        /** The workers each server runs. */
        const val WORKERS: Int = 2

        private const val ENGINE = "engine"
        private const val GITHUB = "github"
        private const val NO_REPLAY = "The replay of pull requests is not available on this server yet."
        private const val NO_REPOS = "The GitHub App sees no repository of this organization: grant it the " +
            "repositories to analyse, then start again."
        private const val BUSY = "Too many analyses are waiting on this server; start again in a minute."
    }
}
