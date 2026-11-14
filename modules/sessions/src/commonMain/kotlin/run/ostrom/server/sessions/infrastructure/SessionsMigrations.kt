// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sessions.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration

/** The one read across organizations, the sweep of lost sessions: a function owned by `ostrom_router`. */
private val ROUTER_FUNCTIONS = listOf("sessions_stale(TIMESTAMPTZ, INTEGER)")

/**
 * Version 2: the sweep's policy, which any transaction could open by setting `ostrom.sweep` (RLS audit R4), gives way
 * to a narrow `SECURITY DEFINER` function owned by `ostrom_router` (`NOLOGIN`, never a member of the serving role),
 * with `pg_temp` last in its `search_path` and executable by the serving role only. It returns the lost sessions'
 * keys, the query it replaces, unchanged.
 */
private val SWEEP_THROUGH_THE_ROUTER_SQL: String =
    """
    DO $$ BEGIN
        CREATE ROLE ostrom_router NOLOGIN NOSUPERUSER NOBYPASSRLS;
    EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL;
    END $$;
    DO $$ BEGIN
        GRANT ostrom_router TO CURRENT_USER;
    EXCEPTION WHEN unique_violation THEN NULL;
    END $$;
    GRANT USAGE ON SCHEMA public TO ostrom_router;
    DROP POLICY sessions_sessions_sweep ON sessions_sessions;
    CREATE POLICY sessions_sessions_router ON sessions_sessions FOR SELECT TO ostrom_router USING (true);
    GRANT SELECT ON sessions_sessions TO ostrom_router;
    CREATE FUNCTION sessions_stale(p_cutoff TIMESTAMPTZ, p_limit INTEGER)
        RETURNS TABLE (org_id UUID, id TEXT, last_seen_at TIMESTAMPTZ)
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT s.org_id, s.id, s.last_seen_at FROM sessions_sessions s
        WHERE s.ended_at IS NULL AND s.last_seen_at IS NOT NULL AND s.last_seen_at < p_cutoff
        ORDER BY s.last_seen_at LIMIT p_limit;
    $$;
    ${ROUTER_FUNCTIONS.joinToString("\n") { "REVOKE EXECUTE ON FUNCTION $it FROM PUBLIC;" }}
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
            ${ROUTER_FUNCTIONS.joinToString("\n") { "GRANT EXECUTE ON FUNCTION $it TO ostrom_app;" }}
        END IF;
    END $$;
    GRANT CREATE ON SCHEMA public TO ostrom_router;
    ${ROUTER_FUNCTIONS.joinToString("\n") { "ALTER FUNCTION $it OWNER TO ostrom_router;" }}
    REVOKE CREATE ON SCHEMA public FROM ostrom_router;
    """
        .trimIndent()

/**
 * The sessions schema. Rows are isolated by organization in the database itself: row level security is forced (the
 * owner included) and every query runs in a transaction that first sets `ostrom.org_id`. One read crosses
 * organizations: the sweep of lost sessions, only through `sessions_stale` (version 2), and the count of running
 * sessions, only through `sessions_active_count` (version 4, docs/tenancy.md).
 */
val SessionsMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        1,
        "create_sessions",
        """
        CREATE TABLE sessions_sessions (
            org_id UUID NOT NULL,
            id TEXT NOT NULL,
            root_id TEXT NOT NULL,
            segment INTEGER NOT NULL,
            resumed_from TEXT,
            previous_receipt_id TEXT,
            launch_id TEXT,
            repo TEXT,
            real_repo TEXT,
            cwd TEXT,
            agent TEXT NOT NULL,
            dev TEXT NOT NULL,
            harness_version TEXT,
            package_digest TEXT,
            model TEXT,
            source TEXT,
            started_at TIMESTAMPTZ NOT NULL,
            ended_at TIMESTAMPTZ,
            last_seen_at TIMESTAMPTZ,
            receipt_id TEXT,
            tools INTEGER,
            cost_usd DOUBLE PRECISION,
            exit_code INTEGER,
            seen TEXT NOT NULL DEFAULT '[]',
            PRIMARY KEY (org_id, id),
            UNIQUE (org_id, root_id, segment)
        );
        CREATE INDEX sessions_recent ON sessions_sessions (org_id, started_at DESC, id DESC);
        CREATE INDEX sessions_stale ON sessions_sessions (last_seen_at)
            WHERE ended_at IS NULL AND last_seen_at IS NOT NULL;
        ALTER TABLE sessions_sessions ENABLE ROW LEVEL SECURITY;
        ALTER TABLE sessions_sessions FORCE ROW LEVEL SECURITY;
        CREATE POLICY sessions_sessions_org ON sessions_sessions
            USING (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID))
            WITH CHECK (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID));
        CREATE POLICY sessions_sessions_sweep ON sessions_sessions FOR SELECT
            USING (current_setting('ostrom.sweep', TRUE) = 'lost-sessions')
        """.trimIndent(),
    ),
    SchemaMigration(2, "sweep_through_the_router", SWEEP_THROUGH_THE_ROUTER_SQL),
    // A launch's sessions, first segment first: what a model call through the gateway is spent in (the reports'
    // spending by agent and repository), and the sessions of a launch the API lists.
    SchemaMigration(
        3,
        "index_sessions_by_launch",
        "CREATE INDEX sessions_launch ON sessions_sessions (org_id, launch_id, segment)",
    ),
    // The count of the `ostrom.sessions.active{where="local"}` gauge, across organizations (docs/tenancy.md): a
    // function of `ostrom_router`, through the router's read-only policy of version 2.
    SchemaMigration(
        4,
        "sessions_active_count",
        """
        CREATE FUNCTION sessions_active_count(p_seen_since TIMESTAMPTZ) RETURNS BIGINT
            LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
            SELECT count(*) FROM sessions_sessions WHERE ended_at IS NULL AND last_seen_at >= p_seen_since;
        $$;
        REVOKE EXECUTE ON FUNCTION sessions_active_count(TIMESTAMPTZ) FROM PUBLIC;
        DO $$ BEGIN
            IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
                GRANT EXECUTE ON FUNCTION sessions_active_count(TIMESTAMPTZ) TO ostrom_app;
            END IF;
        END $$;
        GRANT CREATE ON SCHEMA public TO ostrom_router;
        ALTER FUNCTION sessions_active_count(TIMESTAMPTZ) OWNER TO ostrom_router;
        REVOKE CREATE ON SCHEMA public FROM ostrom_router
        """.trimIndent(),
    ),
    // RLS audit R9: the router's policies hold only inside its own functions, as identity's
    // do; the owner, a member of `ostrom_router` to hand them over, no longer passes them.
    SchemaMigration(
        version = 5,
        name = "sessions_router_policies_inside_functions_only",
        sql = """
            ALTER POLICY sessions_sessions_router ON sessions_sessions USING (current_user = 'ostrom_router')
        """.trimIndent(),
    ),
    // The filters of `GET /sessions` (SessionsReadsBenchTest): by developer and the open ones, newest first, read the
    // organization's recent sessions and kept those that matched (4 995 read for a rare developer's 5). Their cost is
    // on the heartbeat: `last_seen_at` is indexed (`sessions_stale`), so no heartbeat is a HOT update and each one
    // writes every index of the table again, these two included (a heartbeat only touches an open session, and every
    // open session is in `sessions_open`). Two more entries per heartbeat are taken for the two filters the bench found
    // slowest; none for the repository, whose rarest value reads the organization's recent sessions within the read
    // budget on the bench's tables (SessionsReadsBenchTest). Built in the migration's transaction, not CONCURRENTLY:
    // session writes wait for the build, short on the tables of today.
    SchemaMigration(
        6,
        "index_sessions_filters",
        """
        CREATE INDEX sessions_by_dev ON sessions_sessions (org_id, dev, started_at DESC, id DESC);
        CREATE INDEX sessions_open ON sessions_sessions (org_id, started_at DESC, id DESC) WHERE ended_at IS NULL
        """.trimIndent(),
    ),
    // Whether the agent works or waits for a person (`sessions.activity.v1`), TeamCode's `agent_runs.activity`,
    // `activity_at` and `activity_observed_at`. Nullable columns without a default: adding them rewrites no row, and
    // no index reads them, so a report is a HOT update.
    SchemaMigration(
        7,
        "sessions_activity",
        """
        ALTER TABLE sessions_sessions
            ADD COLUMN activity TEXT,
            ADD COLUMN activity_at TIMESTAMPTZ,
            ADD COLUMN activity_observed_at TIMESTAMPTZ
        """.trimIndent(),
    ),
)
