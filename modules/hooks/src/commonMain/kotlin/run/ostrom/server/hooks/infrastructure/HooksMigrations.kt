// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.hooks.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.database.TENANT_ROLE
import run.ostrom.server.kernel.database.TENANT_SETTING

/** The tables of the module, each confined by row-level security to the organization of the transaction. */
private val TABLES = listOf("hooks_hooks", "hooks_versions", "hooks_files", "hooks_assignments")

/** Row-level security enabled and forced, one fail-closed policy (no setting, no row), the tenant role's grants. */
private fun tenantTable(table: String): String = """
    ALTER TABLE $table ENABLE ROW LEVEL SECURITY;
    ALTER TABLE $table FORCE ROW LEVEL SECURITY;
    CREATE POLICY ${table}_tenant ON $table
        USING (org_id = CAST(NULLIF(current_setting('$TENANT_SETTING', true), '') AS UUID))
        WITH CHECK (org_id = CAST(NULLIF(current_setting('$TENANT_SETTING', true), '') AS UUID));
    GRANT SELECT, INSERT, UPDATE, DELETE ON $table TO $TENANT_ROLE;
""".trimIndent()

/**
 * The schema of the hooks module (TeamCode's four tables `hooks`, `hook_versions`, `hook_files`, `hook_assignments`):
 * every table has an `org_id`, first in its indexes. A hook's name is unique in its organization; a version's label is
 * unique per hook and case-sensitive, and the version row holds its definition (`events` is the wire names, sorted and
 * comma-separated); files are stored in PostgreSQL (`BYTEA`, at most 1 MiB per version). A decision row stays when the
 * decision is cleared (`decision` null), so its id names the same pair in the audit log. The tenant role is created by
 * the identity module's first migration.
 */
val HooksMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        version = 1,
        name = "create_hooks",
        sql = """
            CREATE TABLE hooks_hooks (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                name TEXT NOT NULL,
                pinned_version_id UUID,
                all_members BOOLEAN NOT NULL DEFAULT FALSE,
                created_at TIMESTAMPTZ NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL,
                UNIQUE (org_id, name),
                UNIQUE (id, org_id)
            );
            CREATE INDEX hooks_hooks_org_updated ON hooks_hooks (org_id, updated_at DESC, name);
            CREATE TABLE hooks_versions (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                hook_id UUID NOT NULL,
                version TEXT NOT NULL,
                digest TEXT NOT NULL,
                events TEXT NOT NULL,
                matcher TEXT,
                command TEXT NOT NULL,
                timeout_seconds INTEGER NOT NULL,
                size_bytes BIGINT NOT NULL,
                file_count INTEGER NOT NULL,
                published_by TEXT NOT NULL,
                published_at TIMESTAMPTZ NOT NULL,
                UNIQUE (hook_id, version),
                UNIQUE (id, org_id),
                FOREIGN KEY (hook_id, org_id) REFERENCES hooks_hooks (id, org_id) ON DELETE CASCADE
            );
            CREATE INDEX hooks_versions_org_hook ON hooks_versions (org_id, hook_id, published_at DESC);
            CREATE TABLE hooks_files (
                version_id UUID NOT NULL,
                org_id UUID NOT NULL,
                path TEXT NOT NULL,
                mode TEXT NOT NULL,
                size INTEGER NOT NULL,
                sha256 TEXT NOT NULL,
                content BYTEA NOT NULL,
                PRIMARY KEY (version_id, path),
                FOREIGN KEY (version_id, org_id) REFERENCES hooks_versions (id, org_id) ON DELETE CASCADE
            );
            CREATE TABLE hooks_assignments (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                hook_id UUID NOT NULL,
                member_id TEXT NOT NULL,
                decision TEXT,
                updated_by TEXT NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL,
                UNIQUE (hook_id, member_id),
                FOREIGN KEY (hook_id, org_id) REFERENCES hooks_hooks (id, org_id) ON DELETE CASCADE
            );
            CREATE INDEX hooks_assignments_org_member ON hooks_assignments (org_id, member_id);
            ${TABLES.joinToString("\n") { tenantTable(it) }}
        """.trimIndent(),
    ),
)
