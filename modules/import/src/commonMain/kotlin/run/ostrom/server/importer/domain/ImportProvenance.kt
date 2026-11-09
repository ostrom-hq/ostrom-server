// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.importer.domain

import kotlin.uuid.Uuid

/** What the import created in an org and keeps track of. */
enum class ImportRecordKind {
    /** An MCP server. */
    SERVER,

    /** An org or personal secret. */
    SECRET,
}

/**
 * Something the import created, so that a later run updates it and nothing an admin created by hand under the same
 * name: an object of the org is the import's only while its id is recorded here.
 *
 * @property kind What it is.
 * @property sourceKey What it comes from in TeamCode: the variant id for a server, `<variant>` for a shared credential,
 *   `<variant>/<VARIABLE>` for a command's variable, `<variant>/member/<Ostrom member>` for a member's own.
 * @property ostromName Its name in Ostrom when it was recorded (a server keeps it across runs).
 * @property ostromId Its id in Ostrom.
 */
data class ImportRecord(val kind: ImportRecordKind, val sourceKey: String, val ostromName: String, val ostromId: Uuid)

/** The import's own table: what it created, per org. */
interface ImportProvenanceRepository {
    /**
     * Lists what the import created in an org.
     *
     * @param orgId The org.
     *
     * @return The records, at most [MAX_IMPORT_RECORDS]: past it, an object reads as not imported and is left alone.
     */
    suspend fun list(orgId: Uuid): List<ImportRecord>

    /**
     * Records an object, replacing an earlier record of the same source or of the same object.
     *
     * @param orgId The org.
     * @param record The record.
     */
    suspend fun record(orgId: Uuid, record: ImportRecord)
}

/** The most records an import reads: 64 servers, their variables and every member's own credential. */
const val MAX_IMPORT_RECORDS: Int = 10_000
