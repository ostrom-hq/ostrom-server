// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.domain.usecases

import dev.ostrom.server.compiler.domain.models.StoredWorkflow
import dev.ostrom.server.compiler.domain.usecases.WorkflowsUseCase
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.rules.domain.models.AnalysisBusyException
import dev.ostrom.server.rules.domain.models.AnalysisFailure
import dev.ostrom.server.rules.domain.models.AnalysisKind
import dev.ostrom.server.rules.domain.models.AnalysisRun
import dev.ostrom.server.rules.domain.models.Decider
import dev.ostrom.server.rules.domain.models.Rule
import dev.ostrom.server.rules.domain.models.RuleQuery
import dev.ostrom.server.rules.domain.repositories.AnalysisRepository
import dev.ostrom.server.rules.domain.repositories.RulesRepository
import dev.ostrom.server.rules.domain.services.AnalysisEngine
import dev.ostrom.server.rules.domain.services.AnalysisStart
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/** Starts an analysis or a replay of the organization (prototype `POST /api/analysis/start`, `/api/replay/start`). */
interface StartAnalysisUseCase {
    /**
     * Records a new run, then asks the engine to run it. A run the engine cannot start is failed at once; a run that
     * has not moved for an hour no longer blocks a new one.
     *
     * @param decider Who starts it.
     * @param kind Analysis or replay.
     * @return The run as recorded after the engine answered.
     * @throws AnalysisBusyException When a run of that kind is still going.
     */
    suspend operator fun invoke(decider: Decider, kind: AnalysisKind): AnalysisRun
}

/**
 * [StartAnalysisUseCase] on the repository: the run is locked for the check, committed, then handed to the engine
 * outside the transaction (an engine may take its time).
 *
 * @param repository The runs.
 * @param engine The analysis engine.
 * @param logger Where an engine that throws is logged.
 * @param clock The time.
 */
class StartAnalysisUseCaseImpl(
    private val repository: AnalysisRepository,
    private val engine: AnalysisEngine,
    private val logger: Logger,
    private val clock: Clock,
) : StartAnalysisUseCase {
    override suspend fun invoke(decider: Decider, kind: AnalysisKind): AnalysisRun {
        val now = clock.now()
        val run = repository.transaction(decider.orgId) {
            val current = lock(kind)
            // A run the engine stopped reporting on (a restart, a crash) holds nobody back past STALE.
            if (current != null && current.running && now - current.updatedAt < STALE) throw AnalysisBusyException()
            AnalysisRun(
                orgId = decider.orgId,
                kind = kind,
                id = "run_${Uuid.random()}",
                phase = AnalysisRun.CONNECT,
                progress = 0.0,
                failure = null,
                startedBy = decider.name,
                startedAt = now,
                updatedAt = now,
                // The last results stay until the new run writes its own: a run that cannot start erases nothing.
                items = current?.items.orEmpty(),
            ).also { put(it) }
        }
        val unavailable = when (val answer = started(run)) {
            AnalysisStart.Started -> return run
            is AnalysisStart.Unavailable -> answer
        }
        val at = clock.now()
        val failed = run.copy(
            phase = AnalysisRun.FAILED,
            failure = AnalysisFailure(unavailable.stage, unavailable.reason, at, kind.retryPath),
            updatedAt = at,
        )
        repository.transaction(decider.orgId) { put(failed) }
        return failed
    }

    // An engine that throws must not leave the run "running" forever: every later start would answer 409.
    private suspend fun started(run: AnalysisRun): AnalysisStart = try {
        engine.start(run)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        logger.error("analysis engine failed to start", mapOf("run_id" to run.id, "kind" to run.kind.value), error)
        AnalysisStart.Unavailable(ENGINE, "The analysis engine failed to start; see the server log.")
    }

    private companion object {
        const val ENGINE = "engine"
    }
}

// Top-level, not in the private companion: a companion property has a getter nothing calls, which Kover counts.
private val STALE = 1.hours

/**
 * The analysis as the console shows it.
 *
 * @property run The last analysis, or null when none was started.
 * @property rules The organization's rules.
 * @property workflows Its saved workflows.
 */
data class AnalysisSnapshot(val run: AnalysisRun?, val rules: List<Rule>, val workflows: List<StoredWorkflow>)

/** Reads the analysis snapshot (prototype `GET /api/state`, the part the analysis fills). */
interface GetAnalysisStateUseCase {
    /**
     * Reads it.
     *
     * @param orgId The organization.
     * @return The snapshot.
     */
    suspend operator fun invoke(orgId: Uuid): AnalysisSnapshot
}

/**
 * [GetAnalysisStateUseCase] on the runs, the rules and the compiler's workflows.
 *
 * @param analyses The runs.
 * @param rules The rules.
 * @param workflows The compiler's workflows.
 */
class GetAnalysisStateUseCaseImpl(
    private val analyses: AnalysisRepository,
    private val rules: RulesRepository,
    private val workflows: WorkflowsUseCase,
) : GetAnalysisStateUseCase {
    override suspend fun invoke(orgId: Uuid): AnalysisSnapshot = AnalysisSnapshot(
        run = analyses.run(orgId, AnalysisKind.ANALYSIS),
        rules = rules.rules(orgId, RuleQuery(), ListRulesUseCase.MAX_RULES),
        workflows = workflows.list(orgId, null, null),
    )
}

/** Reads what the last replay found (prototype `GET /api/replay`). */
interface GetReplayUseCase {
    /**
     * Reads it.
     *
     * @param orgId The organization.
     * @return One object per replayed pull request; none before a replay.
     */
    suspend operator fun invoke(orgId: Uuid): List<JsonObject>
}

/**
 * [GetReplayUseCase] on the runs.
 *
 * @param analyses The runs.
 */
class GetReplayUseCaseImpl(private val analyses: AnalysisRepository) : GetReplayUseCase {
    override suspend fun invoke(orgId: Uuid): List<JsonObject> =
        analyses.run(orgId, AnalysisKind.REPLAY)?.items.orEmpty()
}
