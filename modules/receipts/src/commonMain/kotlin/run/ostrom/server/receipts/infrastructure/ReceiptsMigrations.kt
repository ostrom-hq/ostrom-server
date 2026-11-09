// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.receipts.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration

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
    // AUDIT-SECU-GATES-2 G3: the serving role holds UPDATE and DELETE on every table (default privileges) and sets
    // `ostrom.org_id` itself, so it could rewrite a signed receipt or erase a refusal without a trace. A receipt is
    // written once (`ON CONFLICT DO NOTHING`) and never changed: one statement-level trigger refuses UPDATE, DELETE
    // and TRUNCATE for every role, whatever rows the statement reaches, `ENABLE ALWAYS` (replica mode too), as on the
    // audit log (identity migration 9).
    SchemaMigration(
        2,
        "receipts_append_only",
        """
        CREATE FUNCTION receipts_receipts_append_only() RETURNS trigger LANGUAGE plpgsql
            SET search_path = pg_catalog AS $$
        BEGIN
            RAISE EXCEPTION 'receipts_receipts is append-only: % refused', TG_OP
                USING ERRCODE = 'insufficient_privilege';
        END
        $$;
        CREATE TRIGGER receipts_receipts_append_only BEFORE UPDATE OR DELETE OR TRUNCATE
            ON receipts_receipts FOR EACH STATEMENT EXECUTE FUNCTION receipts_receipts_append_only();
        ALTER TABLE receipts_receipts ENABLE ALWAYS TRIGGER receipts_receipts_append_only
        """.trimIndent(),
    ),
)
