// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.reports.domain

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import kotlin.time.Instant

/**
 * What happened, as the contract's `ActivityItem.kind`.
 *
 * @property wire The stable name.
 * @property byAgent Whether an agent did it (a session, a gate it opened) rather than a person.
 */
enum class ActivityKind(val wire: String, val byAgent: Boolean) {
    /** An agent session started. */
    SESSION_STARTED("session.started", byAgent = true),

    /** An agent session ended, with its cost. */
    SESSION_ENDED("session.ended", byAgent = true),

    /** A gate asked for a person's yes (its first asker). */
    GATE_REQUESTED("gate.requested", byAgent = true),

    /** A person said yes or no to a gate. */
    GATE_DECIDED("gate.decided", byAgent = false),

    /** A person accepted or edited a workflow (a signed decision). */
    WORKFLOW_CHANGED("workflow.changed", byAgent = false),
}

/**
 * A line of the activity feed.
 *
 * @property id Stable and unique: the kind and the id of what it is about.
 * @property at When it happened.
 * @property kind What happened.
 * @property actor Who did it: the agent of a session, the first asker of a gate, the person who decided (their login,
 *   or the id the source keeps when no login is known).
 * @property subject What it is about: the person who runs a session, the label of a gate, the id of a workflow.
 * @property outcome How a gate was decided: `approved` or `rejected`.
 * @property repo The repository (`owner/name`), for a session or a gate that has one.
 * @property costMicros What an ended session cost, as the server measured it.
 * @property sessionId The session, for a session item.
 * @property gateId The gate, for a gate item.
 */
data class ActivityItem(
    val id: String,
    val at: Instant,
    val kind: ActivityKind,
    val actor: String,
    val subject: String,
    val outcome: String? = null,
    val repo: String? = null,
    val costMicros: Long? = null,
    val sessionId: String? = null,
    val gateId: String? = null,
) {
    /** Where the page after this item starts. */
    val cursor: ActivityCursor get() = ActivityCursor(at, id)
}

/**
 * A position in the feed: items strictly older than ([at], [id]).
 *
 * @property at The time of the last item of the previous page.
 * @property id Its id, which orders items of the same instant.
 */
data class ActivityCursor(val at: Instant, val id: String) {
    /** The opaque form of `next` and `?before=`: `<ISO instant>~<id>`. */
    val wire: String get() = "$at$SEPARATOR$id"

    /** Reading a cursor. */
    companion object {
        private const val SEPARATOR = '~'

        /**
         * Reads `?before=`.
         *
         * @param wire The value a previous page gave as `next`.
         * @return The cursor.
         * @throws InvalidCursorException When it is not one.
         */
        fun of(wire: String): ActivityCursor {
            val at = wire.substringBefore(SEPARATOR, "")
            val id = wire.substringAfter(SEPARATOR, "")
            val instant = try {
                Instant.parse(at)
            } catch (e: IllegalArgumentException) {
                throw InvalidCursorException(e)
            }
            if (id.isEmpty()) throw InvalidCursorException(null)
            return ActivityCursor(instant, id)
        }
    }
}

/**
 * A `?before=` that no page gave: 400.
 *
 * @param cause Why it could not be read.
 */
class InvalidCursorException(override val cause: Throwable?) :
    OstromException(ErrorKind.INVALID_REQUEST, "The cursor is not one a previous page gave.")

/**
 * A page of the feed.
 *
 * @property items The items, newest first.
 * @property next Where the next page starts, null on the last one.
 */
data class ActivityPage(val items: List<ActivityItem>, val next: ActivityCursor?)
