// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.domain.usecases

import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.gates.domain.repositories.GateHistory
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.rules.domain.models.AnalysisRun
import dev.ostrom.server.rules.domain.repositories.AnalysisRepository
import dev.ostrom.server.rules.domain.repositories.RulesRepository
import dev.ostrom.server.rules.domain.services.GateReplay
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * Runs one replay of an organization: its rules that gate boundary actions (proposed, accepted, or as the team edited
 * them) replayed on the boundary actions its sessions asked about in the last 30 days, as its gates recorded them.
 * Each action becomes one item that says what happened, what the rules would have done (`passed`, `asked` or
 * `blocked`) and why. Deterministic: no model, the same rules and history give the same items. A simulation: it
 * changes no gate and no rule.
 */
interface ReplayRulesUseCase {
    /**
     * Runs it to its end: `ready` with its items, or `failed` with a stage and a reason. A run a newer one replaced
     * stops without writing anything more.
     *
     * @param run The run, already recorded.
     */
    suspend operator fun invoke(run: AnalysisRun)
}

/**
 * [ReplayRulesUseCase] on the runs, the rules and the gates' history, each read for the run's organization only.
 *
 * @param analyses The runs.
 * @param rules The rules and the self-approval policy.
 * @param history The organization's boundary actions.
 * @param logger Where an unexpected failure is logged.
 * @param clock The time.
 */
class ReplayRulesUseCaseImpl(
    analyses: AnalysisRepository,
    private val rules: RulesRepository,
    private val history: GateHistory,
    logger: Logger,
    private val clock: Clock,
) : ReplayRulesUseCase {
    private val runs = RunRecorder(analyses, clock, logger)

    override suspend fun invoke(run: AnalysisRun) {
        runs.execute(run, "replay") { replay(run) }
    }

    private suspend fun replay(run: AnalysisRun) {
        runs.advance(run, READ, 0.0)
        val replayed = GateReplay.prepare(rules.gatedRules(run.orgId))
        if (replayed.isEmpty()) return runs.fail(run, ENGINE, NO_RULES)
        val policy = rules.selfApproval(run.orgId) ?: SelfApprovalPolicy.DEFAULT
        val actions = history.recent(run.orgId, clock.now() - REPLAY_WINDOW, MAX_ACTIONS)
        runs.advance(run, REPLAY, HALF)
        runs.advance(run, AnalysisRun.READY, 1.0, GateReplay.replayAll(actions, replayed, policy))
    }

    /** The bounds and words of a replay. */
    companion object {
        /** The boundary actions one replay reads at most: the most recent. */
        const val MAX_ACTIONS: Int = 1000

        /** The phase while the rules and the history are read. */
        const val READ: String = "read"

        /** The phase while the rules are replayed. */
        const val REPLAY: String = "replay"

        private const val HALF = 0.5
        private const val ENGINE = "engine"
        private const val NO_RULES = "No rule of the organization gates a boundary action yet: run the analysis, " +
            "or give a rule a gate, then replay again."
    }
}

// How far back the history is replayed. Top-level: a companion property has a getter nothing calls, which Kover
// counts.
private val REPLAY_WINDOW = 30.days
