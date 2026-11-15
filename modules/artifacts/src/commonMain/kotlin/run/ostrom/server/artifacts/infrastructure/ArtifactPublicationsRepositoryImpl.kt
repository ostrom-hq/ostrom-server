// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.infrastructure

import run.ostrom.server.artifacts.domain.ArtifactPublicationsRepository
import run.ostrom.server.kernel.database.JdbcDatabase
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [ArtifactPublicationsRepository] on `artifacts_publications`, under the organization's Row-Level Security.
 *
 * @param database The serving role's pool.
 */
internal class ArtifactPublicationsRepositoryImpl(private val database: JdbcDatabase) :
    ArtifactPublicationsRepository {
    override suspend fun since(orgId: Uuid, memberId: String, since: Instant, limit: Int): List<Instant> =
        database.inOrg(orgId) {
            publicationsSince(orgId, memberId, since, limit).map { it[ArtifactsPublications.publishedAt] }
        }

    override suspend fun record(orgId: Uuid, memberId: String, artifactId: Uuid, at: Instant) {
        database.inOrg(orgId) {
            ArtifactsPublications.insert {
                it[this.orgId] = orgId
                it[this.memberId] = memberId
                it[this.artifactId] = artifactId
                it[publishedAt] = at
            }
        }
    }
}

/** A member's publications of the last hour, oldest first: `artifacts_publications_member`. */
internal fun publicationsSince(orgId: Uuid, memberId: String, since: Instant, limit: Int): Query {
    val p = ArtifactsPublications
    return p.select(p.publishedAt)
        .where { (p.orgId eq orgId) and (p.memberId eq memberId) and (p.publishedAt greater since) }
        .orderBy(p.publishedAt)
        .limit(limit)
}
