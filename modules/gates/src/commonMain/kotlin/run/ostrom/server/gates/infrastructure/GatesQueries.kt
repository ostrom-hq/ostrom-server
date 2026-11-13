// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates.infrastructure

/**
 * The reads of the gates' request path, shared by [PostgresGatesRepository] and the test that reads their plans as the
 * serving role, under row level security (`GatesQueryPlansTest`). `:org` is the organization; the policies of the
 * tables read it too, from `ostrom.org_id`.
 */
internal object GatesQueries {
    const val MAX_REQUESTS = 100
    const val MAX_SESSION_GATES = 500

    const val GATE_COLUMNS = "id, org_id, gate_key, subject, node, label, mode, status, asked_by, asked_via, " +
        "session_ids, launch_ids, shown_digest, evidence, decided_by, decided_at, self_approved, receipt_id, " +
        "reserved_by, reserved_at, consumed_at, released_at, created_at, unlinked_author"
    const val REQUEST_COLUMNS =
        "id, gate_id, org_id, position, approver_id, status, answered_at, channel, created_at"

    /**
     * The gates a session asked or joined, oldest first (`:session`), through their links (`gates_sessions`): under row
     * level security a qual that is not leakproof, like `jsonb @>` on `session_ids`, is never an index condition.
     */
    val OF_SESSION = "SELECT " + GATE_COLUMNS.split(", ").joinToString(", ") { "g.$it" } +
        " FROM gates_sessions s JOIN gates_gates g ON g.id = s.gate_id " +
        "WHERE s.org_id = :org AND s.session_id = :session AND g.org_id = :org " +
        "ORDER BY g.created_at, g.id LIMIT $MAX_SESSION_GATES"

    /**
     * Whether `:approver` (any case) has a request on a gate of `:session`: the session's links by their primary key,
     * then the requests of each gate by `gates_requests_by_gate`.
     */
    const val APPROVES = "SELECT s.session_id FROM gates_sessions s JOIN gates_requests r ON r.gate_id = s.gate_id " +
        "WHERE s.org_id = :org AND s.session_id = :session AND r.org_id = :org " +
        "AND r.approver_key = lower(:approver) LIMIT 1"

    /**
     * The sessions of the gates `:approver` (any case) has a request on: the approver's requests by the inbox index,
     * then each gate's links by `gates_sessions_by_gate` (migration 9).
     */
    const val APPROVED_SESSIONS = "SELECT DISTINCT s.session_id FROM gates_requests r " +
        "JOIN gates_sessions s ON s.org_id = r.org_id AND s.gate_id = r.gate_id " +
        "WHERE r.org_id = :org AND r.approver_key = lower(:approver) AND s.org_id = :org"

    /** When a session's end read its gates (`:session`), on the primary key of `gates_session_ends`. */
    const val SESSION_ENDED =
        "SELECT ended_at FROM gates_session_ends WHERE org_id = :org AND session_id = :session"

    /** Marks a session ended at `:at`; a session already marked keeps its first time. */
    const val MARK_SESSION_ENDED = "INSERT INTO gates_session_ends (org_id, session_id, ended_at) " +
        "VALUES (:org, :session, :at) ON CONFLICT (org_id, session_id) DO NOTHING"

    /**
     * The gates opened since `:since`, newest first, at most `:limit`: the rules' replay, read by a background worker
     * once per replay, never on the request path. The organization's prefix of `gates_gates_by_key` finds its rows.
     */
    const val RECENT = "SELECT $GATE_COLUMNS FROM gates_gates WHERE org_id = :org AND created_at >= :since " +
        "ORDER BY created_at DESC, id DESC LIMIT :limit"

    /** The newest gates of one action (`:key`), under the action's advisory lock, at most `:limit`. */
    const val BY_KEY = "SELECT $GATE_COLUMNS FROM gates_gates WHERE org_id = :org AND gate_key = :key " +
        "ORDER BY created_at DESC, id DESC LIMIT :limit"

    /** One approval request (`:id`). */
    const val REQUEST = "SELECT $REQUEST_COLUMNS FROM gates_requests WHERE org_id = :org AND id = :id"

    /** The approval requests of one gate (`:gate`), in the approvers' order. */
    const val REQUESTS_OF = "SELECT $REQUEST_COLUMNS FROM gates_requests WHERE org_id = :org AND gate_id = :gate " +
        "ORDER BY position LIMIT $MAX_REQUESTS"

    /**
     * The approval requests of several gates (`:g0` to `:g<count - 1>`), each gate's in the approvers' order.
     *
     * @param count How many gate ids are bound.
     * @return The SQL.
     */
    fun requestsOf(count: Int): String =
        "SELECT $REQUEST_COLUMNS FROM gates_requests WHERE org_id = :org AND gate_id IN (" +
            (0 until count).joinToString(", ") { ":g$it" } + ") ORDER BY gate_id, position"

    /**
     * Links a gate (`:gate`) to the sessions that asked or joined it (`:s0` to `:s<count - 1>`); a link already there
     * stays.
     *
     * @param count How many session ids are bound.
     * @return The SQL.
     */
    fun linkSessions(count: Int): String =
        "INSERT INTO gates_sessions (org_id, session_id, gate_id) VALUES " +
            (0 until count).joinToString(", ") { "(:org, :s$it, :gate)" } + " ON CONFLICT DO NOTHING"

    /**
     * One gate (`:id`), locked for the transaction when [lock] (an answer, a reservation, a use).
     *
     * @param lock Whether the row is locked (`FOR UPDATE`).
     * @return The SQL.
     */
    fun gate(lock: Boolean): String =
        "SELECT $GATE_COLUMNS FROM gates_gates WHERE org_id = :org AND id = :id" + if (lock) " FOR UPDATE" else ""

    /**
     * The gates of a page of approvals, by id: `:g0` to `:g<count - 1>`.
     *
     * @param count How many ids are bound.
     * @return The SQL.
     */
    fun gates(count: Int): String =
        "SELECT $GATE_COLUMNS FROM gates_gates WHERE org_id = :org AND id IN (" +
            (0 until count).joinToString(", ") { ":g$it" } + ")"

    /**
     * A page of approval requests, newest first, at most `:limit`: an approver's (`:approver`) when [approver], of one
     * status (`:status`) when [status], older than the request `:before` when [cursor].
     *
     * @param approver Whether the page is one approver's.
     * @param status Whether the page is of one status.
     * @param cursor Whether the page starts after a request.
     * @return The SQL.
     */
    fun approvals(approver: Boolean, status: Boolean, cursor: Boolean = false): String {
        val filters = buildString {
            append("org_id = :org")
            // Identities compare without case everywhere (`SelfApproval.same`), the inbox too: `approver_key` is the
            // lowered approver, so the comparison is a leakproof `=`, an index condition under row level security.
            if (approver) append(" AND approver_key = lower(:approver)")
            if (status) append(" AND status = :status")
            if (cursor) {
                append(
                    " AND (created_at, id) < (SELECT created_at, id FROM gates_requests WHERE org_id = :org " +
                        "AND id = :before)",
                )
            }
        }
        return "SELECT $REQUEST_COLUMNS FROM gates_requests WHERE $filters " +
            "ORDER BY created_at DESC, id DESC LIMIT :limit"
    }
}
