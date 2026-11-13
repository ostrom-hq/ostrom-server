// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.secrets.infrastructure.TENANT_ROLE_SQL
import run.ostrom.server.secrets.infrastructure.tenantTableSql

/** The schema of the import module: what it created per org, confined to its org by row-level security. */
val ImportMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        version = 1,
        name = "create_import_provenance",
        sql = listOf(
            """
            CREATE TABLE import_provenance (
                org_id UUID NOT NULL,
                kind TEXT NOT NULL,
                ostrom_id UUID NOT NULL,
                source_key TEXT NOT NULL,
                ostrom_name TEXT NOT NULL,
                PRIMARY KEY (org_id, kind, ostrom_id)
            );
            CREATE UNIQUE INDEX import_provenance_source ON import_provenance (org_id, kind, source_key);
            """.trimIndent(),
            TENANT_ROLE_SQL,
            tenantTableSql("import_provenance"),
        ).joinToString("\n"),
    ),
)
