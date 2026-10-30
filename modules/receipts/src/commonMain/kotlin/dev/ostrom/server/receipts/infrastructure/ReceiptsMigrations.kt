package dev.ostrom.server.receipts.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

/**
 * The receipts schema. A receipt is stored as the exact JSON that was signed. Rows are isolated by organization in the
 * database itself: row level security is forced (the owner included) and every query runs in a transaction that first
 * sets `ostrom.org_id`; without it, no row is visible and none can be written.
 */
val ReceiptsMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        1,
        "create_receipts",
        """
        CREATE TABLE receipts_receipts (
            org_id UUID NOT NULL,
            id TEXT NOT NULL,
            subject_kind TEXT NOT NULL,
            subject_id TEXT NOT NULL,
            body TEXT NOT NULL,
            created_at TIMESTAMPTZ NOT NULL,
            PRIMARY KEY (org_id, id)
        );
        CREATE INDEX receipts_by_subject ON receipts_receipts (org_id, subject_kind, subject_id, created_at DESC);
        CREATE INDEX receipts_recent ON receipts_receipts (org_id, created_at DESC, id DESC);
        ALTER TABLE receipts_receipts ENABLE ROW LEVEL SECURITY;
        ALTER TABLE receipts_receipts FORCE ROW LEVEL SECURITY;
        CREATE POLICY receipts_receipts_org ON receipts_receipts
            USING (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID))
            WITH CHECK (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID))
        """.trimIndent(),
    ),
)
