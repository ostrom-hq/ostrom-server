// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration

private const val ORG = "CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID)"

// A webhook names an installation, and the install callback a state, before any organization is known. Their
// transaction sets `ostrom.github_installation` or `ostrom.github_state` (only after the webhook's signature or the
// state's hash is checked) and sees that one row, never another: row level security stays forced on every table.
private const val INSTALLATION =
    "installation_id = CAST(NULLIF(current_setting('ostrom.github_installation', TRUE), '') AS BIGINT)"
private const val STATE = "state_hash = NULLIF(current_setting('ostrom.github_state', TRUE), '')"

private fun forced(table: String) = """
    ALTER TABLE $table ENABLE ROW LEVEL SECURITY;
    ALTER TABLE $table FORCE ROW LEVEL SECURITY;
""".trimIndent()

// Reads and deletes by the organization or by the one key the transaction names; writes only into the organization.
private fun keyed(table: String, key: String) = """
    ${forced(table)}
    CREATE POLICY ${table}_read ON $table FOR SELECT USING (org_id = $ORG OR $key);
    CREATE POLICY ${table}_delete ON $table FOR DELETE USING (org_id = $ORG OR $key);
    CREATE POLICY ${table}_insert ON $table FOR INSERT WITH CHECK (org_id = $ORG);
    CREATE POLICY ${table}_update ON $table FOR UPDATE USING (org_id = $ORG) WITH CHECK (org_id = $ORG);
""".trimIndent()

// One organization's rows only, read and written in its transaction.
private fun tenant(table: String) = """
    ${forced(table)}
    CREATE POLICY ${table}_org ON $table
        USING (org_id = $ORG)
        WITH CHECK (org_id = $ORG);
""".trimIndent()

// The cross-organization reads of a change of the App, functions of `ostrom_router` (docs/tenancy.md).
private const val INSTALLATIONS_COUNT = "github_installations_count()"
private const val OTHER_CLIENTS = "github_links_of_other_clients(TEXT)"

// A function handed to `ostrom_router`, which only `ostrom_app` may run.
private fun routed(function: String) = """
    REVOKE EXECUTE ON FUNCTION $function FROM PUBLIC;
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
            GRANT EXECUTE ON FUNCTION $function TO ostrom_app;
        END IF;
    END $$;
    GRANT CREATE ON SCHEMA public TO ostrom_router;
    ALTER FUNCTION $function OWNER TO ostrom_router;
    REVOKE CREATE ON SCHEMA public FROM ostrom_router;
""".trimIndent()

/**
 * The GitHub integration's schema. Version 1: one installation of the App per organization, the single-use states of
 * the install flow (their SHA-256 only), and the check run published per gate. Version 2: each organization's
 * directory as last read from GitHub (repositories, teams, when), under forced row level security like the rest;
 * and the instance's App created by the manifest flow, with the single-use states of that flow. Those two are the
 * instance's, not an organization's (no `org_id`, like `instance_info`): the App's key and secrets are sealed with
 * the secrets key (`AesGcmSecretCipher`), so a read of the table alone opens nothing. Version 3 (AUDIT-SECU-2): who
 * installed each installation, from its `installation.created` webhook, seen only by the transaction that names that
 * installation (no organization owns it yet); and the ids of the webhook deliveries handled in the last 30 days, the
 * instance's (an id and a time), so that a replayed delivery is ignored. Version 4: the members' GitHub links (one per
 * member of an organization, one member per GitHub account in it, the tokens sealed) and their pending attempts (the
 * device code or PKCE verifier sealed, the hash of the browser session of a web link), both an organization's rows
 * under forced row level security. Version 6: the App's version and last save, for the App entered by hand, and the two
 * functions of `ostrom_router` a change of the App reads across organizations with (the installations' count, the
 * members whose link another client issued).
 */
val GitHubMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        1,
        "create_github",
        """
        CREATE TABLE github_installations (
            org_id UUID PRIMARY KEY,
            installation_id BIGINT NOT NULL UNIQUE,
            account TEXT NOT NULL,
            created_at TIMESTAMPTZ NOT NULL
        );
        CREATE TABLE github_install_states (
            state_hash TEXT PRIMARY KEY,
            org_id UUID NOT NULL,
            created_by TEXT NOT NULL,
            expires_at TIMESTAMPTZ NOT NULL
        );
        CREATE TABLE github_checks (
            org_id UUID NOT NULL,
            gate_id UUID NOT NULL,
            repo TEXT NOT NULL,
            sha TEXT NOT NULL,
            check_run_id BIGINT NOT NULL,
            conclusion TEXT,
            updated_at TIMESTAMPTZ NOT NULL,
            PRIMARY KEY (org_id, gate_id)
        );
        ${keyed("github_installations", INSTALLATION)}
        ${keyed("github_install_states", STATE)}
        ${forced("github_checks")}
        CREATE POLICY github_checks_org ON github_checks
            USING (org_id = $ORG)
            WITH CHECK (org_id = $ORG)
        """.trimIndent(),
    ),
    SchemaMigration(
        2,
        "create_github_directory_and_app",
        """
        CREATE TABLE github_repos (
            org_id UUID NOT NULL,
            name TEXT NOT NULL,
            description TEXT,
            language TEXT,
            default_branch TEXT NOT NULL,
            archived BOOLEAN NOT NULL,
            pushed_at TIMESTAMPTZ,
            owners TEXT NOT NULL,
            PRIMARY KEY (org_id, name)
        );
        CREATE TABLE github_teams (
            org_id UUID NOT NULL,
            slug TEXT NOT NULL,
            name TEXT NOT NULL,
            members TEXT NOT NULL,
            repos TEXT NOT NULL,
            PRIMARY KEY (org_id, slug)
        );
        CREATE TABLE github_directory_syncs (
            org_id UUID PRIMARY KEY,
            synced_at TIMESTAMPTZ,
            checked_at TIMESTAMPTZ NOT NULL
        );
        ${tenant("github_repos")}
        ${tenant("github_teams")}
        ${tenant("github_directory_syncs")}
        CREATE TABLE github_apps (
            singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
            app_id BIGINT NOT NULL,
            slug TEXT NOT NULL,
            owner TEXT,
            client_id TEXT NOT NULL,
            private_key_sealed TEXT NOT NULL,
            client_secret_sealed TEXT NOT NULL,
            webhook_secret_sealed TEXT,
            created_by TEXT NOT NULL,
            created_at TIMESTAMPTZ NOT NULL
        );
        CREATE TABLE github_manifest_states (
            state_hash TEXT PRIMARY KEY,
            created_by TEXT NOT NULL,
            expires_at TIMESTAMPTZ NOT NULL
        )
        """.trimIndent(),
    ),
    SchemaMigration(
        3,
        "create_github_origins_and_deliveries",
        """
        CREATE TABLE github_installation_origins (
            installation_id BIGINT PRIMARY KEY,
            sender TEXT NOT NULL,
            account TEXT NOT NULL,
            account_type TEXT NOT NULL,
            created_at TIMESTAMPTZ NOT NULL
        );
        ${forced("github_installation_origins")}
        CREATE POLICY github_installation_origins_key ON github_installation_origins
            USING ($INSTALLATION)
            WITH CHECK ($INSTALLATION);
        CREATE TABLE github_webhook_deliveries (
            delivery_id TEXT PRIMARY KEY,
            received_at TIMESTAMPTZ NOT NULL
        );
        CREATE INDEX github_webhook_deliveries_received ON github_webhook_deliveries (received_at)
        """.trimIndent(),
    ),
    SchemaMigration(
        4,
        "create_github_links",
        """
        CREATE TABLE github_links (
            org_id UUID NOT NULL,
            member_id UUID NOT NULL,
            github_user_id BIGINT NOT NULL,
            github_login TEXT NOT NULL,
            client_id TEXT NOT NULL,
            tokens_sealed TEXT NOT NULL,
            state TEXT NOT NULL,
            access_expires_at TIMESTAMPTZ,
            refresh_expires_at TIMESTAMPTZ,
            token_version INTEGER NOT NULL,
            refresh_pending BOOLEAN NOT NULL DEFAULT FALSE,
            refresh_started_at TIMESTAMPTZ,
            linked_at TIMESTAMPTZ NOT NULL,
            refreshed_at TIMESTAMPTZ NOT NULL,
            PRIMARY KEY (org_id, member_id),
            UNIQUE (org_id, github_user_id)
        );
        CREATE TABLE github_link_attempts (
            org_id UUID NOT NULL,
            id TEXT NOT NULL,
            member_id UUID NOT NULL,
            kind TEXT NOT NULL,
            secret_sealed TEXT NOT NULL,
            binding TEXT,
            poll_interval INTEGER NOT NULL,
            next_poll_at TIMESTAMPTZ NOT NULL,
            expires_at TIMESTAMPTZ NOT NULL,
            PRIMARY KEY (org_id, id)
        );
        CREATE INDEX github_links_login ON github_links (org_id, github_login, member_id);
        CREATE INDEX github_link_attempts_member ON github_link_attempts (org_id, member_id);
        CREATE INDEX github_link_attempts_expiry ON github_link_attempts (org_id, expires_at);
        ${tenant("github_links")}
        ${tenant("github_link_attempts")}
        """.trimIndent(),
    ),
    // An App imported once from the former variables may have no OAuth client (the manifest's always has one).
    SchemaMigration(
        5,
        "github_app_oauth_client_optional",
        """
        ALTER TABLE github_apps ALTER COLUMN client_id DROP NOT NULL;
        ALTER TABLE github_apps ALTER COLUMN client_secret_sealed DROP NOT NULL
        """.trimIndent(),
    ),
    // The App entered by hand (TeamCode's GitHub App form): a version an administrator's change must name, and who
    // saved it last. A change of the App reads across organizations only through two functions of `ostrom_router`
    // (docs/tenancy.md): the count of the installations, when its id changes, and the members whose link another
    // client issued, when its client changes (ids only: each link is then removed in its own organization).
    SchemaMigration(
        6,
        "github_app_by_hand",
        """
        ALTER TABLE github_apps ADD COLUMN version INTEGER NOT NULL DEFAULT 1;
        ALTER TABLE github_apps ADD COLUMN updated_by TEXT;
        ALTER TABLE github_apps ADD COLUMN updated_at TIMESTAMPTZ;
        UPDATE github_apps SET updated_by = created_by, updated_at = created_at;
        ALTER TABLE github_apps ALTER COLUMN updated_by SET NOT NULL;
        ALTER TABLE github_apps ALTER COLUMN updated_at SET NOT NULL;
        DO $$ BEGIN
            CREATE ROLE ostrom_router NOLOGIN NOSUPERUSER NOBYPASSRLS;
        EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL;
        END $$;
        DO $$ BEGIN
            GRANT ostrom_router TO CURRENT_USER;
        EXCEPTION WHEN unique_violation THEN NULL;
        END $$;
        GRANT USAGE ON SCHEMA public TO ostrom_router;
        CREATE POLICY github_installations_router ON github_installations FOR SELECT TO ostrom_router
            USING (current_user = 'ostrom_router');
        GRANT SELECT (installation_id) ON github_installations TO ostrom_router;
        CREATE POLICY github_links_router ON github_links FOR SELECT TO ostrom_router
            USING (current_user = 'ostrom_router');
        GRANT SELECT (org_id, member_id, client_id) ON github_links TO ostrom_router;
        CREATE FUNCTION $INSTALLATIONS_COUNT RETURNS BIGINT
            LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
            SELECT count(installation_id) FROM github_installations;
        $$;
        CREATE FUNCTION github_links_of_other_clients(p_client TEXT) RETURNS TABLE (org_id UUID, member_id UUID)
            LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
            SELECT l.org_id, l.member_id FROM github_links l WHERE l.client_id <> p_client;
        $$;
        ${routed(INSTALLATIONS_COUNT)}
        ${routed(OTHER_CLIENTS)}
        """.trimIndent(),
    ),
)
