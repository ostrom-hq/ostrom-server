// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

/**
 * The schema of the automations module; its tables start with `automations_`, and each is confined by row-level
 * security to the organization of the transaction (S2-01).
 */
val AutomationsMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        version = 1,
        name = "create_waits",
        sql = """
            CREATE TABLE automations_waits (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                kind TEXT NOT NULL,
                status TEXT NOT NULL,
                session_id TEXT,
                run_id TEXT,
                assignee TEXT NOT NULL,
                created_by TEXT NOT NULL,
                question TEXT NOT NULL,
                answer_action TEXT,
                answer_values TEXT,
                answered_by TEXT,
                answered_at TIMESTAMPTZ,
                created_at TIMESTAMPTZ NOT NULL,
                resolved_at TIMESTAMPTZ,
                idempotency_key TEXT,
                UNIQUE (org_id, created_by, idempotency_key)
            );
            CREATE INDEX automations_waits_assignee
                ON automations_waits (org_id, assignee, status, created_at DESC, id DESC);
            CREATE INDEX automations_waits_creator ON automations_waits (org_id, created_by, created_at DESC, id DESC);
            CREATE INDEX automations_waits_session ON automations_waits (org_id, session_id, created_at DESC);
            $TENANT_ROLE_SQL
            ${tenantTableSql("automations_waits")}
        """,
    ),
    SchemaMigration(
        version = 2,
        name = "create_automations",
        sql = """
            CREATE TABLE automations_automations (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                owner TEXT NOT NULL,
                name TEXT NOT NULL,
                trigger_kind TEXT NOT NULL,
                definition TEXT NOT NULL,
                enabled BOOLEAN NOT NULL,
                version INTEGER NOT NULL,
                secret_generation INTEGER NOT NULL,
                previous_secret_valid_until TIMESTAMPTZ,
                next_run_at TIMESTAMPTZ,
                created_at TIMESTAMPTZ NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL,
                UNIQUE (org_id, owner, name)
            );
            CREATE INDEX automations_automations_owner
                ON automations_automations (org_id, owner, created_at DESC, id DESC);
            CREATE INDEX automations_automations_due
                ON automations_automations (next_run_at) WHERE enabled AND next_run_at IS NOT NULL;
            CREATE TABLE automations_runs (
                id UUID PRIMARY KEY,
                automation_id UUID NOT NULL REFERENCES automations_automations (id) ON DELETE CASCADE,
                org_id UUID NOT NULL,
                source TEXT NOT NULL,
                status TEXT NOT NULL,
                version INTEGER NOT NULL,
                delivery_key TEXT NOT NULL,
                session_id TEXT,
                reason TEXT,
                problems TEXT NOT NULL,
                slot_at TIMESTAMPTZ,
                created_at TIMESTAMPTZ NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL,
                UNIQUE (automation_id, delivery_key)
            );
            CREATE INDEX automations_runs_history ON automations_runs (automation_id, created_at DESC, id DESC);
            CREATE INDEX automations_runs_created ON automations_runs (created_at);
            CREATE INDEX automations_runs_queued ON automations_runs (created_at) WHERE status = 'queued';
        """,
    ),
)
