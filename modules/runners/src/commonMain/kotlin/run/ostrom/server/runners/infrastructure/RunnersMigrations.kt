// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration

/** What the reconciler and the runner channel do across organizations: functions owned by `ostrom_router`. */
private val ROUTER_FUNCTIONS = listOf(
    "runners_session_org(UUID)",
    "runners_session_by_token(TEXT)",
    "runners_claim_due(TEXT, TIMESTAMPTZ, TIMESTAMPTZ, INTEGER)",
    "runners_release(UUID, TEXT, BOOLEAN, TIMESTAMPTZ)",
)

/**
 * Version 3: `runners_session_routes` under forced row-level security like the other tables (a request reads and
 * writes only its organization's routes), and what crosses organizations moved into narrow `SECURITY DEFINER`
 * functions owned by `ostrom_router` (`NOLOGIN`, never a member of the serving role), with `pg_temp` last in their
 * `search_path`, executable by the serving role only (RLS audit R2 and R3). The claim and the release are the
 * statements they replace, unchanged: one replica leases a due session (`FOR UPDATE SKIP LOCKED`), and a request for a
 * new look made during the pass is kept.
 */
private val ROUTES_UNDER_ROW_LEVEL_SECURITY_SQL: String =
    """
    ALTER TABLE runners_session_routes ENABLE ROW LEVEL SECURITY;
    ALTER TABLE runners_session_routes FORCE ROW LEVEL SECURITY;
    CREATE POLICY runners_session_routes_tenant ON runners_session_routes
        USING (org_id = CAST(NULLIF(current_setting('$TENANT_SETTING', true), '') AS UUID))
        WITH CHECK (org_id = CAST(NULLIF(current_setting('$TENANT_SETTING', true), '') AS UUID));
    DO $$ BEGIN
        CREATE ROLE ostrom_router NOLOGIN NOSUPERUSER NOBYPASSRLS;
    EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL;
    END $$;
    DO $$ BEGIN
        GRANT ostrom_router TO CURRENT_USER;
    EXCEPTION WHEN unique_violation THEN NULL;
    END $$;
    GRANT USAGE ON SCHEMA public TO ostrom_router;
    CREATE POLICY runners_session_routes_router ON runners_session_routes TO ostrom_router USING (true)
        WITH CHECK (true);
    GRANT SELECT, UPDATE ON runners_session_routes TO ostrom_router;
    CREATE FUNCTION runners_session_org(p_id UUID) RETURNS UUID
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT r.org_id FROM runners_session_routes r WHERE r.session_id = p_id;
    $$;
    CREATE FUNCTION runners_session_by_token(p_hash TEXT) RETURNS TABLE (session_id UUID, org_id UUID)
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT r.session_id, r.org_id FROM runners_session_routes r WHERE r.runner_token_hash = p_hash;
    $$;
    CREATE FUNCTION runners_claim_due(p_owner TEXT, p_until TIMESTAMPTZ, p_now TIMESTAMPTZ, p_limit INTEGER)
        RETURNS TABLE (session_id UUID, org_id UUID)
        LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        UPDATE runners_session_routes r SET reconcile_owner = p_owner, reconcile_until = p_until, claimed_at = p_now
        WHERE r.session_id IN (
            SELECT d.session_id FROM runners_session_routes d WHERE d.next_reconcile_at <= p_now
            AND (d.reconcile_until IS NULL OR d.reconcile_until < p_now) ORDER BY d.next_reconcile_at LIMIT p_limit
            FOR UPDATE SKIP LOCKED
        ) RETURNING r.session_id, r.org_id;
    $$;
    CREATE FUNCTION runners_release(p_id UUID, p_owner TEXT, p_has_next BOOLEAN, p_next_at TIMESTAMPTZ) RETURNS VOID
        LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        UPDATE runners_session_routes SET reconcile_owner = NULL, reconcile_until = NULL, next_reconcile_at = CASE
            WHEN next_reconcile_at > claimed_at AND p_has_next THEN LEAST(next_reconcile_at, p_next_at)
            WHEN next_reconcile_at > claimed_at THEN next_reconcile_at
            WHEN p_has_next THEN p_next_at ELSE NULL END
        WHERE session_id = p_id AND reconcile_owner = p_owner;
    $$;
    ${ROUTER_FUNCTIONS.joinToString("\n") { "REVOKE EXECUTE ON FUNCTION $it FROM PUBLIC;" }}
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
            ${ROUTER_FUNCTIONS.joinToString("\n            ") { "GRANT EXECUTE ON FUNCTION $it TO ostrom_app;" }}
        END IF;
    END $$;
    GRANT CREATE ON SCHEMA public TO ostrom_router;
    ${ROUTER_FUNCTIONS.joinToString("\n") { "ALTER FUNCTION $it OWNER TO ostrom_router;" }}
    REVOKE CREATE ON SCHEMA public FROM ostrom_router;
    """
        .trimIndent()

/** What a registered runner's heartbeat reads across organizations: a function owned by `ostrom_router`. */
private val REGISTERED_ROUTER_FUNCTIONS = listOf("runners_registered_key(UUID)")

private val REGISTERED_ROUTER_GRANTS =
    REGISTERED_ROUTER_FUNCTIONS.joinToString("\n") { "GRANT EXECUTE ON FUNCTION $it TO ostrom_app;" }

/**
 * Version 4: a registered runner's heartbeat names only the runner, so its organization is read across organizations
 * by one narrow `SECURITY DEFINER` function owned by `ostrom_router` (`NOLOGIN`, never a member of the serving role),
 * with `pg_temp` last in its `search_path`, executable by the serving role only (docs/tenancy.md). It returns the
 * organization and key hash of one runner id and nothing else; the sign of life is then written in that organization's
 * transaction, only while the runner has that key.
 */
private val REGISTERED_RUNNER_ORG_SQL: String =
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
    CREATE POLICY runners_registered_router ON runners_registered TO ostrom_router USING (true) WITH CHECK (false);
    GRANT SELECT ON runners_registered TO ostrom_router;
    CREATE FUNCTION runners_registered_key(p_id UUID) RETURNS TABLE (org_id UUID, key_hash TEXT)
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT r.org_id, r.key_hash FROM runners_registered r WHERE r.id = p_id;
    $$;
    ${REGISTERED_ROUTER_FUNCTIONS.joinToString("\n") { "REVOKE EXECUTE ON FUNCTION $it FROM PUBLIC;" }}
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
            $REGISTERED_ROUTER_GRANTS
        END IF;
    END $$;
    GRANT CREATE ON SCHEMA public TO ostrom_router;
    ${REGISTERED_ROUTER_FUNCTIONS.joinToString("\n") { "ALTER FUNCTION $it OWNER TO ostrom_router;" }}
    REVOKE CREATE ON SCHEMA public FROM ostrom_router;
    """
        .trimIndent()

/**
 * The schema of the runners module; its tables start with `runners_`.
 *
 * `runners_sessions` and `runners_usage` are tenant tables, confined by row-level security to the organization of the
 * transaction (S2-01). `runners_session_routes` (a session's id and organization, the hash of its runner token and
 * its reconcile schedule, no customer content) is also used across organizations, by the reconciler (due sessions of
 * every organization) and by the runner channel (a session found by its runner token): since version 3, under forced
 * row-level security like the other tables, and across organizations only through [ROUTER_FUNCTIONS]
 * (docs/tenancy.md). `runners_registered` (version 2) is a tenant table too: the machines an organization registered,
 * with the hash of their key; a heartbeat reads one runner's organization and key hash across organizations only
 * through [REGISTERED_ROUTER_FUNCTIONS] (version 4). `runners_assignments` (version 6) is a tenant table: the
 * executions handed to an organization's registered runners.
 */
val RunnersMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        version = 1,
        name = "create_cloud_sessions",
        sql = """
            CREATE TABLE runners_sessions (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                owner_id TEXT NOT NULL,
                name TEXT NOT NULL,
                agent TEXT NOT NULL,
                model TEXT,
                prompt TEXT,
                repository_url TEXT,
                repository_ref TEXT,
                state TEXT NOT NULL,
                desired_state TEXT NOT NULL,
                phase TEXT,
                epoch BIGINT NOT NULL,
                generation BIGINT NOT NULL,
                failure_code TEXT,
                failure_message TEXT,
                idle_timeout_minutes INTEGER NOT NULL,
                max_run_minutes INTEGER NOT NULL,
                restricted_egress BOOLEAN NOT NULL,
                created_at TIMESTAMPTZ NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL,
                execution_started_at TIMESTAMPTZ,
                ready_at TIMESTAMPTZ,
                last_heartbeat_at TIMESTAMPTZ,
                last_activity_at TIMESTAMPTZ,
                paused_at TIMESTAMPTZ,
                terminated_at TIMESTAMPTZ,
                pausing_since TIMESTAMPTZ,
                runner_stopped_at TIMESTAMPTZ,
                runner_token_hash TEXT
            );
            CREATE INDEX runners_sessions_owner ON runners_sessions (org_id, owner_id, created_at DESC, id DESC);
            CREATE INDEX runners_sessions_holding ON runners_sessions (org_id, state);
            CREATE TABLE runners_usage (
                org_id UUID NOT NULL,
                month TEXT NOT NULL,
                seconds BIGINT NOT NULL,
                PRIMARY KEY (org_id, month)
            );
            CREATE TABLE runners_session_routes (
                session_id UUID PRIMARY KEY REFERENCES runners_sessions (id) ON DELETE CASCADE,
                org_id UUID NOT NULL,
                runner_token_hash TEXT,
                next_reconcile_at TIMESTAMPTZ,
                reconcile_owner TEXT,
                reconcile_until TIMESTAMPTZ,
                claimed_at TIMESTAMPTZ
            );
            CREATE INDEX runners_session_routes_due ON runners_session_routes (next_reconcile_at)
                WHERE next_reconcile_at IS NOT NULL;
            CREATE UNIQUE INDEX runners_session_routes_runner_token ON runners_session_routes (runner_token_hash)
                WHERE runner_token_hash IS NOT NULL;
            $TENANT_ROLE_SQL
            ${tenantTableSql("runners_sessions")}
            ${tenantTableSql("runners_usage")}
            GRANT SELECT, INSERT, UPDATE ON runners_session_routes TO $TENANT_ROLE;
        """.trimIndent(),
    ),
    SchemaMigration(
        version = 2,
        name = "create_registered_runners",
        sql = """
            CREATE TABLE runners_registered (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                name TEXT NOT NULL,
                labels TEXT NOT NULL,
                version TEXT,
                key_hash TEXT NOT NULL,
                registered_by TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL,
                last_seen_at TIMESTAMPTZ
            );
            CREATE UNIQUE INDEX runners_registered_name ON runners_registered (org_id, lower(name));
            CREATE UNIQUE INDEX runners_registered_key ON runners_registered (key_hash);
            ${tenantTableSql("runners_registered")}
        """.trimIndent(),
    ),
    SchemaMigration(
        version = 3,
        name = "runners_session_routes_under_row_level_security",
        sql = ROUTES_UNDER_ROW_LEVEL_SECURITY_SQL,
    ),
    SchemaMigration(version = 4, name = "registered_runner_key", sql = REGISTERED_RUNNER_ORG_SQL),
    // RLS audit R9: the router's policies hold only inside its own functions, as identity's
    // do; the owner, a member of `ostrom_router` to hand them over, no longer passes them.
    SchemaMigration(
        version = 5,
        name = "runners_router_policies_inside_functions_only",
        sql = """
            ALTER POLICY runners_session_routes_router ON runners_session_routes USING (current_user = 'ostrom_router')
                WITH CHECK (current_user = 'ostrom_router');
            ALTER POLICY runners_registered_router ON runners_registered USING (current_user = 'ostrom_router')
        """.trimIndent(),
    ),
    // The executions handed to registered runners (RegisteredRuntimeDriver): a tenant table, never read across
    // organizations. A runner is not a foreign key: a removed runner leaves its executions lost, then relocated.
    SchemaMigration(
        version = 6,
        name = "create_runner_assignments",
        sql = """
            CREATE TABLE runners_assignments (
                session_id UUID NOT NULL REFERENCES runners_sessions (id) ON DELETE CASCADE,
                epoch BIGINT NOT NULL,
                org_id UUID NOT NULL,
                runner_id UUID,
                created_at TIMESTAMPTZ NOT NULL,
                delivered_at TIMESTAMPTZ,
                stopped_at TIMESTAMPTZ,
                dropped_at TIMESTAMPTZ,
                state TEXT,
                holds_workspace BOOLEAN NOT NULL DEFAULT FALSE,
                PRIMARY KEY (session_id, epoch)
            );
            CREATE INDEX runners_assignments_runner ON runners_assignments (org_id, runner_id);
            CREATE INDEX runners_assignments_waiting ON runners_assignments (org_id, created_at)
                WHERE runner_id IS NULL AND stopped_at IS NULL;
            ${tenantTableSql("runners_assignments")}
        """.trimIndent(),
    ),
    // The sessions a registered runner runs at once at most, from its last heartbeat (null: no limit declared). An
    // execution is never handed to a runner that already holds as many (RunnerAssignmentsRepositoryImpl).
    SchemaMigration(
        version = 7,
        name = "registered_runner_capacity",
        sql = "ALTER TABLE runners_registered ADD COLUMN capacity INTEGER",
    ),
    // What a waiting heartbeat's look reads, once a second per organization (InProcessRunnerWorkSignals): the
    // executions not handed over yet, a handful at any time, whatever the history.
    SchemaMigration(
        version = 8,
        name = "runner_assignments_undelivered",
        sql = "CREATE INDEX runners_assignments_undelivered ON runners_assignments (org_id) " +
            "WHERE delivered_at IS NULL AND stopped_at IS NULL",
    ),
    // TC-REP-2: the terminal tickets, in the database instead of a replica's memory, so that one replica issues a
    // ticket another redeems (TerminalTicketsImpl). Their hash only, a minute's life by the database's clock.
    SchemaMigration(
        version = 9,
        name = "terminal_tickets",
        sql = """
            CREATE TABLE runners_terminal_tickets (
                token_hash TEXT PRIMARY KEY,
                org_id UUID NOT NULL,
                session_id UUID NOT NULL,
                member_id TEXT NOT NULL,
                admin BOOLEAN NOT NULL,
                expires_at TIMESTAMPTZ NOT NULL
            );
            CREATE INDEX runners_terminal_tickets_expiry ON runners_terminal_tickets (org_id, expires_at);
            ${tenantTableSql("runners_terminal_tickets")}
        """.trimIndent(),
    ),
)
