// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.importer.infrastructure

import run.ostrom.server.importer.domain.ImportProvenanceRepository
import run.ostrom.server.importer.domain.ImportRecord
import run.ostrom.server.importer.domain.ImportRecordKind
import run.ostrom.server.importer.domain.MAX_IMPORT_RECORDS
import run.ostrom.server.secrets.infrastructure.fetchInOrg
import run.ostrom.server.secrets.infrastructure.inTenant
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlin.uuid.Uuid

/**
 * [ImportProvenanceRepository] on PostgreSQL (table of [ImportMigrations]).
 *
 * @param driver The database.
 */
class ImportProvenanceRepositoryImpl(private val driver: Driver) : ImportProvenanceRepository {
    override suspend fun list(orgId: Uuid): List<ImportRecord> = driver.fetchInOrg(
        orgId,
        Statement.create(
            "SELECT kind, source_key, ostrom_name, ostrom_id FROM import_provenance WHERE org_id = :org_id " +
                "ORDER BY kind = 'SECRET', source_key LIMIT :limit",
        )
            .bind("org_id", orgId)
            .bind("limit", MAX_IMPORT_RECORDS),
    ).getOrThrow().map { row ->
        ImportRecord(
            kind = ImportRecordKind.valueOf(row.get("kind").asString()),
            sourceKey = row.get("source_key").asString(),
            ostromName = row.get("ostrom_name").asString(),
            ostromId = row.get("ostrom_id").asUuid(),
        )
    }

    override suspend fun record(orgId: Uuid, record: ImportRecord) {
        driver.inTenant(orgId) {
            execute(
                Statement.create(
                    "DELETE FROM import_provenance WHERE org_id = :org_id AND kind = :kind " +
                        "AND (source_key = :source_key OR ostrom_id = :ostrom_id)",
                )
                    .bind("org_id", orgId)
                    .bind("kind", record.kind.name)
                    .bind("source_key", record.sourceKey)
                    .bind("ostrom_id", record.ostromId),
            ).getOrThrow()
            execute(
                Statement.create(
                    "INSERT INTO import_provenance (org_id, kind, ostrom_id, source_key, ostrom_name) " +
                        "VALUES (:org_id, :kind, :ostrom_id, :source_key, :ostrom_name)",
                )
                    .bind("org_id", orgId)
                    .bind("kind", record.kind.name)
                    .bind("ostrom_id", record.ostromId)
                    .bind("source_key", record.sourceKey)
                    .bind("ostrom_name", record.ostromName),
            ).getOrThrow()
        }
    }
}
