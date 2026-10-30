// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

/** The schema of the runners module; its tables start with `runners_`. */
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
                runner_token_hash TEXT,
                next_reconcile_at TIMESTAMPTZ,
                reconcile_owner TEXT,
                reconcile_until TIMESTAMPTZ,
                claimed_at TIMESTAMPTZ
            );
            CREATE INDEX runners_sessions_owner ON runners_sessions (org_id, owner_id, created_at DESC, id DESC);
            CREATE INDEX runners_sessions_holding ON runners_sessions (org_id, state);
            CREATE INDEX runners_sessions_due ON runners_sessions (next_reconcile_at)
                WHERE next_reconcile_at IS NOT NULL;
            CREATE UNIQUE INDEX runners_sessions_runner_token ON runners_sessions (runner_token_hash)
                WHERE runner_token_hash IS NOT NULL;
            CREATE TABLE runners_usage (
                org_id UUID NOT NULL,
                month TEXT NOT NULL,
                seconds BIGINT NOT NULL,
                PRIMARY KEY (org_id, month)
            );
        """.trimIndent(),
    ),
)
