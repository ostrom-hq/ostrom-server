// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration

/** The reads across organizations of a webhook and of the scheduler, functions owned by `ostrom_router`. */
private val ROUTER_FUNCTIONS = listOf(
    "automations_route_org(UUID)",
    "automations_due_routes(TIMESTAMPTZ, INTEGER)",
    "automations_organizations()",
)

/**
 * Version 3: `automations_routes` under forced row-level security like the other tables (a request writes only its
 * organization's route), and its three cross-organization reads moved into narrow functions owned by `ostrom_router`
 * (`NOLOGIN`, never a member of the serving role), with `pg_temp` last in their `search_path`, executable by the
 * serving role only (RLS audit R2 and R3). `automations_due_routes` locks the routes it returns (`FOR UPDATE SKIP
 * LOCKED`) until the scheduler's transaction ends, as the query it replaces did.
 */
private val ROUTES_UNDER_ROW_LEVEL_SECURITY_SQL: String =
    """
    ALTER TABLE automations_routes ENABLE ROW LEVEL SECURITY;
    ALTER TABLE automations_routes FORCE ROW LEVEL SECURITY;
    CREATE POLICY automations_routes_tenant ON automations_routes
        USING (org_id = CAST(NULLIF(current_setting('$TENANT_SETTING', true), '') AS UUID))
        WITH CHECK (org_id = CAST(NULLIF(current_setting('$TENANT_SETTING', true), '') AS UUID));
    DO $$ BEGIN
        CREATE ROLE ostrom_router NOLOGIN NOSUPERUSER NOBYPASSRLS;
    EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL;
    END $$;
    DO $$ BEGIN
        GRANT ostrom_router TO CURRENT_USER;
    EXCEPTION WHEN unique_violation THEN NULL;
    END $$;
    GRANT USAGE ON SCHEMA public TO ostrom_router;
    CREATE POLICY automations_routes_router ON automations_routes TO ostrom_router USING (true) WITH CHECK (false);
    GRANT SELECT, UPDATE ON automations_routes TO ostrom_router;
    CREATE FUNCTION automations_route_org(p_id UUID) RETURNS UUID
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT r.org_id FROM automations_routes r WHERE r.automation_id = p_id;
    $$;
    CREATE FUNCTION automations_due_routes(p_now TIMESTAMPTZ, p_limit INTEGER)
        RETURNS TABLE (automation_id UUID, org_id UUID, next_run_at TIMESTAMPTZ)
        LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT r.automation_id, r.org_id, r.next_run_at FROM automations_routes r WHERE r.next_run_at <= p_now
        ORDER BY r.next_run_at LIMIT p_limit FOR UPDATE SKIP LOCKED;
    $$;
    CREATE FUNCTION automations_organizations() RETURNS SETOF UUID
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT DISTINCT r.org_id FROM automations_routes r;
    $$;
    ${ROUTER_FUNCTIONS.joinToString("\n") { "REVOKE EXECUTE ON FUNCTION $it FROM PUBLIC;" }}
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
            ${ROUTER_FUNCTIONS.joinToString("\n            ") { "GRANT EXECUTE ON FUNCTION $it TO ostrom_app;" }}
        END IF;
    END $$;
    GRANT CREATE ON SCHEMA public TO ostrom_router;
    ${ROUTER_FUNCTIONS.joinToString("\n") { "ALTER FUNCTION $it OWNER TO ostrom_router;" }}
    REVOKE CREATE ON SCHEMA public FROM ostrom_router;
    """
        .trimIndent()

/** The reads across organizations of the waits that resolve by themselves, functions owned by `ostrom_router`. */
private val WAIT_ROUTER_FUNCTIONS = listOf(
    "automations_due_waits(TIMESTAMPTZ, INTEGER)",
    "automations_wait_event(UUID)",
)

/**
 * Version 6: the waits that resolve by themselves (`resolver` `time` or `event`, the agent's `wait_for`). Their instant
 * is `due_at` for a time, `timeout_at` for an event; the clock's scan reads the open ones of every organization by
 * that instant (partial index `automations_waits_due`), and an event address finds its wait by id alone: both only
 * through narrow functions owned by `ostrom_router` (docs/tenancy.md), which return ids, organizations and a secret's
 * hash, never a wait's content. Each wait is then resolved in its own organization. The secret of an event address is
 * kept as its SHA-256 only (`event_secret_hash`).
 */
private val WAITS_THAT_RESOLVE_BY_THEMSELVES_SQL: String =
    """
    ALTER TABLE automations_waits
        ADD COLUMN resolver TEXT NOT NULL DEFAULT 'human',
        ADD COLUMN due_at TIMESTAMPTZ,
        ADD COLUMN timeout_at TIMESTAMPTZ,
        ADD COLUMN event_source TEXT,
        ADD COLUMN resume_message TEXT,
        ADD COLUMN event_secret_hash TEXT;
    CREATE INDEX automations_waits_due ON automations_waits ((COALESCE(due_at, timeout_at)))
        WHERE status = 'open' AND resolver <> 'human';
    CREATE POLICY automations_waits_router ON automations_waits FOR SELECT TO ostrom_router
        USING (current_user = 'ostrom_router');
    GRANT SELECT ON automations_waits TO ostrom_router;
    CREATE FUNCTION automations_due_waits(p_now TIMESTAMPTZ, p_limit INTEGER)
        RETURNS TABLE (id UUID, org_id UUID, resolver TEXT)
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT w.id, w.org_id, w.resolver FROM automations_waits w
        WHERE w.status = 'open' AND w.resolver <> 'human' AND COALESCE(w.due_at, w.timeout_at) <= p_now
        ORDER BY COALESCE(w.due_at, w.timeout_at) LIMIT p_limit;
    $$;
    CREATE FUNCTION automations_wait_event(p_id UUID) RETURNS TABLE (org_id UUID, secret_hash TEXT)
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT w.org_id, w.event_secret_hash FROM automations_waits w
        WHERE w.id = p_id AND w.status = 'open' AND w.resolver = 'event' AND w.event_secret_hash IS NOT NULL;
    $$;
    ${WAIT_ROUTER_FUNCTIONS.joinToString("\n") { "REVOKE EXECUTE ON FUNCTION $it FROM PUBLIC;" }}
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
            ${WAIT_ROUTER_FUNCTIONS.joinToString("\n            ") { "GRANT EXECUTE ON FUNCTION $it TO ostrom_app;" }}
        END IF;
    END $$;
    GRANT CREATE ON SCHEMA public TO ostrom_router;
    ${WAIT_ROUTER_FUNCTIONS.joinToString("\n") { "ALTER FUNCTION $it OWNER TO ostrom_router;" }}
    REVOKE CREATE ON SCHEMA public FROM ostrom_router;
    """
        .trimIndent()

/**
 * The schema of the automations module; its tables start with `automations_`, and each is confined by row-level
 * security to the organization of the transaction (S2-01). `automations_routes` (an automation's id and organization
 * and its next cron slot, no customer content) is also read across organizations, by a webhook (an automation found
 * by its id) and by the scheduler (the due crons of every organization): only through [ROUTER_FUNCTIONS] (version 3,
 * docs/tenancy.md). `automations_waits` is read so too, by the clock's scan and an event address, only through
 * [WAIT_ROUTER_FUNCTIONS] (version 6).
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
                UNIQUE (org_id, owner, name),
                UNIQUE (id, org_id)
            );
            CREATE INDEX automations_automations_owner
                ON automations_automations (org_id, owner, created_at DESC, id DESC);
            CREATE TABLE automations_runs (
                id UUID PRIMARY KEY,
                automation_id UUID NOT NULL,
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
                UNIQUE (automation_id, delivery_key),
                -- A run belongs to an automation of its own organization.
                FOREIGN KEY (automation_id, org_id) REFERENCES automations_automations (id, org_id) ON DELETE CASCADE
            );
            CREATE INDEX automations_runs_history ON automations_runs (automation_id, created_at DESC, id DESC);
            CREATE INDEX automations_runs_created ON automations_runs (org_id, created_at);
            CREATE INDEX automations_runs_queued ON automations_runs (org_id, created_at) WHERE status = 'queued';
            CREATE TABLE automations_routes (
                automation_id UUID PRIMARY KEY REFERENCES automations_automations (id) ON DELETE CASCADE,
                org_id UUID NOT NULL,
                next_run_at TIMESTAMPTZ
            );
            CREATE INDEX automations_routes_due ON automations_routes (next_run_at) WHERE next_run_at IS NOT NULL;
            CREATE INDEX automations_routes_org ON automations_routes (org_id);
            ${tenantTableSql("automations_automations")}
            ${tenantTableSql("automations_runs")}
            GRANT SELECT, INSERT, UPDATE ON automations_routes TO $TENANT_ROLE;
        """,
    ),
    SchemaMigration(
        version = 3,
        name = "automations_routes_under_row_level_security",
        sql = ROUTES_UNDER_ROW_LEVEL_SECURITY_SQL,
    ),
    // RLS audit R9: the router's policies hold only inside its own functions, as identity's
    // do; the owner, a member of `ostrom_router` to hand them over, no longer passes them.
    SchemaMigration(
        version = 4,
        name = "automations_router_policies_inside_functions_only",
        sql = """
            ALTER POLICY automations_routes_router ON automations_routes USING (current_user = 'ostrom_router')
        """.trimIndent(),
    ),
    // An answered wait of a session waits to be handed to its agent once (the Stop hook of its launch, through
    // `POST /api/v1/waits/wake`): waits answered before this version are never handed over.
    SchemaMigration(
        version = 5,
        name = "waits_wake_pending",
        sql = """
            ALTER TABLE automations_waits ADD COLUMN wake_pending BOOLEAN NOT NULL DEFAULT FALSE;
            CREATE INDEX automations_waits_wake ON automations_waits (org_id, session_id) WHERE wake_pending;
        """.trimIndent(),
    ),
    SchemaMigration(
        version = 6,
        name = "waits_that_resolve_by_themselves",
        sql = WAITS_THAT_RESOLVE_BY_THEMSELVES_SQL,
    ),
    // A run over its owner's cap waits queued with its rendered prompt, dropped by any end of the run; the partial
    // index finds the started runs of the last day, which the cap counts.
    SchemaMigration(
        version = 7,
        name = "runs_waiting_for_capacity",
        sql = """
            ALTER TABLE automations_runs ADD COLUMN prompt TEXT;
            CREATE INDEX automations_runs_started ON automations_runs (org_id, created_at DESC)
                WHERE status = 'started';
        """.trimIndent(),
    ),
    // A workflow action (TC-AUT-5): the workflow its automation starts, a column so that deleting a workflow finds the
    // automation that starts it in one indexed lookup (`workflow_in_use`); the workflow run a run started, and the
    // started ones the scheduler follows until their workflow run ends.
    SchemaMigration(
        version = 8,
        name = "workflow_actions",
        sql = """
            ALTER TABLE automations_automations ADD COLUMN workflow_id TEXT;
            CREATE INDEX automations_automations_workflow ON automations_automations (org_id, workflow_id)
                WHERE workflow_id IS NOT NULL;
            ALTER TABLE automations_runs ADD COLUMN workflow_run_id TEXT;
            CREATE INDEX automations_runs_followed ON automations_runs (org_id, created_at)
                WHERE status = 'started' AND workflow_run_id IS NOT NULL;
        """.trimIndent(),
    ),
)
