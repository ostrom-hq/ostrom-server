// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.infrastructure

import run.ostrom.server.artifacts.domain.ArtifactPublicationsRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [ArtifactPublicationsRepository] on `artifacts_publications`, under the organization's Row-Level Security.
 *
 * @param driver The database.
 */
internal class ArtifactPublicationsRepositoryImpl(private val driver: Driver) : ArtifactPublicationsRepository {
    override suspend fun since(orgId: Uuid, memberId: String, since: Instant, limit: Int): List<Instant> =
        driver.fetchInOrg(
            orgId,
            Statement.create(PublicationQueries.SINCE).bind("org_id", orgId).bind("member", memberId)
                .bind("since", since).bind("limit", limit),
        ).getOrThrow().map { it.get("published_at").asInstant() }

    override suspend fun record(orgId: Uuid, memberId: String, artifactId: Uuid, at: Instant) {
        driver.inTenant(orgId) {
            execute(
                Statement.create(
                    "INSERT INTO artifacts_publications (org_id, member_id, artifact_id, published_at) " +
                        "VALUES (:org_id, :member, :artifact, :at)",
                ).bind("org_id", orgId).bind("member", memberId).bind("artifact", artifactId).bind("at", at),
            ).getOrThrow()
        }
    }
}

/** The request path's read of the publications, for its plan test. */
internal object PublicationQueries {
    /** A member's publications of the last hour, oldest first: `artifacts_publications_member`. */
    const val SINCE = "SELECT published_at FROM artifacts_publications WHERE org_id = :org_id AND " +
        "member_id = :member AND published_at > :since ORDER BY published_at LIMIT :limit"
}
