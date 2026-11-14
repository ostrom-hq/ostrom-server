// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.secrets.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration

/**
 * The schema of the secrets module; its tables start with `secrets_`, each confined to its org by row-level security.
 */
val SecretsMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        version = 1,
        name = "create_secrets",
        sql = """
            CREATE TABLE secrets_entries (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                name TEXT NOT NULL,
                owner_member_id UUID,
                envelope TEXT NOT NULL,
                version INTEGER NOT NULL,
                created_at TIMESTAMPTZ NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL,
                updated_by UUID NOT NULL
            );
            CREATE UNIQUE INDEX secrets_entries_name ON secrets_entries (org_id, name, owner_member_id) NULLS NOT DISTINCT;
            CREATE INDEX secrets_entries_owner ON secrets_entries (org_id, owner_member_id, name)
        """.trimIndent(),
    ),
    SchemaMigration(
        version = 2,
        name = "confine_secrets_to_their_org",
        sql = TENANT_ROLE_SQL + "\n" + tenantTableSql("secrets_entries"),
    ),
    SchemaMigration(
        version = 3,
        name = "create_environment_variants",
        sql = """
            CREATE TABLE environment_variants (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                variable TEXT NOT NULL,
                name TEXT NOT NULL,
                binding TEXT NOT NULL,
                owner_member_id UUID,
                envelope TEXT,
                version INTEGER NOT NULL,
                created_at TIMESTAMPTZ NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL
            );
            CREATE UNIQUE INDEX environment_variants_name
                ON environment_variants (org_id, variable, lower(name), owner_member_id) NULLS NOT DISTINCT;
            CREATE INDEX environment_variants_owner ON environment_variants (org_id, owner_member_id, variable);
            CREATE TABLE environment_assignments (
                org_id UUID NOT NULL,
                member_id UUID NOT NULL,
                variable TEXT NOT NULL,
                variant_id UUID NOT NULL REFERENCES environment_variants (id) ON DELETE CASCADE,
                source TEXT NOT NULL,
                envelope TEXT,
                assigned_at TIMESTAMPTZ NOT NULL,
                PRIMARY KEY (org_id, member_id, variable)
            );
            CREATE INDEX environment_assignments_variant ON environment_assignments (org_id, variant_id);
            CREATE TABLE environment_exclusions (
                org_id UUID NOT NULL,
                member_id UUID NOT NULL,
                variable TEXT NOT NULL,
                PRIMARY KEY (org_id, member_id, variable)
            );
        """.trimIndent() + "\n" + tenantTableSql("environment_variants") + "\n" +
            tenantTableSql("environment_assignments") + "\n" + tenantTableSql("environment_exclusions"),
    ),
)
