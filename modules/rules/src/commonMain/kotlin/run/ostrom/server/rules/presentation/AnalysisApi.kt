// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.rules.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** `/api/v1/analysis`: the analysis of the caller's organization and the replay of its pull requests. */
@Serializable
@Resource("/api/v1/analysis")
class AnalysisApi {
    /**
     * `/start`: analyse the organization (`startAnalysis`).
     *
     * @property parent The analysis.
     */
    @Serializable
    @Resource("start")
    class Start(val parent: AnalysisApi = AnalysisApi())

    /**
     * `/restart`: start a failed analysis again (`restartAnalysis`).
     *
     * @property parent The analysis.
     */
    @Serializable
    @Resource("restart")
    class Restart(val parent: AnalysisApi = AnalysisApi())

    /**
     * `/state`: the snapshot (`getAnalysisState`).
     *
     * @property parent The analysis.
     */
    @Serializable
    @Resource("state")
    class State(val parent: AnalysisApi = AnalysisApi())

    /**
     * `/replay`: replay the last 30 days of pull requests (`startReplay`), and its results (`getReplay`).
     *
     * @property parent The analysis.
     */
    @Serializable
    @Resource("replay")
    class Replay(val parent: AnalysisApi = AnalysisApi())
}

/**
 * A run just started.
 *
 * @property runId Its id.
 */
@Serializable
data class AnalysisStarted(val runId: String)

/**
 * The analysis snapshot (contract `AnalysisState`).
 *
 * @property phase Where the last analysis is; absent before the first.
 * @property progress From 0 to 1; absent before the first.
 * @property repos The repositories it read.
 * @property rules The organization's rules.
 * @property workflows Its workflows.
 * @property failure Why the last analysis failed: `stage`, `reason`, `at`, `retryPath`.
 */
@Serializable
data class AnalysisStateDto(
    val phase: String? = null,
    val progress: Double? = null,
    val repos: List<JsonObject>,
    val rules: List<RuleDto>,
    val workflows: List<JsonObject>,
    val failure: Map<String, String>? = null,
)

/**
 * What the last replay found.
 *
 * @property items One object per replayed pull request.
 */
@Serializable
data class ReplayPage(val items: List<JsonObject>)
