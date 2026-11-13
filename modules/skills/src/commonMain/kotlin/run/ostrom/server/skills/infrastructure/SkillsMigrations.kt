// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.skills.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.database.TENANT_ROLE
import run.ostrom.server.kernel.database.TENANT_SETTING

/** The tables of the module, each confined by row-level security to the organization of the transaction. */
private val TABLES =
    listOf("skills_skills", "skills_versions", "skills_files", "skills_assignments", "skills_installations")

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
 * The schema of the skills module (TeamCode's five tables `skills`, `skill_versions`, `skill_files`,
 * `skill_assignments`, `skill_installations`, with its later `all_members` and `owner_id` columns): every table has an
 * `org_id`, first in its indexes. A skill's name is unique in its organization, team and personal skills alike; a
 * version's label is unique per skill and case-sensitive; files are stored in PostgreSQL (`BYTEA`, at most 5 MiB per
 * version). A decision row stays when the decision is cleared (`decision` null), so its id names the same pair in the
 * audit log. The tenant role is created by the identity module's first migration.
 */
val SkillsMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        version = 1,
        name = "create_skills",
        sql = """
            CREATE TABLE skills_skills (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                name TEXT NOT NULL,
                owner_id TEXT,
                pinned_version_id UUID,
                all_members BOOLEAN NOT NULL DEFAULT FALSE,
                retired_at TIMESTAMPTZ,
                created_at TIMESTAMPTZ NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL,
                UNIQUE (org_id, name),
                UNIQUE (id, org_id)
            );
            CREATE INDEX skills_skills_org_updated ON skills_skills (org_id, updated_at DESC, name);
            CREATE INDEX skills_skills_org_owner ON skills_skills (org_id, owner_id, updated_at DESC);
            CREATE TABLE skills_versions (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                skill_id UUID NOT NULL,
                version TEXT NOT NULL,
                digest TEXT NOT NULL,
                size_bytes BIGINT NOT NULL,
                file_count INTEGER NOT NULL,
                published_by TEXT NOT NULL,
                published_at TIMESTAMPTZ NOT NULL,
                retired_at TIMESTAMPTZ,
                UNIQUE (skill_id, version),
                UNIQUE (id, org_id),
                FOREIGN KEY (skill_id, org_id) REFERENCES skills_skills (id, org_id) ON DELETE CASCADE
            );
            CREATE INDEX skills_versions_org_skill ON skills_versions (org_id, skill_id, published_at DESC);
            CREATE TABLE skills_files (
                version_id UUID NOT NULL,
                org_id UUID NOT NULL,
                path TEXT NOT NULL,
                mode TEXT NOT NULL,
                size INTEGER NOT NULL,
                sha256 TEXT NOT NULL,
                content BYTEA NOT NULL,
                PRIMARY KEY (version_id, path),
                FOREIGN KEY (version_id, org_id) REFERENCES skills_versions (id, org_id) ON DELETE CASCADE
            );
            CREATE TABLE skills_assignments (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                skill_id UUID NOT NULL,
                member_id TEXT NOT NULL,
                decision TEXT,
                updated_by TEXT NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL,
                UNIQUE (skill_id, member_id),
                FOREIGN KEY (skill_id, org_id) REFERENCES skills_skills (id, org_id) ON DELETE CASCADE
            );
            CREATE INDEX skills_assignments_org_member ON skills_assignments (org_id, member_id);
            CREATE TABLE skills_installations (
                org_id UUID NOT NULL,
                device_id TEXT NOT NULL,
                device_name TEXT NOT NULL,
                member_id TEXT NOT NULL,
                skill_id UUID NOT NULL,
                version TEXT,
                digest TEXT,
                status TEXT NOT NULL,
                reported_at TIMESTAMPTZ NOT NULL,
                PRIMARY KEY (device_id, skill_id),
                FOREIGN KEY (skill_id, org_id) REFERENCES skills_skills (id, org_id) ON DELETE CASCADE
            );
            CREATE INDEX skills_installations_org_member ON skills_installations (org_id, member_id, reported_at DESC);
            CREATE INDEX skills_installations_org_skill ON skills_installations (org_id, skill_id);
            ${TABLES.joinToString("\n") { tenantTable(it) }}
        """.trimIndent(),
    ),
)
