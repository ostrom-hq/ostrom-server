// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

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

/**
 * The GitHub integration's schema: one installation of the App per organization, the single-use states of the
 * install flow (their SHA-256 only), and the check run published per gate.
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
)
