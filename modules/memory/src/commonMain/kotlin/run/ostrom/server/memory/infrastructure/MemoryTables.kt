// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.UuidColumnType
import kotlin.uuid.Uuid

// The tables of [MemoryMigrations], as Exposed reads and writes them: the migrations create the schema, these objects
// only name its columns. Every table but the two functions' is a tenant table, read and written in
// `JdbcDatabase.inOrg`, each query filtered on `org_id` too.

/** The entries: the organization's (`owner_id` empty) and each member's personal ones. */
internal object MemoryEntries : Table("memory_entries") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val scope = text("scope")
    val ownerId = text("owner_id")
    val title = text("title")
    val summary = text("summary")
    val content = text("content")
    val refs = text("refs")
    val status = text("status")
    val origin = text("origin")
    val authorId = text("author_id")
    val sessionId = text("session_id").nullable()
    val supersedesId = uuid("supersedes_id").nullable()
    val expiresAt = instant("expires_at").nullable()
    val version = integer("version")
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")
    val lastReadAt = instant("last_read_at").nullable()
    val readCount = integer("read_count")
    val proposalWaitId = uuid("proposal_wait_id").nullable()
    val supersededById = uuid("superseded_by_id").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** An entry's tags, repeating its organization, scope and owner. */
internal object MemoryTags : Table("memory_tags") {
    val memoryId = uuid("memory_id")
    val tag = text("tag")
    val orgId = uuid("org_id")
    val scope = text("scope")
    val ownerId = text("owner_id")

    override val primaryKey = PrimaryKey(memoryId, tag)
}

/** An organization's curation setting (off without a row) and what its last run did. */
internal object MemorySettings : Table("memory_settings") {
    val orgId = uuid("org_id")
    val curationEnabled = bool("curation_enabled")
    val lastRunAt = instant("last_run_at").nullable()
    val lastRunId = text("last_run_id").nullable()

    // Written by the database's default (0) on the first row, then by the run.
    val lastSuperseded = integer("last_superseded").databaseGenerated()
    val lastQueued = integer("last_queued").databaseGenerated()

    override val primaryKey = PrimaryKey(orgId)
}

/** When each scope of an organization was last curated. */
internal object MemoryCurationState : Table("memory_curation_state") {
    val orgId = uuid("org_id")
    val scope = text("scope")
    val ownerId = text("owner_id")
    val curatedAt = instant("curated_at")

    override val primaryKey = PrimaryKey(orgId, scope, ownerId)
}

/** The pairs of entries a person decides on. */
internal object MemoryReviews : Table("memory_reviews") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val scope = text("scope")
    val ownerId = text("owner_id")
    val olderId = uuid("older_id")
    val newerId = uuid("newer_id")
    val kind = text("kind")
    val reason = text("reason")
    val status = text("status")
    val createdAt = instant("created_at")
    val resolvedAt = instant("resolved_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * The rows of `memory_settling_orgs(after, limit)` (migration 2) and `memory_kept_orgs(after, limit)` (migration 3),
 * the `ostrom_router` functions that return the ids of the organizations a pass works on, read as a table:
 * `SELECT org_id FROM name(?, CAST(? AS INTEGER))`.
 *
 * @property function The function's name.
 */
internal sealed class MemoryOrgsFunction(private val function: String) : Table(function) {
    val orgId = uuid("org_id")

    /**
     * The function called with its arguments, to select from.
     *
     * @param after The last organization of the previous page; the first page when null.
     * @param limit At most this many rows.
     */
    fun of(after: Uuid?, limit: Int): ColumnSet = Call(function, after, limit)

    private class Call(
        private val function: String,
        private val after: Uuid?,
        private val limit: Int,
    ) : Table(function) {
        override fun describe(s: Transaction, queryBuilder: QueryBuilder): Unit = queryBuilder {
            append(function)
            append("(")
            if (after == null) append("CAST(NULL AS UUID)") else registerArgument(UuidColumnType(), after)
            append(", CAST(")
            registerArgument(IntegerColumnType(), limit)
            append(" AS INTEGER))")
        }
    }
}

/** `memory_settling_orgs`: the organizations holding proposals or rejected entries. */
internal object MemorySettlingOrgs : MemoryOrgsFunction("memory_settling_orgs")

/** `memory_kept_orgs`: the organizations holding entries. */
internal object MemoryKeptOrgs : MemoryOrgsFunction("memory_kept_orgs")
