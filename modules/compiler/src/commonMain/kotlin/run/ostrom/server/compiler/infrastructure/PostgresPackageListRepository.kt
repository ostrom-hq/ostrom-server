// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.infrastructure

import run.ostrom.server.compiler.domain.models.ListedPackage
import run.ostrom.server.compiler.domain.repositories.PackageListRepository
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.withOrganization
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/**
 * [PackageListRepository] on PostgreSQL through Exposed: the summary columns of `compiler_packages` and the pin of
 * each, joined on `compiler_pins` in one query (`compiler_packages_recent`, or `compiler_packages_by_repo` for one
 * repository), never the envelope, which holds the whole compiled payload.
 *
 * @param database The serving role's pool.
 */
class PostgresPackageListRepository(private val database: JdbcDatabase) : PackageListRepository {
    override suspend fun listPackages(orgId: Uuid, repo: String?): List<ListedPackage> =
        database.withOrganization(orgId) {
            val p = CompilerPackages
            val pin = CompilerPins
            val pinned = pin.contentDigest.isNotNull()
            val joined = p.join(pin, JoinType.LEFT, additionalConstraint = {
                (pin.orgId eq p.orgId) and (pin.repoKey eq coalesce(p.repo, stringLiteral(ORG_KEY))) and
                    (pin.contentDigest eq p.contentDigest)
            })
            val columns = listOf(
                p.version, p.digest, p.contentDigest, p.repo, p.compiledAt, p.counts, p.revoked, p.revokedBy,
                p.revokeReason, pinned,
            )
            // One repository's packages come in the order of `compiler_packages_by_repo`. The whole organization's
            // are sorted after a bitmap scan: in the "C" collation, the same order for ISO instants and hex digests, a
            // fraction of the locale's cost (CompilerReadsBenchTest: the sort of 1000 rows was most of the read).
            val query = if (repo == null) {
                joined.select(columns).where { p.orgId eq orgId }
                    .orderBy(InC(p.compiledAt) to SortOrder.DESC, InC(p.contentDigest) to SortOrder.ASC)
            } else {
                joined.select(columns).where { (p.orgId eq orgId) and (p.repo eq repo) }
                    .orderBy(p.compiledAt to SortOrder.DESC, p.contentDigest to SortOrder.ASC)
            }
            query.limit(MAX_ROWS).map {
                ListedPackage(
                    version = it[p.version],
                    digest = it[p.digest],
                    contentDigest = it[p.contentDigest],
                    repo = it[p.repo],
                    compiledAt = it[p.compiledAt],
                    counts = listJson.decodeFromString(listCounts, it[p.counts]),
                    revoked = it[p.revoked],
                    revokedBy = it[p.revokedBy],
                    revokeReason = it[p.revokeReason],
                    pinned = it[pinned],
                )
            }
        }
}

// A text column sorted in the "C" collation (`column COLLATE "C"`).
private class InC(private val column: Expression<String>) : Expression<String>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append(column)
        queryBuilder.append(" COLLATE \"C\"")
    }
}

// The key of the organization's own pin, for a package of no repository.
private const val ORG_KEY = "*"
private const val MAX_ROWS = 1000

// Top-level, not in a companion: a companion property has a getter nothing calls, which Kover counts.
private val listJson = Json { ignoreUnknownKeys = true }
private val listCounts = MapSerializer(String.serializer(), Int.serializer())
