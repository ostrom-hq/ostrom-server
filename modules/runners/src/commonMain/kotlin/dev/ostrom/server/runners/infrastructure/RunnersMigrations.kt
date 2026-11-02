// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

/** What a registered runner's heartbeat reads across organizations: a function owned by `ostrom_router`. */
private val REGISTERED_ROUTER_FUNCTIONS = listOf("runners_registered_key(UUID)")

private val REGISTERED_ROUTER_GRANTS =
    REGISTERED_ROUTER_FUNCTIONS.joinToString("\n") { "GRANT EXECUTE ON FUNCTION $it TO ostrom_app;" }

/**
 * Version 3: a registered runner's heartbeat names only the runner, so its organization is read across organizations
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
 * transaction (S2-01). `runners_session_routes` is the one table read across organizations, by the reconciler (due
 * sessions of every organization) and by the runner channel (a session found by its runner token): it holds no
 * customer content, only a session's id and organization, the hash of its runner token and its reconcile schedule.
 * `runners_registered` (version 2) is a tenant table too: the machines an organization registered, with the hash of
 * their key.
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
    // The migrator refuses a gap between versions: server#104 (runners_session_routes under row-level security), open
    // when this one was written, takes the next number after it.
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
    SchemaMigration(version = 3, name = "registered_runner_key", sql = REGISTERED_RUNNER_ORG_SQL),
)
