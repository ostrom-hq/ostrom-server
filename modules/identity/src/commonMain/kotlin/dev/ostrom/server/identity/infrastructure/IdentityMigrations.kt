// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

/** The functions' signatures, for their handover to `ostrom_router`. */
private val ROUTER_FUNCTIONS =
    listOf(
        "identity_token_by_hash(TEXT)",
        "identity_token_touch(UUID, TIMESTAMPTZ)",
        "identity_revoke_agent_session_tokens(TEXT, TIMESTAMPTZ)",
    )

/**
 * Version 6 (RLS audit R1): outside any organization, the serving role only reaches the account-level tokens
 * (`org_id IS NULL`: personal and CLI tokens, read and changed by their user); an organization's tokens (the agent
 * tokens) are written inside their organization, and the three things done to them from outside it go through narrow
 * `SECURITY DEFINER` functions owned by `ostrom_router` (`NOLOGIN`, never a member of the serving role): authenticating
 * a token by its hash, noting its last use, and revoking the tokens of an agent session. `pg_temp` comes last in
 * their `search_path`, and only the serving role may run them (S2-60), as for the orgs module's functions. The
 * router's policy holds only inside those functions (`current_user = 'ostrom_router'`): the owner, a member of
 * `ostrom_router` to hand the functions over, stays bound to the organization policies like everyone else.
 */
private val TOKENS_OF_ORGANIZATIONS_SQL: String =
    """
    DROP POLICY identity_api_tokens_account ON identity_api_tokens;
    CREATE POLICY identity_api_tokens_account ON identity_api_tokens
        USING (org_id IS NULL AND NULLIF(current_setting('ostrom.org_id', true), '') IS NULL
            AND current_user <> 'ostrom_tenant')
        WITH CHECK (org_id IS NULL AND NULLIF(current_setting('ostrom.org_id', true), '') IS NULL
            AND current_user <> 'ostrom_tenant');
    DO $$ BEGIN
        CREATE ROLE ostrom_router NOLOGIN NOSUPERUSER NOBYPASSRLS;
    EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL;
    END $$;
    DO $$ BEGIN
        GRANT ostrom_router TO CURRENT_USER;
    EXCEPTION WHEN unique_violation THEN NULL;
    END $$;
    GRANT USAGE ON SCHEMA public TO ostrom_router;
    CREATE POLICY identity_api_tokens_router ON identity_api_tokens TO ostrom_router
        USING (current_user = 'ostrom_router') WITH CHECK (current_user = 'ostrom_router');
    GRANT SELECT, UPDATE (last_used_at, revoked_at) ON identity_api_tokens TO ostrom_router;
    CREATE FUNCTION identity_token_by_hash(p_hash TEXT) RETURNS TABLE (
        id UUID, user_id UUID, org_id UUID, name TEXT, kind TEXT, scopes TEXT, hint TEXT, agent_session_id TEXT,
        created_at TIMESTAMPTZ, expires_at TIMESTAMPTZ, last_used_at TIMESTAMPTZ, revoked_at TIMESTAMPTZ,
        authenticated_at TIMESTAMPTZ
    ) LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT t.id, t.user_id, t.org_id, t.name, t.kind, t.scopes, t.hint, t.agent_session_id, t.created_at,
               t.expires_at, t.last_used_at, t.revoked_at, t.authenticated_at
        FROM identity_api_tokens t WHERE t.secret_hash = p_hash;
    $$;
    CREATE FUNCTION identity_token_touch(p_id UUID, p_at TIMESTAMPTZ) RETURNS VOID
        LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        UPDATE identity_api_tokens SET last_used_at = p_at WHERE id = p_id;
    $$;
    CREATE FUNCTION identity_revoke_agent_session_tokens(p_session TEXT, p_at TIMESTAMPTZ) RETURNS BIGINT
        LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        WITH revoked AS (
            UPDATE identity_api_tokens SET revoked_at = p_at
            WHERE agent_session_id = p_session AND kind = 'AGENT' AND revoked_at IS NULL
            RETURNING 1
        )
        SELECT count(*) FROM revoked;
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

/**
 * Version 15 (audit SERVEUR-3 B3): the purge function crosses row-level security for every organization, so it bounds
 * the date it is given itself. No date, an infinite one or one in the future is refused; and it never deletes an agent
 * token expired since less than the retention (`IdentityModule.AGENT_TOKEN_RETENTION`, 7 days), whatever the date. The
 * function keeps its owner, its grants and its `search_path`.
 */
private val BOUND_PURGE_SQL: String =
    """
    CREATE OR REPLACE FUNCTION identity_purge_agent_tokens(p_before TIMESTAMPTZ) RETURNS BIGINT
        LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        DECLARE
            gone BIGINT;
        BEGIN
            IF p_before IS NULL OR NOT isfinite(p_before) OR p_before > now() THEN
                RAISE EXCEPTION 'purge date refused: no date, an infinite one or one in the future'
                    USING ERRCODE = '22023';
            END IF;
            DELETE FROM identity_api_tokens
            WHERE kind = 'AGENT' AND expires_at < LEAST(p_before, now() - INTERVAL '7 days');
            GET DIAGNOSTICS gone = ROW_COUNT;
            RETURN gone;
        END
    $$;
    """
        .trimIndent()

/** The purge of the agent tokens, the one more operation across organizations (version 14). */
private const val PURGE_FUNCTION = "identity_purge_agent_tokens(TIMESTAMPTZ)"

/**
 * Version 14: the agent tokens long expired are deleted, across organizations (the launch path's load test, which
 * found them kept for ever): one per launch, revoked when it ends (R2b) or expired a day after, a row per launch. A
 * `SECURITY DEFINER` function owned by `ostrom_router`, as the version 6 functions, deletes only agent tokens expired
 * before the date it is given, with an index on their expiry so that the hourly purge never reads the whole table.
 */
private val PURGE_AGENT_TOKENS_SQL: String =
    """
    CREATE INDEX identity_api_tokens_agent_expiry ON identity_api_tokens (expires_at) WHERE kind = 'AGENT';
    GRANT DELETE ON identity_api_tokens TO ostrom_router;
    CREATE FUNCTION identity_purge_agent_tokens(p_before TIMESTAMPTZ) RETURNS BIGINT
        LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        WITH gone AS (
            DELETE FROM identity_api_tokens WHERE kind = 'AGENT' AND expires_at < p_before RETURNING 1
        )
        SELECT count(*) FROM gone;
    $$;
    REVOKE EXECUTE ON FUNCTION $PURGE_FUNCTION FROM PUBLIC;
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
            GRANT EXECUTE ON FUNCTION $PURGE_FUNCTION TO ostrom_app;
        END IF;
    END $$;
    GRANT CREATE ON SCHEMA public TO ostrom_router;
    ALTER FUNCTION $PURGE_FUNCTION OWNER TO ostrom_router;
    REVOKE CREATE ON SCHEMA public FROM ostrom_router;
    """
        .trimIndent()

/**
 * The schema of the identity module; its tables start with `identity_`.
 *
 * Version 1 also creates the `ostrom_tenant` role every organization-scoped query runs as
 * (`dev.ostrom.server.kernel.database.inOrg`): identity is the first module with tenant rows (the audit log). Roles
 * are global to the PostgreSQL cluster, so its creation tolerates an existing role (another database, or a parallel
 * test that created it first).
 */
val IdentityMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        version = 1,
        name = "create_identity",
        sql = """
            DO $$
            BEGIN
                CREATE ROLE ostrom_tenant NOLOGIN;
            EXCEPTION WHEN duplicate_object OR unique_violation THEN
                NULL;
            END
            $$;
            DO $$
            BEGIN
                GRANT ostrom_tenant TO CURRENT_USER;
            EXCEPTION WHEN unique_violation THEN
                NULL;
            END
            $$;
            CREATE TABLE identity_users (
                id UUID PRIMARY KEY,
                login TEXT NOT NULL UNIQUE,
                display_name TEXT NOT NULL,
                primary_email TEXT,
                avatar_url TEXT,
                created_at TIMESTAMPTZ NOT NULL
            );
            CREATE TABLE identity_user_emails (
                user_id UUID NOT NULL REFERENCES identity_users (id) ON DELETE CASCADE,
                email TEXT NOT NULL,
                verified BOOLEAN NOT NULL,
                created_at TIMESTAMPTZ NOT NULL,
                PRIMARY KEY (user_id, email)
            );
            CREATE UNIQUE INDEX identity_user_emails_verified ON identity_user_emails (email) WHERE verified;
            CREATE TABLE identity_identities (
                issuer TEXT NOT NULL,
                subject TEXT NOT NULL,
                user_id UUID NOT NULL REFERENCES identity_users (id) ON DELETE CASCADE,
                email TEXT,
                created_at TIMESTAMPTZ NOT NULL,
                last_used_at TIMESTAMPTZ NOT NULL,
                PRIMARY KEY (issuer, subject)
            );
            CREATE INDEX identity_identities_user ON identity_identities (user_id);
            CREATE TABLE identity_web_sessions (
                id UUID PRIMARY KEY,
                user_id UUID NOT NULL REFERENCES identity_users (id) ON DELETE CASCADE,
                secret_hash TEXT NOT NULL UNIQUE,
                created_at TIMESTAMPTZ NOT NULL,
                expires_at TIMESTAMPTZ NOT NULL,
                last_seen_at TIMESTAMPTZ NOT NULL,
                revoked_at TIMESTAMPTZ
            );
            CREATE INDEX identity_web_sessions_user ON identity_web_sessions (user_id) WHERE revoked_at IS NULL;
            CREATE TABLE identity_api_tokens (
                id UUID PRIMARY KEY,
                user_id UUID NOT NULL REFERENCES identity_users (id) ON DELETE CASCADE,
                org_id UUID,
                name TEXT NOT NULL,
                kind TEXT NOT NULL,
                scopes TEXT NOT NULL,
                hint TEXT NOT NULL,
                agent_session_id TEXT,
                secret_hash TEXT NOT NULL UNIQUE,
                created_at TIMESTAMPTZ NOT NULL,
                expires_at TIMESTAMPTZ,
                last_used_at TIMESTAMPTZ,
                revoked_at TIMESTAMPTZ,
                CHECK (kind <> 'AGENT' OR (agent_session_id IS NOT NULL AND org_id IS NOT NULL))
            );
            CREATE INDEX identity_api_tokens_user ON identity_api_tokens (user_id, created_at DESC) WHERE revoked_at IS NULL;
            CREATE INDEX identity_api_tokens_agent ON identity_api_tokens (agent_session_id)
                WHERE agent_session_id IS NOT NULL AND revoked_at IS NULL;
            CREATE TABLE identity_device_authorizations (
                id UUID PRIMARY KEY,
                device_code_hash TEXT NOT NULL UNIQUE,
                user_code TEXT NOT NULL,
                client_name TEXT NOT NULL,
                status TEXT NOT NULL,
                user_id UUID REFERENCES identity_users (id) ON DELETE CASCADE,
                created_at TIMESTAMPTZ NOT NULL,
                expires_at TIMESTAMPTZ NOT NULL,
                last_polled_at TIMESTAMPTZ
            );
            CREATE UNIQUE INDEX identity_device_pending_code ON identity_device_authorizations (user_code)
                WHERE status = 'PENDING';
            CREATE TABLE identity_audit_events (
                id UUID PRIMARY KEY,
                chain TEXT NOT NULL,
                sequence BIGINT NOT NULL,
                org_id UUID,
                actor_id UUID,
                action TEXT NOT NULL,
                target_type TEXT NOT NULL,
                target_id TEXT NOT NULL,
                details TEXT NOT NULL,
                occurred_at TIMESTAMPTZ NOT NULL,
                previous_hash TEXT,
                hash TEXT NOT NULL,
                signature TEXT NOT NULL,
                key_id TEXT NOT NULL,
                UNIQUE (chain, sequence)
            );
            CREATE INDEX identity_audit_events_actor ON identity_audit_events (actor_id, sequence DESC) WHERE org_id IS NULL;
            ALTER TABLE identity_audit_events ENABLE ROW LEVEL SECURITY;
            CREATE POLICY identity_audit_events_tenant ON identity_audit_events
                USING (org_id = CAST(NULLIF(current_setting('ostrom.org_id', true), '') AS UUID))
                WITH CHECK (org_id = CAST(NULLIF(current_setting('ostrom.org_id', true), '') AS UUID));
            GRANT SELECT, INSERT ON identity_audit_events TO ostrom_tenant
        """.trimIndent(),
    ),
    SchemaMigration(
        version = 2,
        name = "create_sign_in",
        sql = """
            CREATE TABLE identity_oauth_attempts (
                id UUID PRIMARY KEY,
                state_hash TEXT NOT NULL UNIQUE,
                provider TEXT NOT NULL,
                connection_id UUID,
                code_verifier TEXT NOT NULL,
                nonce TEXT NOT NULL,
                redirect_path TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL,
                expires_at TIMESTAMPTZ NOT NULL,
                used_at TIMESTAMPTZ
            );
            CREATE INDEX identity_oauth_attempts_expiry ON identity_oauth_attempts (expires_at);
            CREATE TABLE identity_magic_links (
                id UUID PRIMARY KEY,
                token_hash TEXT NOT NULL UNIQUE,
                email TEXT NOT NULL,
                redirect_path TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL,
                expires_at TIMESTAMPTZ NOT NULL,
                used_at TIMESTAMPTZ
            );
            CREATE INDEX identity_magic_links_expiry ON identity_magic_links (expires_at)
        """.trimIndent(),
    ),
    // S2-07: an OAuth attempt started by a signed-in user links the provider to that user.
    // S2-01: every identity table with an org_id is ENABLE + FORCE row level security, so the owner role is bound too.
    // The organization rows are only seen inside their organization (inOrg); outside any organization (account-level
    // work: authenticating a token, a user's own audit log), the serving role sees the rows that are no organization's
    // (audit) or the tokens by their hash; the tenant role without an organization sees nothing (fail-closed).
    SchemaMigration(
        version = 3,
        name = "link_identities_and_force_tenancy",
        sql = """
            ALTER TABLE identity_oauth_attempts ADD COLUMN link_user_id UUID REFERENCES identity_users (id) ON DELETE CASCADE;
            ALTER TABLE identity_audit_events FORCE ROW LEVEL SECURITY;
            CREATE POLICY identity_audit_events_account ON identity_audit_events
                USING (org_id IS NULL AND NULLIF(current_setting('ostrom.org_id', true), '') IS NULL
                    AND current_user <> 'ostrom_tenant')
                WITH CHECK (org_id IS NULL AND NULLIF(current_setting('ostrom.org_id', true), '') IS NULL
                    AND current_user <> 'ostrom_tenant');
            ALTER TABLE identity_api_tokens ENABLE ROW LEVEL SECURITY;
            ALTER TABLE identity_api_tokens FORCE ROW LEVEL SECURITY;
            CREATE POLICY identity_api_tokens_tenant ON identity_api_tokens
                USING (org_id = CAST(NULLIF(current_setting('ostrom.org_id', true), '') AS UUID))
                WITH CHECK (org_id = CAST(NULLIF(current_setting('ostrom.org_id', true), '') AS UUID));
            CREATE POLICY identity_api_tokens_account ON identity_api_tokens
                USING (NULLIF(current_setting('ostrom.org_id', true), '') IS NULL AND current_user <> 'ostrom_tenant')
                WITH CHECK (NULLIF(current_setting('ostrom.org_id', true), '') IS NULL AND current_user <> 'ostrom_tenant');
            GRANT SELECT ON identity_api_tokens TO ostrom_tenant
        """.trimIndent(),
    ),
    // S2-08: a signed-in person's one-time link (`opk_`, 15 min) that opens a browser session elsewhere.
    SchemaMigration(
        version = 4,
        name = "sign_in_links",
        sql = """
            CREATE TABLE identity_sign_in_links (
                id UUID PRIMARY KEY,
                token_hash TEXT NOT NULL UNIQUE,
                user_id UUID NOT NULL REFERENCES identity_users (id) ON DELETE CASCADE,
                created_at TIMESTAMPTZ NOT NULL,
                expires_at TIMESTAMPTZ NOT NULL,
                used_at TIMESTAMPTZ
            );
            CREATE INDEX identity_sign_in_links_expiry ON identity_sign_in_links (expires_at)
        """.trimIndent(),
    ),
    // The phone app (S2-24, S2-31, S2-54): an OAuth attempt the app started keeps its deep link, PKCE challenge and
    // state; its one-time code; the app's token keeps the hash of its unlock key and its last biometric unlock; and
    // each person's notification preferences. None of these rows belongs to an organization: like the users, the
    // sign-in links and the account-level tokens, they are read outside any organization, by hash or by user.
    SchemaMigration(
        version = 5,
        name = "mobile_app",
        sql = """
            ALTER TABLE identity_oauth_attempts ADD COLUMN mobile_redirect_uri TEXT, ADD COLUMN mobile_code_challenge TEXT,
                ADD COLUMN mobile_state TEXT;
            ALTER TABLE identity_api_tokens ADD COLUMN unlock_key_hash TEXT, ADD COLUMN authenticated_at TIMESTAMPTZ;
            ALTER TABLE identity_api_tokens ADD CONSTRAINT identity_api_tokens_unlock_key
                CHECK ((kind = 'MOBILE') = (unlock_key_hash IS NOT NULL));
            CREATE TABLE identity_mobile_codes (
                id UUID PRIMARY KEY,
                code_hash TEXT NOT NULL UNIQUE,
                user_id UUID NOT NULL REFERENCES identity_users (id) ON DELETE CASCADE,
                code_challenge TEXT NOT NULL,
                redirect_uri TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL,
                expires_at TIMESTAMPTZ NOT NULL,
                used_at TIMESTAMPTZ
            );
            CREATE INDEX identity_mobile_codes_expiry ON identity_mobile_codes (expires_at);
            CREATE TABLE identity_notification_preferences (
                user_id UUID PRIMARY KEY REFERENCES identity_users (id) ON DELETE CASCADE,
                requests BOOLEAN NOT NULL,
                decisions BOOLEAN NOT NULL,
                spending BOOLEAN NOT NULL,
                quiet_start TEXT,
                quiet_end TEXT,
                quiet_time_zone TEXT,
                updated_at TIMESTAMPTZ NOT NULL
            )
        """.trimIndent(),
    ),
    SchemaMigration(
        version = 6,
        name = "tokens_of_organizations_through_the_router",
        sql = TOKENS_OF_ORGANIZATIONS_SQL,
    ),
    // An organization's service keys are listed inside it, by organization: a partial index on the live keys, so the
    // list never scans the agent tokens of the same table.
    SchemaMigration(
        version = 7,
        name = "service_keys",
        sql = """
            CREATE INDEX identity_api_tokens_service ON identity_api_tokens (org_id, created_at DESC)
                WHERE kind = 'SERVICE' AND revoked_at IS NULL
        """.trimIndent(),
    ),
    // `ostrom login --admin`: the governance scopes asked for, and the organization whose owner or admin approved
    // them (not `org_id`: the authorization belongs to no organization, only the token it gives does).
    SchemaMigration(
        version = 8,
        name = "device_authorizations_for_governance",
        sql = """
            ALTER TABLE identity_device_authorizations ADD COLUMN scopes TEXT NOT NULL DEFAULT '';
            ALTER TABLE identity_device_authorizations ADD COLUMN approving_org_id UUID;
        """.trimIndent(),
    ),
    // Audit of the audit log, A1: the serving role holds UPDATE and DELETE on every table (default privileges) and the
    // organization policy only reads `ostrom.org_id`, which any role sets: it could rewrite or erase any organization's
    // log. One statement-level trigger refuses UPDATE, DELETE and TRUNCATE with an error, whatever rows the statement
    // reaches (none included), for every role. `ENABLE ALWAYS`: it fires in `session_replication_role = replica` too.
    // The owner (migrations, never serving) and a superuser can still drop or disable it: reading checks the chain.
    SchemaMigration(
        version = 9,
        name = "audit_log_append_only",
        sql = """
            CREATE FUNCTION identity_audit_events_append_only() RETURNS trigger LANGUAGE plpgsql
                SET search_path = pg_catalog AS $$
            BEGIN
                RAISE EXCEPTION 'identity_audit_events is append-only: % refused', TG_OP
                    USING ERRCODE = 'insufficient_privilege';
            END
            $$;
            CREATE TRIGGER identity_audit_events_append_only BEFORE UPDATE OR DELETE OR TRUNCATE
                ON identity_audit_events FOR EACH STATEMENT EXECUTE FUNCTION identity_audit_events_append_only();
            ALTER TABLE identity_audit_events ENABLE ALWAYS TRIGGER identity_audit_events_append_only
        """.trimIndent(),
    ),
    // Web audit n°2 M4: an e-mailed link signs in only in the browser that asked for it (the SHA-256 of its nonce).
    SchemaMigration(
        version = 10,
        name = "magic_links_bound_to_their_browser",
        sql = "ALTER TABLE identity_magic_links ADD COLUMN browser_hash TEXT",
    ),
    // `/devices` lists an organization's live tokens of `ostrom login --admin` inside it, for its owners and admins:
    // a partial index on them, so the list never scans the agent tokens and service keys of the same table.
    SchemaMigration(
        version = 11,
        name = "elevated_tokens",
        sql = """
            CREATE INDEX identity_api_tokens_elevated ON identity_api_tokens (org_id, created_at DESC)
                WHERE kind = 'ADMIN' AND revoked_at IS NULL
        """.trimIndent(),
    ),
    // The reads the bench measured (IdentityReadsBenchTest): `/audit?actor=` and `/audit?event=` read the whole chain
    // of the organization and kept a line in a thousand (72 and 82 ms of p95 at 20 calls on 10 000 lines); `/devices`
    // read every live token of the person, agents' included, to keep three (43 ms on 5 000). The person's index by kind
    // comes beside the one without, which `/tokens` keeps: it lists several kinds in one creation order. Built in the
    // migration's transaction, not CONCURRENTLY: writes to these tables wait for the build, short on today's tables.
    SchemaMigration(
        version = 12,
        name = "index_audit_filters_and_devices",
        sql = """
            CREATE INDEX identity_audit_events_chain_actor ON identity_audit_events (chain, actor_id, sequence DESC)
                WHERE actor_id IS NOT NULL;
            CREATE INDEX identity_audit_events_chain_action ON identity_audit_events (chain, action, sequence DESC);
            CREATE INDEX identity_api_tokens_user_kind ON identity_api_tokens (user_id, kind, created_at DESC, id)
                WHERE revoked_at IS NULL
        """.trimIndent(),
    ),
    // Web audit n°2 W3: the network an `ostrom login` was asked from (a /24 or a /48, never the address), shown by
    // the console before the yes. Null for the requests started before it, which expire within ten minutes.
    SchemaMigration(
        version = 13,
        name = "device_authorizations_requested_from",
        sql = "ALTER TABLE identity_device_authorizations ADD COLUMN requested_from TEXT",
    ),
    SchemaMigration(version = 14, name = "purge_agent_tokens", sql = PURGE_AGENT_TOKENS_SQL),
    SchemaMigration(version = 15, name = "bound_agent_token_purge", sql = BOUND_PURGE_SQL),
    // Q-APP-STEPUP-RISK: the approval request the app's last unlock was for. A yes from the app uses it once, within a
    // minute (PhoneUnlockProofs); null when the last unlock named none, or once used.
    SchemaMigration(
        version = 16,
        name = "api_tokens_unlock_request",
        sql = "ALTER TABLE identity_api_tokens ADD COLUMN unlock_request_id TEXT",
    ),
)
