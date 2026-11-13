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
    // TeamCode #238 (parity TC-NEW-675a530-2): a receipt about a session is read by the session's person, the
    // organization's owners and administrators, and the approvers of the session's gates. Each receipt names the
    // session it is about (its signed `sessionId`, else the subject of a session receipt) and that session's person,
    // its owner (`member`, the session's `dev`). The receipts signed before are filled in by the one UPDATE the
    // append-only trigger ever lets through: the migration disables it, fills the two columns and enables it again
    // `ALWAYS`, in its own transaction, whose ACCESS EXCLUSIVE lock (taken by the first ALTER TABLE) keeps any other
    // statement off the table until it commits; the signed bodies are not touched. The owner reads every
    // organization's rows for that one statement, with the policies no longer forced on it (as gates migration 3). A
    // database where the sessions module has not run yet has no session to read: the owners stay unknown there. A
    // member's page reads the receipts about no session by `receipts_by_session` (`session_id IS NULL`), and those of
    // one kind by the partial `receipts_without_session`, never through the organization's other receipts.
    SchemaMigration(
        3,
        "receipts_session_and_member",
        """
        ALTER TABLE receipts_receipts ADD COLUMN session_id TEXT;
        ALTER TABLE receipts_receipts ADD COLUMN member TEXT;
        ALTER TABLE receipts_receipts DISABLE TRIGGER receipts_receipts_append_only;
        ALTER TABLE receipts_receipts NO FORCE ROW LEVEL SECURITY;
        UPDATE receipts_receipts SET session_id = COALESCE(
            NULLIF(CAST(body AS JSONB) ->> 'sessionId', ''),
            CASE WHEN subject_kind = 'session' THEN subject_id END
        );
        DO $$
        BEGIN
            IF to_regclass('sessions_sessions') IS NOT NULL THEN
                ALTER TABLE sessions_sessions NO FORCE ROW LEVEL SECURITY;
                UPDATE receipts_receipts r SET member = s.dev
                    FROM sessions_sessions s
                    WHERE s.org_id = r.org_id AND s.id = r.session_id;
                ALTER TABLE sessions_sessions FORCE ROW LEVEL SECURITY;
            END IF;
        END
        $$;
        ALTER TABLE receipts_receipts FORCE ROW LEVEL SECURITY;
        ALTER TABLE receipts_receipts ENABLE ALWAYS TRIGGER receipts_receipts_append_only;
        CREATE INDEX receipts_by_session ON receipts_receipts (org_id, session_id, created_at DESC, id DESC);
        CREATE INDEX receipts_by_member ON receipts_receipts (org_id, member, created_at DESC, id DESC);
        CREATE INDEX receipts_without_session ON receipts_receipts (org_id, subject_kind, created_at DESC, id DESC)
            WHERE session_id IS NULL
        """.trimIndent(),
    ),
)
