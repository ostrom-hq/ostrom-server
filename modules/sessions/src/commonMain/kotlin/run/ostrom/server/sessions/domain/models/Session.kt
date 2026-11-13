// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sessions.domain.models

import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A coding agent session, one segment of it (CORRECTIFS-2 of the prototype): a launch is a segment. A session resumed
 * by another launch (`claude --resume` keeps its id) opens segment `<id>~r<n>`, chained to the one before, whose
 * receipt it cites.
 *
 * @property orgId The organization.
 * @property id The segment id: the agent's session id for the first segment, `<id>~r<n>` after.
 * @property rootId The agent's session id, the same for every segment.
 * @property segment The segment number, from 1.
 * @property resumedFrom The segment this one follows.
 * @property previousReceiptId The receipt of the segment this one follows.
 * @property launchId The launch (`ostrom <agent>`) that runs it.
 * @property repo The repository the session declared.
 * @property realRepo The repository it really runs in, when it differs.
 * @property cwd The working directory.
 * @property agent The coding agent (`claude-code`, `codex`…).
 * @property dev The person who runs it, read from the credential, never from the body.
 * @property harnessVersion The launcher or plugin version.
 * @property packageDigest The session package the launch runs with.
 * @property model The model.
 * @property source How it started or ended (`superseded: …`, `launcher lost: …`, `reopened: …`).
 * @property startedAt When it started.
 * @property endedAt When it ended; a session that ended takes no more writes.
 * @property lastSeenAt The launcher's last sign of life; a session without one for too long is closed as lost.
 * @property receiptId Its signed receipt, once ended.
 * @property tools How many tool calls it made.
 * @property costUsd What it cost.
 * @property exitCode The launcher's exit code.
 * @property seen The skills and sub-agents of its package it opened.
 */
data class Session(
    val orgId: Uuid,
    val id: String,
    val rootId: String,
    val segment: Int,
    val resumedFrom: String? = null,
    val previousReceiptId: String? = null,
    val launchId: String? = null,
    val repo: String? = null,
    val realRepo: String? = null,
    val cwd: String? = null,
    val agent: String,
    val dev: String,
    val harnessVersion: String? = null,
    val packageDigest: String? = null,
    val model: String? = null,
    val source: String? = null,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val lastSeenAt: Instant? = null,
    val receiptId: String? = null,
    val tools: Int? = null,
    val costUsd: Double? = null,
    val exitCode: Int? = null,
    val seen: List<SeenItem> = emptyList(),
) {
    /** True when Ostrom closed it for lack of a sign of life: its launcher, still there, reopens a segment. */
    val lost: Boolean get() = endedAt != null && source.orEmpty().startsWith(LOST)

    /** The prefixes of [source]. */
    companion object {
        /** A segment closed by the sweep: its launcher gave no sign of life. */
        const val LOST: String = "launcher lost"

        /** A segment closed because another launch resumed the session. */
        const val SUPERSEDED: String = "superseded: the session was resumed by another launch"
    }
}

/**
 * A skill or sub-agent of the session package that the agent opened.
 *
 * @property kind `skill` or `agent`.
 * @property name Its name.
 * @property via How it was opened (`hook`, `mcp`).
 * @property at When.
 */
data class SeenItem(val kind: String, val name: String, val via: String, val at: Instant)

/**
 * A session with what its single reads show.
 *
 * @property session The segment.
 * @property gates The gates it asked or joined.
 * @property receipt Its signed receipt, once ended.
 * @property answers The answers to those of its gates someone answered, oldest gate first.
 */
data class SessionView(
    val session: Session,
    val gates: List<String>,
    val receipt: JsonObject?,
    val answers: List<GateAnswer>,
)

/**
 * The answer to a gate a session asked or joined: what the session's thread (`ostrom logs`) shows of it, to a token
 * that reads the session without reading the gates.
 *
 * @property gateId The gate.
 * @property actionClass Its action class.
 * @property approved Yes (the yes may since have been reserved or used) or no.
 * @property by Who answered.
 * @property at When.
 * @property receiptId The gate's signed receipt, once signed.
 */
data class GateAnswer(
    val gateId: String,
    val actionClass: String,
    val approved: Boolean,
    val by: String,
    val at: Instant,
    val receiptId: String?,
)

/**
 * Which sessions to list, newest first.
 *
 * @property repo Only this repository.
 * @property dev Only this person's.
 * @property open Only the open ones (true) or the ended ones (false).
 * @property before Only those started before this segment (the cursor of the previous page).
 * @property launchId Only this launch's (an agent token reads its own launch only).
 * @property limit At most this many.
 */
data class SessionQuery(
    val repo: String? = null,
    val dev: String? = null,
    val open: Boolean? = null,
    val before: String? = null,
    val launchId: String? = null,
    val limit: Int = PAGE,
) {
    /** The page size. */
    companion object {
        /** Sessions per page. */
        const val PAGE: Int = 100
    }
}
