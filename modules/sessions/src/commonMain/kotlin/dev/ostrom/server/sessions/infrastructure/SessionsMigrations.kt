// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sessions.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

/**
 * The sessions schema. Rows are isolated by organization in the database itself: row level security is forced (the
 * owner included) and every query runs in a transaction that first sets `ostrom.org_id`. One read crosses
 * organizations: the sweep of lost sessions, which sets `ostrom.sweep` in its own read-only transaction; that policy
 * is for `SELECT` only, so nothing can be written that way. The count of running sessions crosses them through a
 * function of `ostrom_router` (migration 2, docs/tenancy.md).
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
    // The count of the `ostrom.sessions.active{where="local"}` gauge, across organizations (docs/tenancy.md): a
    // function of `ostrom_router`, which sees two time columns alone, through a read-only policy of its own.
    SchemaMigration(
        2,
        "sessions_active_count",
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
        CREATE POLICY sessions_sessions_router ON sessions_sessions FOR SELECT TO ostrom_router USING (true);
        GRANT SELECT (ended_at, last_seen_at) ON sessions_sessions TO ostrom_router;
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
