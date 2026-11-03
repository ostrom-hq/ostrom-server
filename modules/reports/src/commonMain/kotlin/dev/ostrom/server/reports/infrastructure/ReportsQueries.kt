// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.reports.infrastructure

/**
 * The SQL of the reports, apart from the repository so that the query-plan test runs the very same text under
 * `EXPLAIN ANALYZE`.
 */
internal object ReportsQueries {
    /**
     * The ledger of a window joined to the session of each event, grouped four ways: `dim` is `agent`, `repo`,
     * `person` or `day`. Parameters `:org_id`, `:from`, `:to`, and `:member_id` if [member].
     *
     * An event names its session by its id, or, for a model call through the gateway, by its launch (`launch_id`; its
     * `session_id` is then the launch's id too): the agent and the repository come from the session with that id, else
     * from the first segment of that launch, one session only so that nothing counts twice. The launch is compared as
     * the column itself, no expression: under row level security only a leakproof comparison reaches the index. A
     * line's `sessions` counts the sessions its spending was found in: a launch counts once, a call outside any session
     * never.
     *
     * The ledger is first added up by member, session, launch and day (`calls`), then joined: a session's calls are
     * joined once per day, not once per call (ReportsReadsBenchTest measures it).
     *
     * @param member Whether one member's spending only.
     * @return The statement.
     */
    fun spending(member: Boolean): String {
        val mine = if (member) " AND e.member_id = :member_id" else ""
        return """
            WITH calls AS (
                SELECT e.org_id, e.member_id, e.session_id, e.launch_id,
                    CAST(e.at AT TIME ZONE 'UTC' AS DATE) AS day, SUM(e.cost_micros) AS cost_micros
                FROM usage_events e
                WHERE e.org_id = :org_id AND e.at >= :from AND e.at < :to AND e.cost_micros > 0$mine
                GROUP BY e.org_id, e.member_id, e.session_id, e.launch_id, CAST(e.at AT TIME ZONE 'UTC' AS DATE)
            ),
            spent AS (
                SELECT c.member_id, COALESCE(s.id, l.id) AS spent_in, c.cost_micros, c.day,
                    COALESCE(s.agent, l.agent) AS agent, COALESCE(s.repo, l.repo) AS repo, u.login
                FROM calls c
                LEFT JOIN sessions_sessions s ON s.org_id = c.org_id AND s.id = c.session_id
                LEFT JOIN LATERAL (
                    SELECT f.id, f.agent, f.repo FROM sessions_sessions f
                    WHERE s.id IS NULL AND f.org_id = c.org_id AND f.launch_id = c.launch_id
                    ORDER BY f.segment LIMIT 1
                ) l ON TRUE
                LEFT JOIN orgs_members m ON m.org_id = c.org_id AND m.id = c.member_id
                LEFT JOIN identity_users u ON u.id = m.user_id
            )
            SELECT 'agent' AS dim, COALESCE(agent, '') AS group_key, COALESCE(agent, '') AS label,
                CAST(SUM(cost_micros) AS BIGINT) AS micros, COUNT(DISTINCT spent_in) AS sessions
                FROM spent GROUP BY agent
            UNION ALL
            SELECT 'repo', COALESCE(repo, ''), COALESCE(repo, ''), CAST(SUM(cost_micros) AS BIGINT),
                COUNT(DISTINCT spent_in) FROM spent GROUP BY repo
            UNION ALL
            SELECT 'person', COALESCE(CAST(member_id AS TEXT), ''),
                COALESCE(MIN(login), CAST(member_id AS TEXT), ''), CAST(SUM(cost_micros) AS BIGINT),
                COUNT(DISTINCT spent_in) FROM spent GROUP BY member_id
            UNION ALL
            SELECT 'day', to_char(day, 'YYYY-MM-DD'), '', CAST(SUM(cost_micros) AS BIGINT), 0
                FROM spent GROUP BY 2
        """.trimIndent()
    }

    /**
     * A page of the activity, newest first by (`at`, `item_id`): each source gives its newest `:limit` rows in that
     * very order (its index gives the time, the id only breaks ties), then the union keeps the newest `:limit`. Params
     * `:org_id`, `:limit`, `:before_at` and `:before_id` if [cursor], `:repo` if [repo]. A gate's line carries the
     * repository of its subject ([GATE_REPO]); a workflow's carries none. With a repository, only sessions are read.
     *
     * @param cursor Whether the page starts before a position.
     * @param repo Whether only one repository's sessions.
     * @return The statement.
     */
    fun activity(cursor: Boolean, repo: Boolean): String {
        // The plain bound on the time lets the index start at the cursor; the row comparison breaks the ties.
        fun before(at: String, id: String) =
            if (cursor) " AND $at <= :before_at AND ($at, $id) < (:before_at, :before_id)" else ""
        val ofRepo = if (repo) " AND s.repo = :repo" else ""
        val started = "CONCAT('session.started:', s.id)"
        val ended = "CONCAT('session.ended:', s.id)"
        val asked = "CONCAT('gate.requested:', CAST(g.id AS TEXT))"
        val decided = "CONCAT('gate.decided:', CAST(g.id AS TEXT))"
        val changed = "CONCAT('workflow.changed:', d.id)"
        val decidedAt = "CAST(d.decided_at AS TIMESTAMPTZ)"
        // The askers are a JSON array, the first one opened the gate.
        val firstAsker = "COALESCE(CAST(g.asked_by AS JSONB) ->> 0, '')"
        val endedBefore = before("s.ended_at", ended)
        val decidedBefore = before("g.decided_at", decided)
        val sessions = """
            (SELECT $started AS item_id, s.started_at AS at, 'session.started' AS kind, s.agent AS actor,
                s.dev AS subject, CAST(NULL AS TEXT) AS outcome, s.repo AS repo,
                CAST(NULL AS DOUBLE PRECISION) AS cost_usd, s.id AS session_id, CAST(NULL AS TEXT) AS gate_id
                FROM sessions_sessions s WHERE s.org_id = :org_id$ofRepo${before("s.started_at", started)}
                ORDER BY s.started_at DESC, $started DESC LIMIT :limit)
            UNION ALL
            (SELECT $ended, s.ended_at, 'session.ended', s.agent, s.dev, NULL, s.repo, s.cost_usd, s.id, NULL
                FROM sessions_sessions s WHERE s.org_id = :org_id AND s.ended_at IS NOT NULL$ofRepo$endedBefore
                ORDER BY s.ended_at DESC, $ended DESC LIMIT :limit)
        """
        val others = """
            UNION ALL
            (SELECT $asked, g.created_at, 'gate.requested', $firstAsker, g.label, NULL, $GATE_REPO, NULL, NULL,
                CAST(g.id AS TEXT) FROM gates_gates g
                WHERE g.org_id = :org_id AND g.status NOT IN ('SHADOW', 'UNGATED')${before("g.created_at", asked)}
                ORDER BY g.created_at DESC, $asked DESC LIMIT :limit)
            UNION ALL
            (SELECT $decided, g.decided_at, 'gate.decided', g.decided_by, g.label,
                CASE WHEN g.status = 'REJECTED' THEN 'rejected' ELSE 'approved' END, $GATE_REPO, NULL, NULL,
                CAST(g.id AS TEXT) FROM gates_gates g
                WHERE g.org_id = :org_id AND g.decided_at IS NOT NULL AND g.decided_by IS NOT NULL$decidedBefore
                ORDER BY g.decided_at DESC, $decided DESC LIMIT :limit)
            UNION ALL
            (SELECT $changed, $decidedAt, 'workflow.changed', d.decided_by, d.subject_id, NULL, NULL, NULL, NULL,
                NULL FROM compiler_decisions d
                WHERE d.org_id = :org_id AND d.subject_kind = 'workflow'${before(decidedAt, changed)}
                ORDER BY $decidedAt DESC, $changed DESC LIMIT :limit)
        """
        val sources = if (repo) sessions else sessions + others
        return """
            SELECT feed.item_id, feed.at, feed.kind, COALESCE(u.login, feed.actor) AS actor, feed.subject,
                feed.outcome, feed.repo, feed.cost_usd, feed.session_id, feed.gate_id
            FROM ($sources) feed
            LEFT JOIN (orgs_members m JOIN identity_users u ON u.id = m.user_id)
                ON m.org_id = :org_id AND feed.kind IN ('gate.decided', 'workflow.changed')
                AND m.user_id = CASE WHEN feed.actor ~ '$UUID_PATTERN' THEN CAST(feed.actor AS UUID) END
            ORDER BY feed.at DESC, feed.item_id DESC LIMIT :limit
        """.trimIndent()
    }

    // The repository of a gate's subject (`owner/name`): the one the command really ran in when the hook read another
    // (`realRepo`), else the one its session declared (`repo`); none outside a repository. The subject is the gate's
    // own row, so it is never another organization's.
    private const val GATE_REPO = "NULLIF(COALESCE(NULLIF(CAST(g.subject AS JSONB) ->> 'realRepo', ''), " +
        "CAST(g.subject AS JSONB) ->> 'repo'), '')"

    // A person who decided, kept as a user's id, is shown by their login, and only if they are a member of the
    // organization: an id written anywhere else, or of a stranger, is shown as it is (never another org's login).
    private const val UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
}
