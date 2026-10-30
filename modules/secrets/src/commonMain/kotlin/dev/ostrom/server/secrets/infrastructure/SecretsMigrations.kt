package dev.ostrom.server.secrets.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

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
)
