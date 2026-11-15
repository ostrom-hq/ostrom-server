// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration

/** What the reconciler and the runner channel do across organizations: functions owned by `ostrom_router`. */
private val ROUTER_FUNCTIONS = listOf(
    "runners_session_org(UUID)",
    "runners_session_by_token(TEXT)",
    "runners_claim_due(TEXT, TIMESTAMPTZ, TIMESTAMPTZ, INTEGER)",
    "runners_release(UUID, TEXT, BOOLEAN, TIMESTAMPTZ)",
)

/** The purge of the expired idempotency records, across organizations (version 19). */
private const val CREATE_KEYS_PURGE_FUNCTION = "runners_purge_create_keys()"

/**
 * Version 19 (`runners.cloud.idempotency.v1`, TeamCode's `cloud_idempotency`): the `Idempotency-Key` of a session's
 * creation, per organization and initiating member, with the SHA-256 of its canonical request and the session it
 * created, kept 24 hours. A tenant table; the purge of the expired records crosses organizations through one
 * `SECURITY DEFINER` function owned by `ostrom_router`, which takes no argument and deletes at most 500 expired
 * records a call (TeamCode's `maintain`).
 */
private val CREATE_KEYS_SQL: String =
    """
    CREATE TABLE runners_create_keys (
        org_id UUID NOT NULL,
        member_id TEXT NOT NULL,
        idempotency_key TEXT NOT NULL,
        request_digest CHAR(64) NOT NULL,
        session_id UUID NOT NULL,
        created_at TIMESTAMPTZ NOT NULL,
        expires_at TIMESTAMPTZ NOT NULL,
        PRIMARY KEY (org_id, member_id, idempotency_key)
    );
    CREATE INDEX runners_create_keys_expiry ON runners_create_keys (expires_at);
    ${tenantTableSql("runners_create_keys")}
    CREATE POLICY runners_create_keys_router_read ON runners_create_keys FOR SELECT TO ostrom_router
        USING (current_user = 'ostrom_router');
    CREATE POLICY runners_create_keys_router_purge ON runners_create_keys FOR DELETE TO ostrom_router
        USING (current_user = 'ostrom_router');
    GRANT SELECT, DELETE ON runners_create_keys TO ostrom_router;
    CREATE FUNCTION runners_purge_create_keys() RETURNS BIGINT
        LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        WITH gone AS (
            DELETE FROM runners_create_keys WHERE ctid IN (
                SELECT ctid FROM runners_create_keys WHERE expires_at <= now() LIMIT 500
            ) RETURNING 1
        ) SELECT count(*) FROM gone;
    $$;
    REVOKE EXECUTE ON FUNCTION $CREATE_KEYS_PURGE_FUNCTION FROM PUBLIC;
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
            GRANT EXECUTE ON FUNCTION $CREATE_KEYS_PURGE_FUNCTION TO ostrom_app;
        END IF;
    END $$;
    GRANT CREATE ON SCHEMA public TO ostrom_router;
    ALTER FUNCTION $CREATE_KEYS_PURGE_FUNCTION OWNER TO ostrom_router;
    REVOKE CREATE ON SCHEMA public FROM ostrom_router;
    """.trimIndent()

/**
 * Version 3: `runners_session_routes` under forced row-level security like the other tables (a request reads and
 * writes only its organization's routes), and what crosses organizations moved into narrow `SECURITY DEFINER`
 * functions owned by `ostrom_router` (`NOLOGIN`, never a member of the serving role), with `pg_temp` last in their
 * `search_path`, executable by the serving role only (RLS audit R2 and R3). The claim and the release are the
 * statements they replace, unchanged: one replica leases a due session (`FOR UPDATE SKIP LOCKED`), and a request for a
 * new look made during the pass is kept.
 */
private val ROUTES_UNDER_ROW_LEVEL_SECURITY_SQL: String =
    """
    ALTER TABLE runners_session_routes ENABLE ROW LEVEL SECURITY;
    ALTER TABLE runners_session_routes FORCE ROW LEVEL SECURITY;
    CREATE POLICY runners_session_routes_tenant ON runners_session_routes
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
    CREATE POLICY runners_session_routes_router ON runners_session_routes TO ostrom_router USING (true)
        WITH CHECK (true);
    GRANT SELECT, UPDATE ON runners_session_routes TO ostrom_router;
    CREATE FUNCTION runners_session_org(p_id UUID) RETURNS UUID
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT r.org_id FROM runners_session_routes r WHERE r.session_id = p_id;
    $$;
    CREATE FUNCTION runners_session_by_token(p_hash TEXT) RETURNS TABLE (session_id UUID, org_id UUID)
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT r.session_id, r.org_id FROM runners_session_routes r WHERE r.runner_token_hash = p_hash;
    $$;
    CREATE FUNCTION runners_claim_due(p_owner TEXT, p_until TIMESTAMPTZ, p_now TIMESTAMPTZ, p_limit INTEGER)
        RETURNS TABLE (session_id UUID, org_id UUID)
        LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        UPDATE runners_session_routes r SET reconcile_owner = p_owner, reconcile_until = p_until, claimed_at = p_now
        WHERE r.session_id IN (
            SELECT d.session_id FROM runners_session_routes d WHERE d.next_reconcile_at <= p_now
            AND (d.reconcile_until IS NULL OR d.reconcile_until < p_now) ORDER BY d.next_reconcile_at LIMIT p_limit
            FOR UPDATE SKIP LOCKED
        ) RETURNING r.session_id, r.org_id;
    $$;
    CREATE FUNCTION runners_release(p_id UUID, p_owner TEXT, p_has_next BOOLEAN, p_next_at TIMESTAMPTZ) RETURNS VOID
        LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        UPDATE runners_session_routes SET reconcile_owner = NULL, reconcile_until = NULL, next_reconcile_at = CASE
            WHEN next_reconcile_at > claimed_at AND p_has_next THEN LEAST(next_reconcile_at, p_next_at)
            WHEN next_reconcile_at > claimed_at THEN next_reconcile_at
            WHEN p_has_next THEN p_next_at ELSE NULL END
        WHERE session_id = p_id AND reconcile_owner = p_owner;
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

/** What the garbage collection reads across organizations: owned by `ostrom_router`. */
private const val LIVE_OBJECTS_FUNCTION = "runners_cluster_live_objects(TEXT)"

/**
 * Version 18 (`runners.cloud.retention.v1`, TeamCode's workspace deletion fields): the proof that a Kubernetes
 * session's workspace is gone, provider volume included; and the sessions of a cluster whose objects the garbage
 * collection keeps (CollectCloudGarbageUseCase), every organization's, through one narrow `SECURITY DEFINER` function
 * owned by `ostrom_router` (RLS audit R9): the sessions not over, and the ones whose workspace deletion is not proven,
 * held alone by a partial index on their cluster.
 */
private val WORKSPACE_DELETION_SQL: String =
    """
    ALTER TABLE runners_sessions ADD COLUMN workspace_deletion_state TEXT,
        ADD COLUMN workspace_claim_deleted_at TIMESTAMPTZ, ADD COLUMN workspace_volume_deleted_at TIMESTAMPTZ,
        ADD COLUMN workspace_volume_name TEXT, ADD COLUMN workspace_volume_handle TEXT,
        ADD COLUMN workspace_problem_code TEXT, ADD COLUMN workspace_problem_message TEXT,
        ADD COLUMN workspace_problem_retryable BOOLEAN NOT NULL DEFAULT FALSE;
    CREATE INDEX runners_sessions_cluster_live ON runners_sessions (((profile::jsonb) ->> 'clusterId'))
        WHERE state NOT IN ('terminated', 'failed') OR workspace_deletion_state IN ('pending', 'blocked');
    GRANT SELECT (epoch, workspace_deletion_state, workspace_claim_deleted_at) ON runners_sessions TO ostrom_router;
    CREATE FUNCTION runners_cluster_live_objects(p_cluster TEXT)
        RETURNS TABLE (session_id UUID, epoch BIGINT, state TEXT, profile TEXT, claim_deleted BOOLEAN)
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT s.id, s.epoch, s.state, s.profile, s.workspace_claim_deleted_at IS NOT NULL FROM runners_sessions s
            WHERE ((s.profile::jsonb) ->> 'clusterId') = p_cluster
            AND (s.state NOT IN ('terminated', 'failed') OR s.workspace_deletion_state IN ('pending', 'blocked'));
    $$;
    REVOKE EXECUTE ON FUNCTION $LIVE_OBJECTS_FUNCTION FROM PUBLIC;
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
            GRANT EXECUTE ON FUNCTION $LIVE_OBJECTS_FUNCTION TO ostrom_app;
        END IF;
    END $$;
    GRANT CREATE ON SCHEMA public TO ostrom_router;
    ALTER FUNCTION $LIVE_OBJECTS_FUNCTION OWNER TO ostrom_router;
    REVOKE CREATE ON SCHEMA public FROM ostrom_router;
    """.trimIndent()

/** What a server's start reads across organizations to give the former sessions a profile: owned by `ostrom_router`. */
private const val WITHOUT_PROFILE_FUNCTION = "runners_sessions_without_profile(UUID, INTEGER)"

/**
 * Version 15: the sessions of every organization created before cloud profiles and still without one (but the
 * terminated ones), by id after a cursor, ids and organizations only (BackfillSessionProfilesUseCase); each is then
 * read and written in its own organization. A partial index holds them alone, so the pass reads nothing once all have
 * their profile. The router reads the four columns it needs of `runners_sessions`, inside its functions only (RLS audit
 * R9).
 */
private val SESSIONS_WITHOUT_PROFILE_SQL: String =
    """
    CREATE INDEX runners_sessions_profile_missing ON runners_sessions (id)
        WHERE profile IS NULL AND state <> 'terminated';
    CREATE POLICY runners_sessions_router ON runners_sessions FOR SELECT TO ostrom_router
        USING (current_user = 'ostrom_router');
    GRANT SELECT (id, org_id, profile, state) ON runners_sessions TO ostrom_router;
    CREATE FUNCTION runners_sessions_without_profile(p_after UUID, p_limit INTEGER)
        RETURNS TABLE (session_id UUID, org_id UUID)
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT s.id, s.org_id FROM runners_sessions s WHERE s.profile IS NULL AND s.state <> 'terminated'
            AND (p_after IS NULL OR s.id > p_after) ORDER BY s.id LIMIT p_limit;
    $$;
    REVOKE EXECUTE ON FUNCTION $WITHOUT_PROFILE_FUNCTION FROM PUBLIC;
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
            GRANT EXECUTE ON FUNCTION $WITHOUT_PROFILE_FUNCTION TO ostrom_app;
        END IF;
    END $$;
    GRANT CREATE ON SCHEMA public TO ostrom_router;
    ALTER FUNCTION $WITHOUT_PROFILE_FUNCTION OWNER TO ostrom_router;
    REVOKE CREATE ON SCHEMA public FROM ostrom_router;
    """
        .trimIndent()

/** What the conversation's maintenance does across organizations: functions owned by `ostrom_router`. */
private val CONVERSATION_ROUTER_FUNCTIONS = listOf(
    "runners_conversation_purge(TIMESTAMPTZ, INTEGER)",
    "runners_conversation_wakes(INTEGER)",
    "runners_conversation_ended(INTEGER)",
)

/**
 * Version 17: conversation sessions (`runners.cloud.conversation.v1`, TeamCode's `V20261020100009*`,
 * `V20261023230000.2` and the feeds' migration). `runners_sessions` gets its mode (`terminal` for every session before
 * it), the agent's effort, the owner's Bypass (`bypass_*`) and the wait for a person (`waiting_*`).
 * `runners_session_events` is the transcript, numbered per session (`seq`), deduplicated per execution
 * (`runner_seq`), with the feed of each event (`''` for the main agent, so its pages are an equality on the index);
 * `runners_session_transcripts` the per-session cursors the retention purge never deletes, so numbering never
 * restarts; `runners_session_inputs` the messages, interrupts, answers and changes with their acknowledgement, unique
 * per session, author and idempotency key; `runners_session_subagents` the subagents as their events arrive. All four
 * are tenant tables; the purge, the wake sweep and the cancellation of an ended session's inputs find their rows
 * across organizations only through [CONVERSATION_ROUTER_FUNCTIONS] (docs/tenancy.md), then work in each organization.
 */
private val CONVERSATION_SQL: String =
    """
    ALTER TABLE runners_sessions ADD COLUMN mode TEXT NOT NULL DEFAULT 'terminal';
    ALTER TABLE runners_sessions ADD COLUMN effort TEXT;
    ALTER TABLE runners_sessions ADD COLUMN bypass_by TEXT;
    ALTER TABLE runners_sessions ADD COLUMN bypass_credential_id TEXT;
    ALTER TABLE runners_sessions ADD COLUMN bypass_at TIMESTAMPTZ;
    ALTER TABLE runners_sessions ADD COLUMN waiting_reason TEXT;
    ALTER TABLE runners_sessions ADD COLUMN waiting_request_id TEXT;
    ALTER TABLE runners_sessions ADD COLUMN waiting_since TIMESTAMPTZ;
    ALTER TABLE runners_sessions ADD COLUMN waiting_event_seq BIGINT;
    ALTER TABLE runners_sessions ADD COLUMN waiting_epoch BIGINT;
    CREATE TABLE runners_session_events (
        session_id UUID NOT NULL REFERENCES runners_sessions (id) ON DELETE CASCADE,
        seq BIGINT NOT NULL,
        org_id UUID NOT NULL,
        epoch BIGINT NOT NULL,
        runner_seq BIGINT,
        type TEXT NOT NULL,
        at TIMESTAMPTZ NOT NULL,
        data TEXT NOT NULL,
        feed TEXT NOT NULL DEFAULT '',
        PRIMARY KEY (session_id, seq)
    );
    CREATE UNIQUE INDEX runners_session_events_runner_seq ON runners_session_events (session_id, epoch, runner_seq);
    CREATE INDEX runners_session_events_feed ON runners_session_events (session_id, feed, seq);
    CREATE INDEX runners_session_events_type ON runners_session_events (session_id, type, seq);
    CREATE INDEX runners_session_events_retention ON runners_session_events (at);
    CREATE TABLE runners_session_transcripts (
        session_id UUID PRIMARY KEY REFERENCES runners_sessions (id) ON DELETE CASCADE,
        org_id UUID NOT NULL,
        last_seq BIGINT NOT NULL,
        runner_epoch BIGINT NOT NULL,
        runner_seq BIGINT NOT NULL
    );
    CREATE TABLE runners_session_inputs (
        id UUID PRIMARY KEY,
        session_id UUID NOT NULL REFERENCES runners_sessions (id) ON DELETE CASCADE,
        org_id UUID NOT NULL,
        kind TEXT NOT NULL,
        status TEXT NOT NULL,
        author_id TEXT NOT NULL,
        credential_id TEXT,
        text TEXT,
        wake BOOLEAN NOT NULL,
        wake_pending BOOLEAN NOT NULL DEFAULT FALSE,
        client_key TEXT,
        request_digest TEXT,
        created_at TIMESTAMPTZ NOT NULL,
        delivered_at TIMESTAMPTZ,
        delivered_epoch BIGINT,
        attempts INTEGER NOT NULL DEFAULT 0,
        consumed_at TIMESTAMPTZ,
        finished_at TIMESTAMPTZ,
        reason TEXT,
        event_seq BIGINT
    );
    CREATE UNIQUE INDEX runners_session_inputs_client_key ON runners_session_inputs (session_id, author_id, client_key);
    CREATE INDEX runners_session_inputs_session ON runners_session_inputs (session_id, status, created_at);
    CREATE INDEX runners_session_inputs_list ON runners_session_inputs (session_id, created_at DESC, id DESC);
    CREATE INDEX runners_session_inputs_pending ON runners_session_inputs (session_id)
        WHERE status IN ('queued', 'delivered');
    CREATE INDEX runners_session_inputs_wake ON runners_session_inputs (created_at) WHERE wake_pending;
    CREATE INDEX runners_session_inputs_retention ON runners_session_inputs (finished_at)
        WHERE finished_at IS NOT NULL;
    CREATE TABLE runners_session_subagents (
        session_id UUID NOT NULL REFERENCES runners_sessions (id) ON DELETE CASCADE,
        id TEXT NOT NULL,
        org_id UUID NOT NULL,
        type TEXT,
        description TEXT NOT NULL,
        state TEXT NOT NULL,
        started_at TIMESTAMPTZ NOT NULL,
        ended_at TIMESTAMPTZ,
        steps INTEGER NOT NULL,
        parent_id TEXT,
        task_id TEXT,
        result_error BOOLEAN NOT NULL,
        PRIMARY KEY (session_id, id)
    );
    CREATE INDEX runners_session_subagents_started ON runners_session_subagents (session_id, started_at);
    ${tenantTableSql("runners_session_events")}
    ${tenantTableSql("runners_session_transcripts")}
    ${tenantTableSql("runners_session_inputs")}
    ${tenantTableSql("runners_session_subagents")}
    CREATE POLICY runners_session_events_router_purge ON runners_session_events FOR DELETE TO ostrom_router
        USING (current_user = 'ostrom_router');
    CREATE POLICY runners_session_events_router_read ON runners_session_events FOR SELECT TO ostrom_router
        USING (current_user = 'ostrom_router');
    GRANT SELECT (session_id, seq, at), DELETE ON runners_session_events TO ostrom_router;
    CREATE POLICY runners_session_inputs_router_purge ON runners_session_inputs FOR DELETE TO ostrom_router
        USING (current_user = 'ostrom_router');
    CREATE POLICY runners_session_inputs_router_read ON runners_session_inputs FOR SELECT TO ostrom_router
        USING (current_user = 'ostrom_router');
    GRANT SELECT (id, session_id, org_id, status, wake_pending, created_at, finished_at), DELETE
        ON runners_session_inputs TO ostrom_router;
    GRANT SELECT (desired_state) ON runners_sessions TO ostrom_router;
    CREATE FUNCTION runners_conversation_purge(p_before TIMESTAMPTZ, p_limit INTEGER) RETURNS BIGINT
        LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        WITH events AS (
            DELETE FROM runners_session_events WHERE (session_id, seq) IN (
                SELECT e.session_id, e.seq FROM runners_session_events e WHERE e.at < p_before LIMIT p_limit
            ) RETURNING 1
        ), inputs AS (
            DELETE FROM runners_session_inputs WHERE id IN (
                SELECT i.id FROM runners_session_inputs i WHERE i.finished_at < p_before LIMIT p_limit
            ) RETURNING 1
        ) SELECT (SELECT COUNT(*) FROM events) + (SELECT COUNT(*) FROM inputs);
    $$;
    CREATE FUNCTION runners_conversation_wakes(p_limit INTEGER)
        RETURNS TABLE (session_id UUID, org_id UUID, input_id UUID)
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT DISTINCT ON (i.session_id) i.session_id, i.org_id, i.id FROM runners_session_inputs i
            WHERE i.wake_pending AND i.status = 'queued' ORDER BY i.session_id, i.created_at LIMIT p_limit;
    $$;
    CREATE FUNCTION runners_conversation_ended(p_limit INTEGER) RETURNS TABLE (session_id UUID, org_id UUID)
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT DISTINCT i.session_id, i.org_id FROM runners_session_inputs i
            JOIN runners_sessions s ON s.id = i.session_id
            WHERE i.status IN ('queued', 'delivered')
            AND (s.state IN ('terminated', 'failed') OR s.desired_state = 'terminated') LIMIT p_limit;
    $$;
    ${CONVERSATION_ROUTER_FUNCTIONS.joinToString("\n") { "REVOKE EXECUTE ON FUNCTION $it FROM PUBLIC;" }}
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
            ${CONVERSATION_ROUTER_FUNCTIONS.joinToString("\n") { "GRANT EXECUTE ON FUNCTION $it TO ostrom_app;" }}
        END IF;
    END $$;
    GRANT CREATE ON SCHEMA public TO ostrom_router;
    ${CONVERSATION_ROUTER_FUNCTIONS.joinToString("\n") { "ALTER FUNCTION $it OWNER TO ostrom_router;" }}
    REVOKE CREATE ON SCHEMA public FROM ostrom_router
    """
        .trimIndent()

/** What a registered runner's heartbeat reads across organizations: a function owned by `ostrom_router`. */
private val REGISTERED_ROUTER_FUNCTIONS = listOf("runners_registered_key(UUID)")

private val REGISTERED_ROUTER_GRANTS =
    REGISTERED_ROUTER_FUNCTIONS.joinToString("\n") { "GRANT EXECUTE ON FUNCTION $it TO ostrom_app;" }

/**
 * Version 4: a registered runner's heartbeat names only the runner, so its organization is read across organizations
 * by one narrow `SECURITY DEFINER` function owned by `ostrom_router` (`NOLOGIN`, never a member of the serving role),
 * with `pg_temp` last in its `search_path`, executable by the serving role only (docs/tenancy.md). It returns the
 * organization and key hash of one runner id and nothing else; the sign of life is then written in that organization's
 * transaction, only while the runner has that key.
 */
private val REGISTERED_RUNNER_ORG_SQL: String =
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
    CREATE POLICY runners_registered_router ON runners_registered TO ostrom_router USING (true) WITH CHECK (false);
    GRANT SELECT ON runners_registered TO ostrom_router;
    CREATE FUNCTION runners_registered_key(p_id UUID) RETURNS TABLE (org_id UUID, key_hash TEXT)
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT r.org_id, r.key_hash FROM runners_registered r WHERE r.id = p_id;
    $$;
    ${REGISTERED_ROUTER_FUNCTIONS.joinToString("\n") { "REVOKE EXECUTE ON FUNCTION $it FROM PUBLIC;" }}
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
            $REGISTERED_ROUTER_GRANTS
        END IF;
    END $$;
    GRANT CREATE ON SCHEMA public TO ostrom_router;
    ${REGISTERED_ROUTER_FUNCTIONS.joinToString("\n") { "ALTER FUNCTION $it OWNER TO ostrom_router;" }}
    REVOKE CREATE ON SCHEMA public FROM ostrom_router;
    """
        .trimIndent()

/**
 * The schema of the runners module; its tables start with `runners_`.
 *
 * `runners_sessions` and `runners_usage` are tenant tables, confined by row-level security to the organization of the
 * transaction (S2-01). `runners_session_routes` (a session's id and organization, the hash of its runner token and
 * its reconcile schedule, no customer content) is also used across organizations, by the reconciler (due sessions of
 * every organization) and by the runner channel (a session found by its runner token): since version 3, under forced
 * row-level security like the other tables, and across organizations only through [ROUTER_FUNCTIONS]
 * (docs/tenancy.md). `runners_registered` (version 2) is a tenant table too: the machines an organization registered,
 * with the hash of their key; a heartbeat reads one runner's organization and key hash across organizations only
 * through [REGISTERED_ROUTER_FUNCTIONS] (version 4). `runners_assignments` (version 6) is a tenant table: the
 * executions handed to an organization's registered runners.
 */
val RunnersMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        version = 1,
        name = "create_cloud_sessions",
        sql = """
            CREATE TABLE runners_sessions (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                owner_id TEXT NOT NULL,
                name TEXT NOT NULL,
                agent TEXT NOT NULL,
                model TEXT,
                prompt TEXT,
                repository_url TEXT,
                repository_ref TEXT,
                state TEXT NOT NULL,
                desired_state TEXT NOT NULL,
                phase TEXT,
                epoch BIGINT NOT NULL,
                generation BIGINT NOT NULL,
                failure_code TEXT,
                failure_message TEXT,
                idle_timeout_minutes INTEGER NOT NULL,
                max_run_minutes INTEGER NOT NULL,
                restricted_egress BOOLEAN NOT NULL,
                created_at TIMESTAMPTZ NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL,
                execution_started_at TIMESTAMPTZ,
                ready_at TIMESTAMPTZ,
                last_heartbeat_at TIMESTAMPTZ,
                last_activity_at TIMESTAMPTZ,
                paused_at TIMESTAMPTZ,
                terminated_at TIMESTAMPTZ,
                pausing_since TIMESTAMPTZ,
                runner_stopped_at TIMESTAMPTZ,
                runner_token_hash TEXT
            );
            CREATE INDEX runners_sessions_owner ON runners_sessions (org_id, owner_id, created_at DESC, id DESC);
            CREATE INDEX runners_sessions_holding ON runners_sessions (org_id, state);
            CREATE TABLE runners_usage (
                org_id UUID NOT NULL,
                month TEXT NOT NULL,
                seconds BIGINT NOT NULL,
                PRIMARY KEY (org_id, month)
            );
            CREATE TABLE runners_session_routes (
                session_id UUID PRIMARY KEY REFERENCES runners_sessions (id) ON DELETE CASCADE,
                org_id UUID NOT NULL,
                runner_token_hash TEXT,
                next_reconcile_at TIMESTAMPTZ,
                reconcile_owner TEXT,
                reconcile_until TIMESTAMPTZ,
                claimed_at TIMESTAMPTZ
            );
            CREATE INDEX runners_session_routes_due ON runners_session_routes (next_reconcile_at)
                WHERE next_reconcile_at IS NOT NULL;
            CREATE UNIQUE INDEX runners_session_routes_runner_token ON runners_session_routes (runner_token_hash)
                WHERE runner_token_hash IS NOT NULL;
            $TENANT_ROLE_SQL
            ${tenantTableSql("runners_sessions")}
            ${tenantTableSql("runners_usage")}
            GRANT SELECT, INSERT, UPDATE ON runners_session_routes TO $TENANT_ROLE;
        """.trimIndent(),
    ),
    SchemaMigration(
        version = 2,
        name = "create_registered_runners",
        sql = """
            CREATE TABLE runners_registered (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                name TEXT NOT NULL,
                labels TEXT NOT NULL,
                version TEXT,
                key_hash TEXT NOT NULL,
                registered_by TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL,
                last_seen_at TIMESTAMPTZ
            );
            CREATE UNIQUE INDEX runners_registered_name ON runners_registered (org_id, lower(name));
            CREATE UNIQUE INDEX runners_registered_key ON runners_registered (key_hash);
            ${tenantTableSql("runners_registered")}
        """.trimIndent(),
    ),
    SchemaMigration(
        version = 3,
        name = "runners_session_routes_under_row_level_security",
        sql = ROUTES_UNDER_ROW_LEVEL_SECURITY_SQL,
    ),
    SchemaMigration(version = 4, name = "registered_runner_key", sql = REGISTERED_RUNNER_ORG_SQL),
    // RLS audit R9: the router's policies hold only inside its own functions, as identity's
    // do; the owner, a member of `ostrom_router` to hand them over, no longer passes them.
    SchemaMigration(
        version = 5,
        name = "runners_router_policies_inside_functions_only",
        sql = """
            ALTER POLICY runners_session_routes_router ON runners_session_routes USING (current_user = 'ostrom_router')
                WITH CHECK (current_user = 'ostrom_router');
            ALTER POLICY runners_registered_router ON runners_registered USING (current_user = 'ostrom_router')
        """.trimIndent(),
    ),
    // The executions handed to registered runners (RegisteredRuntimeDriver): a tenant table, never read across
    // organizations. A runner is not a foreign key: a removed runner leaves its executions lost, then relocated.
    SchemaMigration(
        version = 6,
        name = "create_runner_assignments",
        sql = """
            CREATE TABLE runners_assignments (
                session_id UUID NOT NULL REFERENCES runners_sessions (id) ON DELETE CASCADE,
                epoch BIGINT NOT NULL,
                org_id UUID NOT NULL,
                runner_id UUID,
                created_at TIMESTAMPTZ NOT NULL,
                delivered_at TIMESTAMPTZ,
                stopped_at TIMESTAMPTZ,
                dropped_at TIMESTAMPTZ,
                state TEXT,
                holds_workspace BOOLEAN NOT NULL DEFAULT FALSE,
                PRIMARY KEY (session_id, epoch)
            );
            CREATE INDEX runners_assignments_runner ON runners_assignments (org_id, runner_id);
            CREATE INDEX runners_assignments_waiting ON runners_assignments (org_id, created_at)
                WHERE runner_id IS NULL AND stopped_at IS NULL;
            ${tenantTableSql("runners_assignments")}
        """.trimIndent(),
    ),
    // The sessions a registered runner runs at once at most, from its last heartbeat (null: no limit declared). An
    // execution is never handed to a runner that already holds as many (RunnerAssignmentsRepositoryImpl).
    SchemaMigration(
        version = 7,
        name = "registered_runner_capacity",
        sql = "ALTER TABLE runners_registered ADD COLUMN capacity INTEGER",
    ),
    // What a waiting heartbeat's look reads, once a second per organization (InProcessRunnerWorkSignals): the
    // executions not handed over yet, a handful at any time, whatever the history.
    SchemaMigration(
        version = 8,
        name = "runner_assignments_undelivered",
        sql = "CREATE INDEX runners_assignments_undelivered ON runners_assignments (org_id) " +
            "WHERE delivered_at IS NULL AND stopped_at IS NULL",
    ),
    // TC-REP-2: the terminal tickets, in the database instead of a replica's memory, so that one replica issues a
    // ticket another redeems (TerminalTicketsImpl). Their hash only, a minute's life by the database's clock.
    SchemaMigration(
        version = 9,
        name = "terminal_tickets",
        sql = """
            CREATE TABLE runners_terminal_tickets (
                token_hash TEXT PRIMARY KEY,
                org_id UUID NOT NULL,
                session_id UUID NOT NULL,
                member_id TEXT NOT NULL,
                admin BOOLEAN NOT NULL,
                expires_at TIMESTAMPTZ NOT NULL
            );
            CREATE INDEX runners_terminal_tickets_expiry ON runners_terminal_tickets (org_id, expires_at);
            ${tenantTableSql("runners_terminal_tickets")}
        """.trimIndent(),
    ),
    // Whose GitHub access a session's Git and `gh` use: its owner's link by default.
    SchemaMigration(
        version = 10,
        name = "session_github_access",
        sql = "ALTER TABLE runners_sessions ADD COLUMN github_access TEXT NOT NULL DEFAULT 'owner'",
    ),
    // TC-REP-1: which replica holds each session's runner socket (TerminalHostsImpl), so that a member attached on
    // another replica is relayed to it. A hold ends 90 s after its last renewal by the database's clock.
    SchemaMigration(
        version = 11,
        name = "terminal_hosts",
        sql = """
            CREATE TABLE runners_terminal_hosts (
                session_id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                epoch BIGINT NOT NULL,
                replica_url TEXT NOT NULL,
                expires_at TIMESTAMPTZ NOT NULL
            );
            CREATE INDEX runners_terminal_hosts_expiry ON runners_terminal_hosts (org_id, expires_at);
            ${tenantTableSql("runners_terminal_hosts")}
        """.trimIndent(),
    ),
    // The agent's background work, as the runner's heartbeats report it (TeamCode af1a5f7b): the start of the
    // unbroken stretch of reports and the last one, for the idle pause.
    SchemaMigration(
        version = 12,
        name = "session_background_work",
        sql = "ALTER TABLE runners_sessions ADD COLUMN background_since TIMESTAMPTZ, " +
            "ADD COLUMN background_seen_at TIMESTAMPTZ",
    ),
    // runners.cloud.environment.v1: the preparation recipe asked for at creation (JSON, never edited), and whether
    // a failed preparation may succeed on another attempt.
    SchemaMigration(
        version = 13,
        name = "session_environment",
        sql = """
            ALTER TABLE runners_sessions ADD COLUMN environment TEXT;
            ALTER TABLE runners_sessions ADD COLUMN failure_retryable BOOLEAN NOT NULL DEFAULT FALSE;
        """.trimIndent(),
    ),
    // runners.cloud.profiles.v1: what a Kubernetes session runs on (JSON, SessionProfile), copied from its cloud
    // profile at creation, or given the former variables' at the session's next step; and since when its steps have
    // found its cluster unreachable, which bounds how long they are retried.
    SchemaMigration(
        version = 14,
        name = "session_profile",
        sql = """
            ALTER TABLE runners_sessions ADD COLUMN profile TEXT;
            ALTER TABLE runners_sessions ADD COLUMN cluster_unreachable_since TIMESTAMPTZ;
        """.trimIndent(),
    ),
    SchemaMigration(version = 15, name = "sessions_without_profile", sql = SESSIONS_WITHOUT_PROFILE_SQL),
    // runners.cloud.retention.v1 (TeamCode's `expiresAt` and `terminatedReason`): when a paused session is terminated
    // with its workspace, from the paused retention in force when it paused, and why a terminated session ended
    // (`deleted` or `expired`). The sessions paused before the upgrade are marked (`expiry_backfill`) and get one
    // step, which gives them their expiry once (ReconcileCloudSessionUseCaseImpl's paused step); a session paused
    // afterwards under a retention left unset is kept, whatever the setting becomes.
    SchemaMigration(
        version = 16,
        name = "session_expiry",
        sql = """
            ALTER TABLE runners_sessions ADD COLUMN expires_at TIMESTAMPTZ, ADD COLUMN terminated_reason TEXT,
                ADD COLUMN expiry_backfill BOOLEAN NOT NULL DEFAULT false;
            UPDATE runners_sessions SET expiry_backfill = true WHERE state = 'paused';
            UPDATE runners_session_routes r
                SET next_reconcile_at = LEAST(COALESCE(r.next_reconcile_at, now()), now())
                FROM runners_sessions s WHERE s.id = r.session_id AND s.expiry_backfill;
        """.trimIndent(),
    ),
    SchemaMigration(version = 17, name = "conversation_sessions", sql = CONVERSATION_SQL),
    SchemaMigration(version = 18, name = "workspace_deletion", sql = WORKSPACE_DELETION_SQL),
    SchemaMigration(version = 19, name = "create_keys", sql = CREATE_KEYS_SQL),
)
