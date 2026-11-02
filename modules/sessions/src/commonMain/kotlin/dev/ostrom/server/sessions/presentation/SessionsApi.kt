// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sessions.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// The routes and bodies of `ostrom-core/api/openapi.yaml` (tag `sessions`), field for field.

/**
 * The sessions of the caller's organization.
 *
 * @property repo Only this repository's.
 * @property dev Only this person's.
 * @property open Only the open (true) or ended (false) ones.
 * @property before The cursor of the previous page.
 * @property limit At most this many.
 */
@Serializable
@Resource("/api/v1/sessions")
class SessionsApi(
    val repo: String? = null,
    val dev: String? = null,
    val open: Boolean? = null,
    val before: String? = null,
    val limit: Int? = null,
) {
    /**
     * One segment.
     *
     * @property parent The sessions.
     * @property id The segment.
     */
    @Serializable
    @Resource("{id}")
    class Id(val parent: SessionsApi = SessionsApi(), val id: String) {
        /** The launcher's sign of life. */
        @Serializable
        @Resource("alive")
        class Alive(val parent: Id)

        /** Ends the segment. */
        @Serializable
        @Resource("end")
        class End(val parent: Id)

        /** Reopens a segment after a closed one. */
        @Serializable
        @Resource("reopen")
        class Reopen(val parent: Id)

        /** A skill or sub-agent opened. */
        @Serializable
        @Resource("seen")
        class Seen(val parent: Id)
    }
}

/**
 * A session segment (contract `Session`).
 *
 * @property id The segment.
 * @property agentSessionId The agent's session id, for a resumed segment.
 * @property segment Its number, for a resumed segment.
 * @property resumedFrom The segment it follows.
 * @property previousReceiptId The receipt of the segment it follows.
 * @property launchId The launch.
 * @property repo The repository.
 * @property realRepo The repository it really runs in.
 * @property cwd The working directory.
 * @property agent The coding agent.
 * @property dev The person, read from the credential.
 * @property devVerified Always true: the person comes from the credential.
 * @property harnessVersion The launcher or plugin version.
 * @property packageDigest The session package.
 * @property model The model.
 * @property startedAt When it started.
 * @property endedAt When it ended.
 * @property endedBy Why it ended.
 * @property lastSeenAt The launcher's last sign of life.
 * @property gates The gates it asked or joined (single reads only).
 * @property receiptId Its receipt.
 * @property tools Tool calls.
 * @property costUsd Cost.
 * @property exitCode The launcher's exit code.
 * @property ended True once ended (the answer to a sign of life).
 * @property lost True when closed for lack of a sign of life: its launcher reopens a segment.
 */
@Serializable
data class SessionDto(
    val id: String,
    val agentSessionId: String? = null,
    val segment: Int? = null,
    val resumedFrom: String? = null,
    val previousReceiptId: String? = null,
    val launchId: String? = null,
    val repo: String? = null,
    val realRepo: String? = null,
    val cwd: String? = null,
    val agent: String,
    val dev: String,
    val devVerified: Boolean = true,
    val harnessVersion: String? = null,
    val packageDigest: String? = null,
    val model: String? = null,
    val startedAt: String,
    val endedAt: String? = null,
    val endedBy: String? = null,
    val lastSeenAt: String? = null,
    val gates: List<String>? = null,
    val receiptId: String? = null,
    val tools: Int? = null,
    val costUsd: Double? = null,
    val exitCode: Int? = null,
    val ended: Boolean? = null,
    val lost: Boolean? = null,
)

/**
 * Starts a session or a segment (contract `StartSessionRequest`). `dev` is never read: the person is the credential's.
 *
 * @property id The agent's session id.
 * @property launchId The launch.
 * @property repo The repository.
 * @property realRepo The repository it really runs in.
 * @property cwd The working directory.
 * @property agent The coding agent.
 * @property dev Ignored.
 * @property harnessVersion The launcher or plugin version.
 * @property packageDigest The session package.
 * @property model The model.
 */
@Serializable
data class StartSessionRequest(
    val id: String,
    val launchId: String? = null,
    val repo: String? = null,
    val realRepo: String? = null,
    val cwd: String? = null,
    val agent: String,
    val dev: String? = null,
    val harnessVersion: String? = null,
    val packageDigest: String? = null,
    val model: String? = null,
)

/**
 * Ends a session (contract `EndSessionRequest`).
 *
 * @property tools Tool calls.
 * @property model The model.
 * @property costUsd Cost.
 * @property source Why it ended.
 * @property exitCode The launcher's exit code.
 */
@Serializable
data class EndSessionRequest(
    val tools: Int? = null,
    val model: String? = null,
    val costUsd: Double? = null,
    val source: String? = null,
    val exitCode: Int? = null,
)

/**
 * A skill or sub-agent the agent opened (contract `reportSeen`).
 *
 * @property kind `skill` or `agent`.
 * @property name Its name.
 * @property via `hook` or `mcp`.
 */
@Serializable
data class SeenRequest(val kind: String, val name: String, val via: String)

/**
 * A session with its signed receipt (contract `SessionResponse`).
 *
 * @property session The segment.
 * @property receipt Its receipt, once ended.
 * @property reopened For `reopen`: false when the segment was already open.
 */
@Serializable
data class SessionResponse(val session: SessionDto, val receipt: JsonObject? = null, val reopened: Boolean? = null)

/**
 * A page of sessions (contract `listSessions`).
 *
 * @property items The segments, newest first.
 * @property next The cursor of the next page.
 */
@Serializable
data class SessionsPage(val items: List<SessionDto>, val next: String? = null)
