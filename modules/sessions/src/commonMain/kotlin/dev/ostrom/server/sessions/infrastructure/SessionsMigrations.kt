// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sessions.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

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
 * sessions, only through `sessions_active_count` (version 3, docs/tenancy.md).
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
    // The count of the `ostrom.sessions.active{where="local"}` gauge, across organizations (docs/tenancy.md): a
    // function of `ostrom_router`, through the router's read-only policy of version 2.
    SchemaMigration(
        3,
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
)
