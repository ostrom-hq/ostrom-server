// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

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
)
