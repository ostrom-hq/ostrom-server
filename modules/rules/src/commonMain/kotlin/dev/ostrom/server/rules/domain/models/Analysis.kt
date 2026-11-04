// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.domain.models

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What an organization runs: the analysis of its repositories (rules and workflows), or the replay of its last 30 days
 * of pull requests under the rules (a simulation).
 *
 * @property value The stored value.
 * @property retryPath Where the console starts it again after a failure.
 */
enum class AnalysisKind(val value: String, val retryPath: String) {
    ANALYSIS("analysis", "/api/v1/analysis/restart"),
    REPLAY("replay", "/api/v1/analysis/replay"),
}

/**
 * Why a run stopped (prototype `AnalysisFailure`): readable, never a secret.
 *
 * @property stage `inference`, `github`, `fast-pass`, `deep-pass` or `engine`.
 * @property reason One sentence.
 * @property at When.
 * @property retryPath Where to start again.
 */
data class AnalysisFailure(val stage: String, val reason: String, val at: Instant, val retryPath: String)

/**
 * The last run of a kind for an organization; a new run replaces it.
 *
 * @property orgId The organization.
 * @property kind Analysis or replay.
 * @property id The run id.
 * @property phase Where it is (`connect` at the start, then the engine's phases); `ready` or `failed` once over.
 * @property progress From 0 to 1.
 * @property failure Why it failed.
 * @property startedBy Who started it.
 * @property startedAt When.
 * @property updatedAt Its last change.
 * @property items What a replay found, one object per pull request.
 */
data class AnalysisRun(
    val orgId: Uuid,
    val kind: AnalysisKind,
    val id: String,
    val phase: String,
    val progress: Double,
    val failure: AnalysisFailure?,
    val startedBy: String,
    val startedAt: Instant,
    val updatedAt: Instant,
    val items: List<JsonObject> = emptyList(),
) {
    /** True until the run is ready or failed. */
    val running: Boolean get() = phase != READY && phase != FAILED

    /** The phases every run passes. */
    companion object {
        /** The first phase, before the engine reports its own. */
        const val CONNECT: String = "connect"

        /** Done. */
        const val READY: String = "ready"

        /** Stopped on a failure. */
        const val FAILED: String = "failed"
    }
}

/** A run of the same kind is still going: one at a time per organization. */
class AnalysisBusyException : OstromException(ErrorKind.CONFLICT, "An analysis of this kind is already running.")

/** A replay ended less than a minute ago: an organization replays once a minute at most (AUDIT-SECU-ANALYSE R3). */
class AnalysisTooSoonException :
    OstromException(ErrorKind.CONFLICT, "A replay ran less than a minute ago: start it again in a minute.")
