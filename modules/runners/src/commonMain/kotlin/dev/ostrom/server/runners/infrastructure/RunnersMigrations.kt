// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.infrastructure

import dev.ostrom.server.kernel.database.ROUTER_ROLE
import dev.ostrom.server.kernel.database.ROUTER_ROLE_SQL
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.database.routedTableSql
import dev.ostrom.server.kernel.database.routerFunctionsSql

/** Hands the cross-organization functions of version 2 over to [ROUTER_ROLE]. */
private val ROUTE_FUNCTIONS_OWNER = routerFunctionsSql(
    "runners_route_by_session(UUID)",
    "runners_route_by_token(TEXT)",
    "runners_claim_due(TEXT, TIMESTAMPTZ, TIMESTAMPTZ, INTEGER)",
    "runners_release(UUID, TEXT, BOOLEAN, TIMESTAMPTZ)",
)

/**
 * The schema of the runners module; its tables start with `runners_`.
 *
 * Every table is confined by row-level security to the organization of the transaction (S2-01, S2-51).
 * `runners_session_routes` (a session's id and organization, the hash of its runner token and its reconcile schedule)
 * is also reached across organizations, only through the narrow `SECURITY DEFINER` functions of version 2, owned by
 * [ROUTER_ROLE]: the reconciler claims and releases due sessions, the runner channel finds a session by its token.
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
        name = "confine_session_routes",
        sql = """
            $ROUTER_ROLE_SQL
            ${routedTableSql("runners_session_routes", "SELECT, UPDATE")}
            CREATE FUNCTION runners_route_by_session(p_session_id UUID) RETURNS TABLE (session_id UUID, org_id UUID)
                LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public
                AS $$ SELECT r.session_id, r.org_id FROM runners_session_routes r WHERE r.session_id = p_session_id $$;
            CREATE FUNCTION runners_route_by_token(p_hash TEXT) RETURNS TABLE (session_id UUID, org_id UUID)
                LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public
                AS $$ SELECT r.session_id, r.org_id FROM runners_session_routes r WHERE r.runner_token_hash = p_hash $$;
            CREATE FUNCTION runners_claim_due(p_owner TEXT, p_until TIMESTAMPTZ, p_now TIMESTAMPTZ, p_limit INTEGER)
                RETURNS TABLE (session_id UUID, org_id UUID)
                LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public
                AS $$
                UPDATE runners_session_routes r SET reconcile_owner = p_owner, reconcile_until = p_until,
                    claimed_at = p_now
                WHERE r.session_id IN (
                    SELECT d.session_id FROM runners_session_routes d WHERE d.next_reconcile_at <= p_now
                    AND (d.reconcile_until IS NULL OR d.reconcile_until < p_now) ORDER BY d.next_reconcile_at
                    LIMIT p_limit FOR UPDATE SKIP LOCKED
                )
                RETURNING r.session_id, r.org_id
                $$;
            CREATE FUNCTION runners_release(p_session_id UUID, p_owner TEXT, p_has_next BOOLEAN, p_next_at TIMESTAMPTZ)
                RETURNS VOID
                LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public
                AS $$
                UPDATE runners_session_routes r SET reconcile_owner = NULL, reconcile_until = NULL,
                    next_reconcile_at = CASE
                        WHEN r.next_reconcile_at > r.claimed_at AND p_has_next
                            THEN LEAST(r.next_reconcile_at, p_next_at)
                        WHEN r.next_reconcile_at > r.claimed_at THEN r.next_reconcile_at
                        WHEN p_has_next THEN p_next_at
                        ELSE NULL
                    END
                WHERE r.session_id = p_session_id AND r.reconcile_owner = p_owner
                $$;
            $ROUTE_FUNCTIONS_OWNER
        """.trimIndent(),
    ),
)
