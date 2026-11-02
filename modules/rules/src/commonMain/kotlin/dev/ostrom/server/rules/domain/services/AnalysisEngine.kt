// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.domain.services

import dev.ostrom.server.rules.domain.models.AnalysisRun

/** What the engine answers when asked to start a run. */
sealed interface AnalysisStart {
    /** The engine runs it and reports its phases, rules, workflows and replay items as it goes. */
    data object Started : AnalysisStart

    /**
     * The engine cannot run it: the run fails at once, visibly, with this reason.
     *
     * @property stage Where it stopped (`inference`, `github`, `engine`).
     * @property reason One readable sentence, never a secret.
     */
    data class Unavailable(val stage: String, val reason: String) : AnalysisStart
}

/**
 * The analysis engine (the prototype's `engine-host.ts`): reads the organization's repositories with a model, proposes
 * rules and workflows, replays pull requests. Its runs are recorded here before it starts, so a run it cannot start
 * fails where the console sees it, never silently.
 */
interface AnalysisEngine {
    /**
     * Starts a run.
     *
     * @param run The run, already recorded.
     * @return Started, or why it cannot.
     */
    suspend fun start(run: AnalysisRun): AnalysisStart
}
