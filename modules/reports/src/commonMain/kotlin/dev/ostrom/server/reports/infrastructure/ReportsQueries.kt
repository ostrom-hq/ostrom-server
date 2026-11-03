// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.reports.infrastructure

/**
 * The SQL of the reports, apart from the repository so that the query-plan test runs the very same text under
 * `EXPLAIN ANALYZE`.
 */
internal object ReportsQueries {
    /**
     * The ledger of a window joined to the session of each event, grouped four ways: `dim` is `agent`, `repo`,
     * `person` or `day`. Parameters `:org_id`, the window's parts ([SpendingRanges]: `:day_from`, `:day_to`,
     * `:head_from`, `:head_to`, `:tail_from`, `:tail_to`), and `:member_id` if [member].
     *
     * An event names its session by its id, or, for a model call through the gateway, by its launch (`launch_id`; its
     * `session_id` is then the launch's id too): the agent and the repository come from the session with that id, else
     * from the first segment of that launch, one session only so that nothing counts twice. The first segments of the
     * organization's launches are read once, in the order of their index, and hashed: no lookup per line. The launch is
     * compared as the column itself, no expression: under row level security only a leakproof comparison reaches the
     * index. A line's `sessions` counts the sessions its spending was found in: a launch counts once, a call outside
     * any session never.
     *
     * The ledger is first added up by member, session, launch and day (`calls`), then joined: a session's calls are
     * joined once per day, not once per call. The whole days of the window come from the daily totals the usage module
     * keeps with the ledger (`usage_daily`); only the hours before the first whole day and after the last are added up
     * from the calls, so the result is the one of the calls alone (ReportsDailyTotalsPostgresTest), and a month reads
     * a few thousand totals instead of every call (ReportsReadsBenchTest). The joined lines are added up in one pass
     * for the four ways at once (grouping sets, by session for all but the days), then by way: each session is counted
     * once per line without a sort.
     *
     * @param member Whether one member's spending only.
     * @return The statement.
     */
    fun spending(member: Boolean): String {
        val mine = if (member) " AND member_id = :member_id" else ""
        fun hours(from: String, to: String) = """
                SELECT org_id, member_id, session_id, launch_id,
                    CAST(at AT TIME ZONE 'UTC' AS DATE) AS day, SUM(cost_micros) AS cost_micros
                FROM usage_events
                WHERE org_id = :org_id AND at >= :$from AND at < :$to AND cost_micros > 0$mine
                GROUP BY 1, 2, 3, 4, 5"""
        return """
            WITH calls AS (
                SELECT org_id, member_id, session_id, launch_id, day, cost_micros
                FROM usage_daily
                WHERE org_id = :org_id AND day >= CAST(:day_from AS DATE) AND day < CAST(:day_to AS DATE)$mine
                UNION ALL${hours("head_from", "head_to")}
                UNION ALL${hours("tail_from", "tail_to")}
            ),
            spent AS (
                SELECT agent, repo, member_id, day, spent_in, GROUPING(agent, repo, member_id, day) AS g,
                    MIN(login) AS login, SUM(cost_micros) AS cost_micros
                FROM (
                    SELECT c.member_id, COALESCE(s.id, l.id) AS spent_in, c.cost_micros, c.day,
                        COALESCE(s.agent, l.agent) AS agent, COALESCE(s.repo, l.repo) AS repo, u.login
                    FROM calls c
                    LEFT JOIN sessions_sessions s ON s.org_id = c.org_id AND s.id = c.session_id
                    LEFT JOIN (
                        SELECT DISTINCT ON (f.launch_id) f.launch_id, f.id, f.agent, f.repo FROM sessions_sessions f
                        WHERE f.org_id = :org_id AND f.launch_id IS NOT NULL ORDER BY f.launch_id, f.segment
                    ) l ON s.id IS NULL AND l.launch_id = c.launch_id
                    LEFT JOIN orgs_members m ON m.org_id = c.org_id AND m.id = c.member_id
                    LEFT JOIN identity_users u ON u.id = m.user_id
                ) x
                GROUP BY GROUPING SETS ((agent, spent_in), (repo, spent_in), (member_id, spent_in), (day))
            )
            SELECT CASE g WHEN $BY_AGENT THEN 'agent' WHEN $BY_REPO THEN 'repo' WHEN $BY_PERSON THEN 'person'
                    ELSE 'day' END AS dim,
                CASE g WHEN $BY_AGENT THEN COALESCE(agent, '') WHEN $BY_REPO THEN COALESCE(repo, '')
                    WHEN $BY_PERSON THEN COALESCE(CAST(member_id AS TEXT), '') ELSE to_char(day, 'YYYY-MM-DD')
                    END AS group_key,
                CASE g WHEN $BY_AGENT THEN COALESCE(agent, '') WHEN $BY_REPO THEN COALESCE(repo, '')
                    WHEN $BY_PERSON THEN COALESCE(MIN(login), CAST(member_id AS TEXT), '') ELSE '' END AS label,
                CAST(SUM(cost_micros) AS BIGINT) AS micros,
                CASE WHEN g = $BY_DAY THEN 0 ELSE COUNT(spent_in) END AS sessions
            FROM spent GROUP BY g, agent, repo, member_id, day
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

    // `GROUPING(agent, repo, member_id, day)` of each grouping set of the spending: a bit for each column left out.
    private const val BY_AGENT = 7
    private const val BY_REPO = 11
    private const val BY_PERSON = 13
    private const val BY_DAY = 14

    // A person who decided, kept as a user's id, is shown by their login, and only if they are a member of the
    // organization: an id written anywhere else, or of a stranger, is shown as it is (never another org's login).
    private const val UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
}
