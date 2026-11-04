// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler.infrastructure

import dev.ostrom.core.api.models.PackageEnvelope
import dev.ostrom.server.compiler.domain.models.PackageAudit
import dev.ostrom.server.compiler.domain.models.PackagePin
import dev.ostrom.server.compiler.domain.models.StoredPackage
import dev.ostrom.server.compiler.domain.repositories.CompilerRepository
import dev.ostrom.server.compiler.domain.repositories.PackageListRepository
import dev.ostrom.server.compiler.domain.repositories.WorkflowsRepository
import dev.ostrom.server.compiler.domain.services.HarnessStamp
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.Uuid

/**
 * [CompilerRepository] on PostgreSQL (sqlx4k): harnesses, packages, pins and the audit here, workflows and decisions in
 * [PostgresWorkflowsRepository], the list of the packages in [PostgresPackageListRepository]. Every call is one
 * transaction that first sets `ostrom.org_id`, read by the row level security policies of the compiler tables.
 *
 * @param driver The connection pool.
 */
class PostgresCompilerRepository(
    private val driver: Driver,
) : CompilerRepository,
    WorkflowsRepository by PostgresWorkflowsRepository(driver),
    PackageListRepository by PostgresPackageListRepository(driver) {
    override suspend fun harness(orgId: Uuid, repo: String?, digest: String): HarnessStamp? = driver.inOrg(orgId) {
        harnesses(orgId, repo, digest).firstOrNull()
    }

    override suspend fun saveHarness(orgId: Uuid, repo: String?, digest: String, stamp: HarnessStamp): HarnessStamp =
        driver.inOrg(orgId) {
            execute(
                Statement.create(
                    "INSERT INTO compiler_harnesses (org_id, repo_key, digest, version, receipt_id) " +
                        "VALUES (:org, :repo, :digest, :version, :receipt) ON CONFLICT DO NOTHING",
                ).bind("org", orgId).bind("repo", keyOf(repo)).bind("digest", digest)
                    .bind("version", stamp.version).bind("receipt", stamp.receiptId),
            )
            harnesses(orgId, repo, digest).first()
        }

    override suspend fun findPackage(orgId: Uuid, digest: String): StoredPackage? = driver.inOrg(orgId) {
        fetch(
            Statement.create(
                "SELECT $PACKAGE_COLUMNS FROM compiler_packages WHERE org_id = :org " +
                    "AND (content_digest = :digest OR digest = :digest) LIMIT 1",
            ).bind("org", orgId).bind("digest", digest),
        ).firstOrNull()?.toPackage()
    }

    override suspend fun packages(orgId: Uuid): List<StoredPackage> = driver.inOrg(orgId) {
        fetch(
            Statement.create(
                "SELECT $PACKAGE_COLUMNS FROM compiler_packages WHERE org_id = :org " +
                    "ORDER BY compiled_at DESC, content_digest LIMIT $MAX_ROWS",
            ).bind("org", orgId),
        ).map { it.toPackage() }
    }

    override suspend fun savePackage(orgId: Uuid, stored: StoredPackage): StoredPackage = driver.inOrg(orgId) {
        execute(
            bindPackage(
                Statement.create(
                    "INSERT INTO compiler_packages ($PACKAGE_COLUMNS, org_id) VALUES (:content_digest, :digest, " +
                        ":version, :repo, :compiled_at, :envelope, :counts, :revoked, :revoked_by, :revoke_reason, " +
                        ":org) ON CONFLICT DO NOTHING",
                ),
                orgId,
                stored,
            ),
        )
        fetch(
            Statement.create(
                "SELECT $PACKAGE_COLUMNS FROM compiler_packages WHERE org_id = :org AND content_digest = :digest",
            ).bind("org", orgId).bind("digest", stored.envelope.contentDigest),
        ).first().toPackage()
    }

    override suspend fun updatePackage(orgId: Uuid, stored: StoredPackage) {
        driver.inOrg(orgId) {
            execute(
                Statement.create(
                    "UPDATE compiler_packages SET envelope = :envelope, revoked = :revoked, " +
                        "revoked_by = :revoked_by, revoke_reason = :revoke_reason " +
                        "WHERE org_id = :org AND content_digest = :content_digest",
                ).bind("org", orgId)
                    .bind("content_digest", stored.envelope.contentDigest)
                    .bind("envelope", json.encodeToString(PackageEnvelope.serializer(), stored.envelope))
                    .bind("revoked", stored.revoked)
                    .bind("revoked_by", stored.revokedBy)
                    .bind("revoke_reason", stored.revokeReason),
            )
        }
    }

    override suspend fun pin(orgId: Uuid, repo: String?): PackagePin? = driver.inOrg(orgId) {
        fetch(
            Statement.create(
                "SELECT content_digest, pinned_by, pinned_at FROM compiler_pins " +
                    "WHERE org_id = :org AND repo_key = :repo",
            ).bind("org", orgId).bind("repo", keyOf(repo)),
        ).firstOrNull()?.let {
            PackagePin(
                contentDigest = it.get("content_digest").asString(),
                by = it.get("pinned_by").asString(),
                at = it.get("pinned_at").asInstant(),
            )
        }
    }

    override suspend fun setPin(orgId: Uuid, repo: String?, pin: PackagePin?) {
        driver.inOrg(orgId) {
            val statement = if (pin == null) {
                Statement.create("DELETE FROM compiler_pins WHERE org_id = :org AND repo_key = :repo")
            } else {
                Statement.create(
                    "INSERT INTO compiler_pins (org_id, repo_key, content_digest, pinned_by, pinned_at) " +
                        "VALUES (:org, :repo, :digest, :by, :at) ON CONFLICT (org_id, repo_key) DO UPDATE SET " +
                        "content_digest = EXCLUDED.content_digest, pinned_by = EXCLUDED.pinned_by, " +
                        "pinned_at = EXCLUDED.pinned_at",
                ).bind("digest", pin.contentDigest).bind("by", pin.by).bind("at", pin.at)
            }
            execute(statement.bind("org", orgId).bind("repo", keyOf(repo)))
        }
    }

    override suspend fun audit(orgId: Uuid, line: PackageAudit) {
        driver.inOrg(orgId) {
            execute(
                Statement.create(
                    "INSERT INTO compiler_audit (org_id, id, at, event, actor, subject, meta) " +
                        "VALUES (:org, :id, :at, :event, :actor, :subject, :meta)",
                ).bind("org", orgId).bind("id", line.id).bind("at", line.at).bind("event", line.event)
                    .bind("actor", line.actor).bind("subject", line.subject).bind("meta", line.meta.toString()),
            )
        }
    }

    override suspend fun auditLog(orgId: Uuid, limit: Int): List<PackageAudit> = driver.inOrg(orgId) {
        fetch(
            Statement.create(
                "SELECT id, at, event, actor, subject, meta FROM compiler_audit WHERE org_id = :org " +
                    "ORDER BY seq DESC LIMIT :limit",
            ).bind("org", orgId).bind("limit", limit),
        ).map {
            PackageAudit(
                id = it.get("id").asString(),
                at = it.get("at").asString(),
                event = it.get("event").asString(),
                actor = it.get("actor").asString(),
                subject = it.get("subject").asString(),
                meta = json.parseToJsonElement(it.get("meta").asString()).jsonObject,
            )
        }
    }

    private companion object {
        const val MAX_ROWS = 1000
        const val PACKAGE_COLUMNS = "content_digest, digest, version, repo, compiled_at, envelope, counts, revoked, " +
            "revoked_by, revoke_reason"

        fun ResultSet.Row.toPackage() = StoredPackage(
            envelope = json.decodeFromString(PackageEnvelope.serializer(), get("envelope").asString())
                .copy(revoked = get("revoked").asBoolean()),
            compiledAt = get("compiled_at").asString(),
            counts = json.decodeFromString(counts, get("counts").asString()),
            revokedBy = get("revoked_by").asStringOrNull(),
            revokeReason = get("revoke_reason").asStringOrNull(),
        )

        fun bindPackage(statement: Statement, orgId: Uuid, stored: StoredPackage): Statement = statement
            .bind("org", orgId)
            .bind("content_digest", stored.envelope.contentDigest)
            .bind("digest", stored.envelope.digest)
            .bind("version", stored.envelope.version)
            .bind("repo", stored.envelope.repo)
            .bind("compiled_at", stored.compiledAt)
            .bind("envelope", json.encodeToString(PackageEnvelope.serializer(), stored.envelope))
            .bind("counts", json.encodeToString(counts, stored.counts))
            .bind("revoked", stored.revoked)
            .bind("revoked_by", stored.revokedBy)
            .bind("revoke_reason", stored.revokeReason)
    }
}

private suspend fun CompilerTx.harnesses(orgId: Uuid, repo: String?, digest: String): List<HarnessStamp> = fetch(
    Statement.create(
        "SELECT version, receipt_id FROM compiler_harnesses " +
            "WHERE org_id = :org AND repo_key = :repo AND digest = :digest",
    ).bind("org", orgId).bind("repo", keyOf(repo)).bind("digest", digest),
).map { HarnessStamp(it.get("version").asString(), it.get("receipt_id").asStringOrNull()) }

// The organization's own pin and harnesses live under `*`, as in the prototype.
private fun keyOf(repo: String?): String = repo ?: "*"

// Top-level, not in the private companion: a companion property has a getter nothing calls, which Kover counts.
private val json = Json { ignoreUnknownKeys = true }
private val counts = MapSerializer(String.serializer(), Int.serializer())
