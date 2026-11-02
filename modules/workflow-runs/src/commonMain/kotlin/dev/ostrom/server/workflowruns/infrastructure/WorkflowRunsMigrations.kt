// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

private fun policy(table: String) = """
    ALTER TABLE $table ENABLE ROW LEVEL SECURITY;
    ALTER TABLE $table FORCE ROW LEVEL SECURITY;
    CREATE POLICY ${table}_org ON $table
        USING (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID))
        WITH CHECK (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID));
""".trimIndent()

/**
 * The workflow-runs schema: each row keeps the contract's JSON as it was answered (`body`), with the columns its reads
 * filter on. Rows are isolated by organization in the database itself: row level security is forced (the owner
 * included) and every query runs in a transaction that first sets `ostrom.org_id` (`withOrganization`).
 */
val WorkflowRunsMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        1,
        "create_checks_and_notifications",
        """
        CREATE TABLE workflow_runs_checks (
            org_id UUID NOT NULL,
            id TEXT NOT NULL,
            at TIMESTAMPTZ NOT NULL,
            session_id TEXT,
            node_id TEXT NOT NULL,
            body JSONB NOT NULL,
            PRIMARY KEY (org_id, id)
        );
        CREATE INDEX workflow_runs_checks_at ON workflow_runs_checks (org_id, at DESC, id DESC);
        CREATE INDEX workflow_runs_checks_session ON workflow_runs_checks (org_id, session_id, at DESC, id DESC);
        CREATE INDEX workflow_runs_checks_node ON workflow_runs_checks (org_id, node_id, at DESC, id DESC);
        ${policy("workflow_runs_checks")}
        CREATE TABLE workflow_runs_notifications (
            org_id UUID NOT NULL,
            id TEXT NOT NULL,
            at TIMESTAMPTZ NOT NULL,
            session_id TEXT,
            notify_id TEXT NOT NULL,
            status TEXT NOT NULL,
            action_key TEXT NOT NULL,
            body JSONB NOT NULL,
            PRIMARY KEY (org_id, id)
        );
        CREATE INDEX workflow_runs_notifications_at ON workflow_runs_notifications (org_id, at DESC, id DESC);
        CREATE INDEX workflow_runs_notifications_status
            ON workflow_runs_notifications (org_id, status, at DESC, id DESC);
        CREATE INDEX workflow_runs_notifications_session
            ON workflow_runs_notifications (org_id, session_id, at DESC, id DESC);
        -- One notification per action of a session; one not sent may go again.
        CREATE UNIQUE INDEX workflow_runs_notifications_once
            ON workflow_runs_notifications (org_id, session_id, notify_id, action_key)
            WHERE session_id IS NOT NULL AND status <> 'not-sent';
        ${policy("workflow_runs_notifications")}
        """.trimIndent(),
    ),
    // Where each run comes from (decision 13:23, core#40): the workstation's hooks, unless the server holds a proof.
    SchemaMigration(
        2,
        "check_attestation",
        """
        ALTER TABLE workflow_runs_checks ADD COLUMN attestation TEXT NOT NULL DEFAULT 'workstation_declared'
            CHECK (attestation IN ('workstation_declared', 'isolated_runner', 'ci'))
        """.trimIndent(),
    ),
)
