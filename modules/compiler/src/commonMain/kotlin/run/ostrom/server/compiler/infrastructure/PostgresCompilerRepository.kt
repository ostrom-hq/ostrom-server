// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.infrastructure

import run.ostrom.core.api.models.PackageEnvelope
import run.ostrom.server.compiler.domain.models.PackageAudit
import run.ostrom.server.compiler.domain.models.PackagePin
import run.ostrom.server.compiler.domain.models.StoredPackage
import run.ostrom.server.compiler.domain.repositories.CompilerRepository
import run.ostrom.server.compiler.domain.repositories.PackageListRepository
import run.ostrom.server.compiler.domain.repositories.WorkflowsRepository
import run.ostrom.server.compiler.domain.services.HarnessStamp
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.withOrganization
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.uuid.Uuid

/**
 * [CompilerRepository] on PostgreSQL through Exposed: harnesses, packages, pins and the audit here, workflows and
 * decisions in [PostgresWorkflowsRepository], the list of the packages in [PostgresPackageListRepository]. Every call
 * is one transaction with the organization set (`withOrganization`), read by the row level security policies of the
 * compiler tables, each statement filtered on `org_id` too.
 *
 * @param database The serving role's pool.
 */
class PostgresCompilerRepository(
    private val database: JdbcDatabase,
) : CompilerRepository,
    WorkflowsRepository by PostgresWorkflowsRepository(database),
    PackageListRepository by PostgresPackageListRepository(database) {
    override suspend fun harness(orgId: Uuid, repo: String?, digest: String): HarnessStamp? =
        database.withOrganization(orgId) { harnesses(orgId, repo, digest).firstOrNull() }

    override suspend fun saveHarness(orgId: Uuid, repo: String?, digest: String, stamp: HarnessStamp): HarnessStamp =
        database.withOrganization(orgId) {
            CompilerHarnesses.insertIgnore {
                it[this.orgId] = orgId
                it[repoKey] = keyOf(repo)
                it[this.digest] = digest
                it[version] = stamp.version
                it[receiptId] = stamp.receiptId
            }
            harnesses(orgId, repo, digest).first()
        }

    override suspend fun findPackage(orgId: Uuid, digest: String): StoredPackage? = database.withOrganization(orgId) {
        val p = CompilerPackages
        val row = p.select(PACKAGE_COLUMNS)
            .where { (p.orgId eq orgId) and ((p.contentDigest eq digest) or (p.digest eq digest)) }
            .limit(1)
            .firstOrNull()
        if (row == null) null else row.toPackage()
    }

    override suspend fun packages(orgId: Uuid): List<StoredPackage> = database.withOrganization(orgId) {
        val p = CompilerPackages
        p.select(PACKAGE_COLUMNS)
            .where { p.orgId eq orgId }
            .orderBy(p.compiledAt to SortOrder.DESC, p.contentDigest to SortOrder.ASC)
            .limit(MAX_ROWS)
            .map { it.toPackage() }
    }

    override suspend fun savePackage(orgId: Uuid, stored: StoredPackage): StoredPackage =
        database.withOrganization(orgId) {
            val p = CompilerPackages
            p.insertIgnore {
                it[this.orgId] = orgId
                it[contentDigest] = stored.envelope.contentDigest
                it[digest] = stored.envelope.digest
                it[version] = stored.envelope.version
                it[repo] = stored.envelope.repo
                it[compiledAt] = stored.compiledAt
                it[counts] = json.encodeToString(countsSerializer, stored.counts)
                it.revocable(stored)
            }
            p.select(PACKAGE_COLUMNS)
                .where { (p.orgId eq orgId) and (p.contentDigest eq stored.envelope.contentDigest) }
                .single()
                .toPackage()
        }

    override suspend fun updatePackage(orgId: Uuid, stored: StoredPackage) {
        database.withOrganization(orgId) {
            val p = CompilerPackages
            p.update({ (p.orgId eq orgId) and (p.contentDigest eq stored.envelope.contentDigest) }) {
                it.revocable(stored)
            }
        }
    }

    override suspend fun pin(orgId: Uuid, repo: String?): PackagePin? = database.withOrganization(orgId) {
        val pin = CompilerPins
        val row = pin.select(pin.contentDigest, pin.pinnedBy, pin.pinnedAt)
            .where { (pin.orgId eq orgId) and (pin.repoKey eq keyOf(repo)) }
            .firstOrNull()
        if (row == null) null else PackagePin(row[pin.contentDigest], row[pin.pinnedBy], row[pin.pinnedAt])
    }

    override suspend fun setPin(orgId: Uuid, repo: String?, pin: PackagePin?) {
        database.withOrganization(orgId) {
            val t = CompilerPins
            if (pin == null) {
                t.deleteWhere { (t.orgId eq orgId) and (t.repoKey eq keyOf(repo)) }
            } else {
                // On conflict, every column but the key takes the new pin's value (Exposed's default update).
                t.upsert(t.orgId, t.repoKey) {
                    it[t.orgId] = orgId
                    it[t.repoKey] = keyOf(repo)
                    it[t.contentDigest] = pin.contentDigest
                    it[t.pinnedBy] = pin.by
                    it[t.pinnedAt] = pin.at
                }
            }
        }
    }

    override suspend fun audit(orgId: Uuid, line: PackageAudit) {
        database.withOrganization(orgId) {
            CompilerAudit.insert {
                it[this.orgId] = orgId
                it[id] = line.id
                it[at] = line.at
                it[event] = line.event
                it[actor] = line.actor
                it[subject] = line.subject
                it[meta] = line.meta.toString()
            }
        }
    }

    override suspend fun auditLog(orgId: Uuid, limit: Int): List<PackageAudit> = database.withOrganization(orgId) {
        val a = CompilerAudit
        a.select(a.id, a.at, a.event, a.actor, a.subject, a.meta)
            .where { a.orgId eq orgId }
            .orderBy(a.seq to SortOrder.DESC)
            .limit(limit)
            .map {
                PackageAudit(
                    id = it[a.id],
                    at = it[a.at],
                    event = it[a.event],
                    actor = it[a.actor],
                    subject = it[a.subject],
                    meta = json.parseToJsonElement(it[a.meta]).jsonObject,
                )
            }
    }
}

private fun harnesses(orgId: Uuid, repo: String?, digest: String): List<HarnessStamp> {
    val h = CompilerHarnesses
    return h.select(h.version, h.receiptId)
        .where { (h.orgId eq orgId) and (h.repoKey eq keyOf(repo)) and (h.digest eq digest) }
        .map { HarnessStamp(it[h.version], it[h.receiptId]) }
}

// What an update of a package replaces: its envelope and its revocation.
private fun UpdateBuilder<*>.revocable(stored: StoredPackage) {
    val p = CompilerPackages
    this[p.envelope] = json.encodeToString(PackageEnvelope.serializer(), stored.envelope)
    this[p.revoked] = stored.revoked
    this[p.revokedBy] = stored.revokedBy
    this[p.revokeReason] = stored.revokeReason
}

private fun ResultRow.toPackage(): StoredPackage {
    val p = CompilerPackages
    return StoredPackage(
        envelope = json.decodeFromString(PackageEnvelope.serializer(), this[p.envelope])
            .copy(revoked = this[p.revoked]),
        compiledAt = this[p.compiledAt],
        counts = json.decodeFromString(countsSerializer, this[p.counts]),
        revokedBy = this[p.revokedBy],
        revokeReason = this[p.revokeReason],
    )
}

// The organization's own pin and harnesses live under `*`, as in the prototype.
private fun keyOf(repo: String?): String = repo ?: "*"

private const val MAX_ROWS = 1000

private val PACKAGE_COLUMNS = CompilerPackages.run {
    listOf(contentDigest, digest, version, repo, compiledAt, envelope, counts, revoked, revokedBy, revokeReason)
}

// Top-level, not in a companion: a companion property has a getter nothing calls, which Kover counts.
private val json = Json { ignoreUnknownKeys = true }
private val countsSerializer = MapSerializer(String.serializer(), Int.serializer())
