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
)
