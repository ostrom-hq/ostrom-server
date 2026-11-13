// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration

private fun policy(table: String) = """
    ALTER TABLE $table ENABLE ROW LEVEL SECURITY;
    ALTER TABLE $table FORCE ROW LEVEL SECURITY;
    CREATE POLICY ${table}_org ON $table
        USING (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID))
        WITH CHECK (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID));
""".trimIndent()

private const val CLAIM_FUNCTION = "workflow_runs_claim_due(TEXT, TIMESTAMPTZ, TIMESTAMPTZ, INTEGER)"

/**
 * Version 4: the server plays the runs. Each run keeps the graph it copied at its start and when the engine looks at
 * it next (`next_step_at`, null once nothing is left to play); a replica leases it (`lease_owner`, `lease_until`).
 * Each visit begun has its key (organization, run, `seq`), inserted once: a visit is never begun twice, whatever
 * restarts. Leasing the runs due crosses organizations, so it is one narrow `SECURITY DEFINER` function owned by
 * `ostrom_router` (`NOLOGIN`, never a member of the serving role), with `pg_temp` last in its `search_path` and
 * executable by the serving role only: it sets the lease of the runs due (`FOR UPDATE SKIP LOCKED`) and returns their
 * organization and id, nothing else. Everything else is read and written in the run's organization's transaction.
 * The runs started before have no graph: queued, they are due at once and fail with nothing to play.
 */
private val ENGINE_SQL: String =
    """
    ALTER TABLE workflow_runs_started ADD COLUMN graph JSONB;
    ALTER TABLE workflow_runs_started ADD COLUMN next_step_at TIMESTAMPTZ;
    ALTER TABLE workflow_runs_started ADD COLUMN lease_owner TEXT;
    ALTER TABLE workflow_runs_started ADD COLUMN lease_until TIMESTAMPTZ;
    UPDATE workflow_runs_started SET next_step_at = started_at WHERE status IN ('queued', 'running', 'waiting');
    CREATE INDEX workflow_runs_started_due ON workflow_runs_started (next_step_at) WHERE next_step_at IS NOT NULL;
    CREATE TABLE workflow_runs_visits (
        org_id UUID NOT NULL,
        run_id TEXT NOT NULL,
        seq INTEGER NOT NULL,
        node_id TEXT NOT NULL,
        at TIMESTAMPTZ NOT NULL,
        PRIMARY KEY (org_id, run_id, seq)
    );
    ${policy("workflow_runs_visits")}
    DO $$ BEGIN
        CREATE ROLE ostrom_router NOLOGIN NOSUPERUSER NOBYPASSRLS;
    EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL;
    END $$;
    DO $$ BEGIN
        GRANT ostrom_router TO CURRENT_USER;
    EXCEPTION WHEN unique_violation THEN NULL;
    END $$;
    GRANT USAGE ON SCHEMA public TO ostrom_router;
    CREATE POLICY workflow_runs_started_router_read ON workflow_runs_started FOR SELECT TO ostrom_router
        USING (current_user = 'ostrom_router');
    CREATE POLICY workflow_runs_started_router_lease ON workflow_runs_started FOR UPDATE TO ostrom_router
        USING (current_user = 'ostrom_router');
    GRANT SELECT, UPDATE ON workflow_runs_started TO ostrom_router;
    CREATE FUNCTION workflow_runs_claim_due(p_owner TEXT, p_until TIMESTAMPTZ, p_now TIMESTAMPTZ, p_limit INTEGER)
        RETURNS TABLE (run_id TEXT, org_id UUID)
        LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        UPDATE workflow_runs_started r SET lease_owner = p_owner, lease_until = p_until
        WHERE (r.org_id, r.id) IN (
            SELECT d.org_id, d.id FROM workflow_runs_started d WHERE d.next_step_at <= p_now
            AND (d.lease_until IS NULL OR d.lease_until < p_now) ORDER BY d.next_step_at LIMIT p_limit
            FOR UPDATE SKIP LOCKED
        ) RETURNING r.id, r.org_id;
    $$;
    REVOKE EXECUTE ON FUNCTION $CLAIM_FUNCTION FROM PUBLIC;
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
            GRANT EXECUTE ON FUNCTION $CLAIM_FUNCTION TO ostrom_app;
        END IF;
    END $$;
    GRANT CREATE ON SCHEMA public TO ostrom_router;
    ALTER FUNCTION $CLAIM_FUNCTION OWNER TO ostrom_router;
    REVOKE CREATE ON SCHEMA public FROM ostrom_router;
    """
        .trimIndent()

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
    // The runs of workflows (contract `WorkflowRun`), started as a whole: newest first, by workflow or by status.
    SchemaMigration(
        3,
        "create_started_runs",
        """
        CREATE TABLE workflow_runs_started (
            org_id UUID NOT NULL,
            id TEXT NOT NULL,
            workflow_id TEXT NOT NULL,
            status TEXT NOT NULL,
            started_at TIMESTAMPTZ NOT NULL,
            body JSONB NOT NULL,
            PRIMARY KEY (org_id, id)
        );
        CREATE INDEX workflow_runs_started_at ON workflow_runs_started (org_id, started_at DESC, id DESC);
        CREATE INDEX workflow_runs_started_workflow
            ON workflow_runs_started (org_id, workflow_id, started_at DESC, id DESC);
        CREATE INDEX workflow_runs_started_status
            ON workflow_runs_started (org_id, status, started_at DESC, id DESC);
        ${policy("workflow_runs_started")}
        """.trimIndent(),
    ),
    SchemaMigration(4, "play_runs", ENGINE_SQL),
    // A child run, started by a workflow node, names its parent run and the parent's visit: one child per visit, so a
    // visit played again after a restart finds the child its first play started instead of starting a second.
    SchemaMigration(
        5,
        "child_runs",
        """
        ALTER TABLE workflow_runs_started ADD COLUMN parent_run_id TEXT;
        ALTER TABLE workflow_runs_started ADD COLUMN parent_seq INTEGER;
        CREATE UNIQUE INDEX workflow_runs_started_child ON workflow_runs_started (org_id, parent_run_id, parent_seq);
        """.trimIndent(),
    ),
    // TeamCode #238 (parity TC-NEW-675a530-2): a member lists their own runs and notifications and those of the
    // sessions whose gates they approve. The person of each (their session's, or the reporter's outside a session) is
    // read from the stored body into a column of its own: under row level security, `->>` is no index condition.
    SchemaMigration(
        6,
        "checks_and_notifications_by_dev",
        """
        ALTER TABLE workflow_runs_checks ADD COLUMN dev TEXT GENERATED ALWAYS AS (body ->> 'dev') STORED;
        CREATE INDEX workflow_runs_checks_dev ON workflow_runs_checks (org_id, dev, at DESC, id DESC);
        ALTER TABLE workflow_runs_notifications ADD COLUMN dev TEXT GENERATED ALWAYS AS (body ->> 'dev') STORED;
        CREATE INDEX workflow_runs_notifications_dev ON workflow_runs_notifications (org_id, dev, at DESC, id DESC)
        """.trimIndent(),
    ),
)
