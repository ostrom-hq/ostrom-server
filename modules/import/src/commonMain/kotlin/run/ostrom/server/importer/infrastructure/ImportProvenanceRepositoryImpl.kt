// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer.infrastructure

import run.ostrom.server.importer.domain.ImportProvenanceRepository
import run.ostrom.server.importer.domain.ImportRecord
import run.ostrom.server.importer.domain.ImportRecordKind
import run.ostrom.server.importer.domain.MAX_IMPORT_RECORDS
import run.ostrom.server.kernel.database.JdbcDatabase
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/**
 * [ImportProvenanceRepository] on `import_provenance` (table of [ImportMigrations]): every statement filters on its
 * organization and runs in it (`inOrg`), under row-level security.
 *
 * @param database The serving role's pool.
 */
class ImportProvenanceRepositoryImpl(private val database: JdbcDatabase) : ImportProvenanceRepository {
    override suspend fun list(orgId: Uuid): List<ImportRecord> = database.inOrg(orgId) {
        val p = ImportProvenance
        // The other kinds first, then the secrets, each by source key.
        p.select(p.kind, p.sourceKey, p.ostromName, p.ostromId)
            .where { p.orgId eq orgId }
            .orderBy((p.kind eq ImportRecordKind.SECRET.name) to SortOrder.ASC, p.sourceKey to SortOrder.ASC)
            .limit(MAX_IMPORT_RECORDS)
            .map { row ->
                ImportRecord(
                    kind = ImportRecordKind.valueOf(row[p.kind]),
                    sourceKey = row[p.sourceKey],
                    ostromName = row[p.ostromName],
                    ostromId = row[p.ostromId],
                )
            }
    }

    override suspend fun record(orgId: Uuid, record: ImportRecord) {
        database.inOrg(orgId) {
            val p = ImportProvenance
            // One record per source key and per object of a kind: the one it replaces goes first.
            p.deleteWhere {
                (p.orgId eq orgId) and (p.kind eq record.kind.name) and
                    ((p.sourceKey eq record.sourceKey) or (p.ostromId eq record.ostromId))
            }
            p.insert {
                it[this.orgId] = orgId
                it[kind] = record.kind.name
                it[ostromId] = record.ostromId
                it[sourceKey] = record.sourceKey
                it[ostromName] = record.ostromName
            }
        }
    }
}
