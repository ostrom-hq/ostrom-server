// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

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
)
