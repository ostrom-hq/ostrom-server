// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

/**
 * The gates schema. Rows are isolated by organization in the database itself: row level security is forced (the
 * owner included) and every query runs in a transaction that first sets `ostrom.org_id`; without it, no row is
 * visible and none can be written.
 */
val GatesMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        1,
        "create_gates",
        """
        CREATE TABLE gates_gates (
            id UUID PRIMARY KEY,
            org_id UUID NOT NULL,
            gate_key TEXT NOT NULL,
            subject TEXT NOT NULL,
            node TEXT,
            label TEXT NOT NULL,
            mode TEXT NOT NULL,
            status TEXT NOT NULL,
            asked_by TEXT NOT NULL,
            asked_via TEXT NOT NULL,
            session_ids TEXT NOT NULL,
            launch_ids TEXT NOT NULL,
            shown_digest TEXT NOT NULL,
            evidence TEXT NOT NULL,
            decided_by TEXT,
            decided_at TIMESTAMPTZ,
            self_approved BOOLEAN NOT NULL DEFAULT FALSE,
            receipt_id TEXT,
            reserved_by TEXT,
            reserved_at TIMESTAMPTZ,
            consumed_at TIMESTAMPTZ,
            created_at TIMESTAMPTZ NOT NULL
        );
        CREATE INDEX gates_gates_by_key ON gates_gates (org_id, gate_key, created_at DESC);
        CREATE TABLE gates_requests (
            id UUID PRIMARY KEY,
            gate_id UUID NOT NULL REFERENCES gates_gates (id) ON DELETE CASCADE,
            org_id UUID NOT NULL,
            position INT NOT NULL,
            approver_id TEXT NOT NULL,
            status TEXT NOT NULL,
            answered_at TIMESTAMPTZ,
            channel TEXT,
            created_at TIMESTAMPTZ NOT NULL
        );
        CREATE INDEX gates_requests_by_gate ON gates_requests (gate_id, position);
        CREATE INDEX gates_requests_inbox ON gates_requests (org_id, lower(approver_id), status, created_at DESC);
        CREATE INDEX gates_requests_recent ON gates_requests (org_id, created_at DESC);
        ALTER TABLE gates_gates ENABLE ROW LEVEL SECURITY;
        ALTER TABLE gates_gates FORCE ROW LEVEL SECURITY;
        CREATE POLICY gates_gates_org ON gates_gates
            USING (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID))
            WITH CHECK (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID));
        ALTER TABLE gates_requests ENABLE ROW LEVEL SECURITY;
        ALTER TABLE gates_requests FORCE ROW LEVEL SECURITY;
        CREATE POLICY gates_requests_org ON gates_requests
            USING (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID))
            WITH CHECK (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID))
        """.trimIndent(),
    ),
)
