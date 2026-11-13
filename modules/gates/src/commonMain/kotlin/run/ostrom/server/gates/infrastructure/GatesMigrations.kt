// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration

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
    SchemaMigration(
        2,
        "gates_by_session",
        "CREATE INDEX gates_gates_by_session ON gates_gates USING GIN ((CAST(session_ids AS JSONB)))",
    ),
    // Under row level security, a qual that is not leakproof (`lower()`, `jsonb @>`) is never an index condition: the
    // inbox and a session's gates read every row of the organization. The inbox compares a stored lowered approver
    // with a leakproof `=`; a session finds its gates through a link table, filled from the existing gates (the owner
    // reads them all for that one statement, with the policy no longer forced on it).
    SchemaMigration(
        3,
        "gates_reads_under_row_level_security",
        """
        ALTER TABLE gates_requests ADD COLUMN approver_key TEXT GENERATED ALWAYS AS (lower(approver_id)) STORED;
        DROP INDEX gates_requests_inbox;
        CREATE INDEX gates_requests_inbox ON gates_requests (org_id, approver_key, status, created_at DESC, id DESC);
        CREATE TABLE gates_sessions (
            org_id UUID NOT NULL,
            session_id TEXT NOT NULL,
            gate_id UUID NOT NULL REFERENCES gates_gates (id) ON DELETE CASCADE,
            PRIMARY KEY (org_id, session_id, gate_id)
        );
        ALTER TABLE gates_gates NO FORCE ROW LEVEL SECURITY;
        INSERT INTO gates_sessions (org_id, session_id, gate_id)
            SELECT g.org_id, s.session_id, g.id
            FROM gates_gates g, jsonb_array_elements_text(CAST(g.session_ids AS JSONB)) AS s (session_id);
        ALTER TABLE gates_gates FORCE ROW LEVEL SECURITY;
        DROP INDEX gates_gates_by_session;
        ALTER TABLE gates_sessions ENABLE ROW LEVEL SECURITY;
        ALTER TABLE gates_sessions FORCE ROW LEVEL SECURITY;
        CREATE POLICY gates_sessions_org ON gates_sessions
            USING (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID))
            WITH CHECK (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID))
        """.trimIndent(),
    ),
    // The count of the `ostrom.gates.pending` gauge, across organizations (docs/tenancy.md): a function of
    // `ostrom_router`, which sees the status column alone, through a read-only policy of its own.
    SchemaMigration(
        4,
        "gates_pending_count",
        """
        DO $$ BEGIN
            CREATE ROLE ostrom_router NOLOGIN NOSUPERUSER NOBYPASSRLS;
        EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL;
        END $$;
        DO $$ BEGIN
            GRANT ostrom_router TO CURRENT_USER;
        EXCEPTION WHEN unique_violation THEN NULL;
        END $$;
        GRANT USAGE ON SCHEMA public TO ostrom_router;
        CREATE POLICY gates_gates_router ON gates_gates FOR SELECT TO ostrom_router USING (true);
        GRANT SELECT (status) ON gates_gates TO ostrom_router;
        CREATE FUNCTION gates_pending_count() RETURNS BIGINT
            LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
            SELECT count(*) FROM gates_gates WHERE status = 'PENDING';
        $$;
        REVOKE EXECUTE ON FUNCTION gates_pending_count() FROM PUBLIC;
        DO $$ BEGIN
            IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
                GRANT EXECUTE ON FUNCTION gates_pending_count() TO ostrom_app;
            END IF;
        END $$;
        GRANT CREATE ON SCHEMA public TO ostrom_router;
        ALTER FUNCTION gates_pending_count() OWNER TO ostrom_router;
        REVOKE CREATE ON SCHEMA public FROM ostrom_router
        """.trimIndent(),
    ),
    // A yes is released once (AUDIT-GATES-2 M2): no more reserve, launch, release, relaunch until it expires.
    SchemaMigration(5, "gates_released_once", "ALTER TABLE gates_gates ADD COLUMN released_at TIMESTAMPTZ"),
    // RLS audit R9: the router's policies hold only inside its own functions, as identity's
    // do; the owner, a member of `ostrom_router` to hand them over, no longer passes them.
    SchemaMigration(
        version = 6,
        name = "gates_router_policies_inside_functions_only",
        sql = """
            ALTER POLICY gates_gates_router ON gates_gates USING (current_user = 'ostrom_router')
        """.trimIndent(),
    ),
    // Q-GATES-G1 (a): a pull request's author whom no Ostrom person linked, by GitHub account id.
    SchemaMigration(7, "gates_unlinked_author", "ALTER TABLE gates_gates ADD COLUMN unlinked_author BIGINT"),
    // AUDIT-SERVEUR-3 B2: the sessions whose end read their gates for the receipt. A gate asked in one of them is
    // refused; the end and the gates take the same advisory lock of the session to write and read it.
    SchemaMigration(
        8,
        "gates_session_ends",
        """
        CREATE TABLE gates_session_ends (
            org_id UUID NOT NULL,
            session_id TEXT NOT NULL,
            ended_at TIMESTAMPTZ NOT NULL,
            PRIMARY KEY (org_id, session_id)
        );
        ALTER TABLE gates_session_ends ENABLE ROW LEVEL SECURITY;
        ALTER TABLE gates_session_ends FORCE ROW LEVEL SECURITY;
        CREATE POLICY gates_session_ends_org ON gates_session_ends
            USING (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID))
            WITH CHECK (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID))
        """.trimIndent(),
    ),
    // TeamCode #238 (parity TC-NEW-675a530-2): the approvers of a session's gates read its receipts; the sessions of an
    // approver's gates are read from their requests (the inbox index) to each gate's links, by gate.
    SchemaMigration(
        9,
        "gates_sessions_by_gate",
        "CREATE INDEX gates_sessions_by_gate ON gates_sessions (org_id, gate_id)",
    ),
)
