// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler.infrastructure

import run.ostrom.server.compiler.domain.models.ListedPackage
import run.ostrom.server.compiler.domain.repositories.PackageListRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

// One repository's packages come in the order of `compiler_packages_by_repo`. The whole organization's are sorted after
// a bitmap scan: in the "C" collation, the same order for ISO instants and hex digests, a fraction of the locale's cost
// (CompilerReadsBenchTest: the sort of 1000 rows was most of the read).
private const val INDEX_ORDER = " ORDER BY p.compiled_at DESC, p.content_digest"
private const val WHOLE_ORDER = " ORDER BY p.compiled_at COLLATE \"C\" DESC, p.content_digest COLLATE \"C\""

/**
 * [PackageListRepository] on PostgreSQL: the summary columns of `compiler_packages` and the pin of each, joined on
 * `compiler_pins` in one query (`compiler_packages_recent`, or `compiler_packages_by_repo` for one repository), never
 * the envelope, which holds the whole compiled payload.
 *
 * @param driver The connection pool.
 */
class PostgresPackageListRepository(private val driver: Driver) : PackageListRepository {
    override suspend fun listPackages(orgId: Uuid, repo: String?): List<ListedPackage> = driver.inOrg(orgId) {
        val statement = Statement.create(
            "SELECT p.version, p.digest, p.content_digest, p.repo, p.compiled_at, p.counts, p.revoked, " +
                "p.revoked_by, p.revoke_reason, pin.content_digest IS NOT NULL AS pinned FROM compiler_packages p " +
                "LEFT JOIN compiler_pins pin ON pin.org_id = p.org_id AND pin.repo_key = COALESCE(p.repo, '*') " +
                "AND pin.content_digest = p.content_digest WHERE p.org_id = :org" +
                (if (repo == null) WHOLE_ORDER else " AND p.repo = :repo$INDEX_ORDER") + " LIMIT $MAX_ROWS",
        ).bind("org", orgId)
        repo?.let { statement.bind("repo", it) }
        fetch(statement).map {
            ListedPackage(
                version = it.get("version").asString(),
                digest = it.get("digest").asString(),
                contentDigest = it.get("content_digest").asString(),
                repo = it.get("repo").asStringOrNull(),
                compiledAt = it.get("compiled_at").asString(),
                counts = listJson.decodeFromString(listCounts, it.get("counts").asString()),
                revoked = it.get("revoked").asBoolean(),
                revokedBy = it.get("revoked_by").asStringOrNull(),
                revokeReason = it.get("revoke_reason").asStringOrNull(),
                pinned = it.get("pinned").asBoolean(),
            )
        }
    }

    private companion object {
        const val MAX_ROWS = 1000
    }
}

// Top-level, not in the private companion: a companion property has a getter nothing calls, which Kover counts.
private val listJson = Json { ignoreUnknownKeys = true }
private val listCounts = MapSerializer(String.serializer(), Int.serializer())
