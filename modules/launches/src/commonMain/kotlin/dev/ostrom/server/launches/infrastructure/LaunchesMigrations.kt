// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

/** The purge of the launches long expired, the module's one operation across organizations (version 4). */
private const val PURGE_FUNCTION = "launches_purge(TIMESTAMPTZ)"

/**
 * Version 4: the launches long expired are deleted, across organizations (the launch path's load test found every
 * launch kept for ever, a row per `ostrom <agent>`). A `SECURITY DEFINER` function owned by `ostrom_router`
 * (`NOLOGIN`, never a member of the serving role), with `pg_temp` last in its `search_path` and executable by the
 * serving role only, deletes the launches expired before the date it is given, and nothing else: the router's policies
 * hold only inside it (`current_user = 'ostrom_router'`). An index on the expiry keeps the hourly purge off the table.
 */
private val PURGE_LAUNCHES_SQL: String =
    """
    CREATE INDEX launches_launches_expiry ON launches_launches (expires_at);
    DO $$ BEGIN
        CREATE ROLE ostrom_router NOLOGIN NOSUPERUSER NOBYPASSRLS;
    EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL;
    END $$;
    DO $$ BEGIN
        GRANT ostrom_router TO CURRENT_USER;
    EXCEPTION WHEN unique_violation THEN NULL;
    END $$;
    GRANT USAGE ON SCHEMA public TO ostrom_router;
    CREATE POLICY launches_launches_router_read ON launches_launches FOR SELECT TO ostrom_router
        USING (current_user = 'ostrom_router');
    CREATE POLICY launches_launches_router_purge ON launches_launches FOR DELETE TO ostrom_router
        USING (current_user = 'ostrom_router');
    GRANT SELECT, DELETE ON launches_launches TO ostrom_router;
    CREATE FUNCTION launches_purge(p_before TIMESTAMPTZ) RETURNS BIGINT
        LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        WITH gone AS (DELETE FROM launches_launches WHERE expires_at < p_before RETURNING 1) SELECT count(*) FROM gone;
    $$;
    REVOKE EXECUTE ON FUNCTION $PURGE_FUNCTION FROM PUBLIC;
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
            GRANT EXECUTE ON FUNCTION $PURGE_FUNCTION TO ostrom_app;
        END IF;
    END $$;
    GRANT CREATE ON SCHEMA public TO ostrom_router;
    ALTER FUNCTION $PURGE_FUNCTION OWNER TO ostrom_router;
    REVOKE CREATE ON SCHEMA public FROM ostrom_router;
    """
        .trimIndent()

/**
 * Version 5 (audit SERVEUR-3 B3): the purge function crosses row-level security for every organization, so it bounds
 * the date it is given itself. No date, an infinite one or one in the future is refused; and it never deletes a launch
 * expired since less than the retention (`LaunchesModule.LAUNCH_RETENTION`, 30 days), whatever the date. The function
 * keeps its owner, its grants and its `search_path`.
 */
private val BOUND_PURGE_SQL: String =
    """
    CREATE OR REPLACE FUNCTION launches_purge(p_before TIMESTAMPTZ) RETURNS BIGINT
        LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        DECLARE
            gone BIGINT;
        BEGIN
            IF p_before IS NULL OR NOT isfinite(p_before) OR p_before > now() THEN
                RAISE EXCEPTION 'purge date refused: no date, an infinite one or one in the future'
                    USING ERRCODE = '22023';
            END IF;
            DELETE FROM launches_launches WHERE expires_at < LEAST(p_before, now() - INTERVAL '30 days');
            GET DIAGNOSTICS gone = ROW_COUNT;
            RETURN gone;
        END
    $$;
    """
        .trimIndent()

/**
 * The launches schema. Rows are isolated by organization in the database itself: row level security is forced (the
 * owner included) and every query runs in a transaction that first sets `ostrom.org_id` (`withOrganization`). An agent
 * token names its organization, so even resolving it reads within that organization only.
 */
val LaunchesMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        1,
        "create_launches",
        """
        CREATE TABLE launches_launches (
            org_id UUID NOT NULL,
            id TEXT NOT NULL,
            user_id UUID NOT NULL,
            dev TEXT NOT NULL,
            repo TEXT,
            agent TEXT NOT NULL,
            model TEXT,
            created_at TIMESTAMPTZ NOT NULL,
            expires_at TIMESTAMPTZ NOT NULL,
            PRIMARY KEY (org_id, id)
        );
        ALTER TABLE launches_launches ENABLE ROW LEVEL SECURITY;
        ALTER TABLE launches_launches FORCE ROW LEVEL SECURITY;
        CREATE POLICY launches_launches_org ON launches_launches
            USING (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID))
            WITH CHECK (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID))
        """.trimIndent(),
    ),
    // A launch opened by the runner of a cloud session names it: the runner reports that launch's checks.
    SchemaMigration(
        2,
        "launch_cloud_session",
        "ALTER TABLE launches_launches ADD COLUMN cloud_session_id UUID",
    ),
    // The launch limit counts a person's, then the organization's, launches of the last window (AUDIT-SECU-2 B9).
    SchemaMigration(
        3,
        "launch_limit_indexes",
        """
        CREATE INDEX launches_launches_person ON launches_launches (org_id, user_id, created_at);
        CREATE INDEX launches_launches_created ON launches_launches (org_id, created_at)
        """.trimIndent(),
    ),
    SchemaMigration(4, "purge_launches", PURGE_LAUNCHES_SQL),
    SchemaMigration(5, "bound_launch_purge", BOUND_PURGE_SQL),
    // A launch names the workflow it plays (`createLaunch.workflowId`): its model calls count under it.
    SchemaMigration(6, "launch_workflow", "ALTER TABLE launches_launches ADD COLUMN workflow_id TEXT"),
)
