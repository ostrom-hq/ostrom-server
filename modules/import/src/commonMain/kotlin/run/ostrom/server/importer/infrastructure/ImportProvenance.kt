// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer.infrastructure

import org.jetbrains.exposed.v1.core.Table

/**
 * The table of [ImportMigrations], as Exposed reads and writes it (the migration creates the schema): what each import
 * created or updated, by kind and source key (`import_provenance_source`, unique). A tenant table: read and written in
 * `JdbcDatabase.inOrg`, each query filtered on `org_id` too.
 */
internal object ImportProvenance : Table("import_provenance") {
    val orgId = uuid("org_id")
    val kind = text("kind")
    val ostromId = uuid("ostrom_id")
    val sourceKey = text("source_key")
    val ostromName = text("ostrom_name")

    override val primaryKey = PrimaryKey(orgId, kind, ostromId)
}
