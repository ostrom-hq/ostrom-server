// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.Artifact
import run.ostrom.server.artifacts.domain.ArtifactAccess
import run.ostrom.server.artifacts.domain.ArtifactFile
import run.ostrom.server.artifacts.domain.ArtifactVersion
import run.ostrom.server.artifacts.domain.ArtifactsRepository
import run.ostrom.server.artifacts.fakes.ALICE
import run.ostrom.server.artifacts.fakes.ORG_A
import run.ostrom.server.artifacts.fakes.ORG_B
import run.ostrom.server.artifacts.infrastructure.inTenant
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * `artifacts_routes` under the serving role (RLS audit R2 and R3): a request reads and writes only its organization's
 * routes, and the content origin still finds any artifact by its id or link through the `ostrom_router` functions.
 */
class ArtifactsRoutesTenancyPostgresTest {
    private val t0 = Instant.parse("2026-11-02T10:00:00Z")

    private fun published(slug: String): Pair<Artifact, ArtifactVersion> {
        val artifact = Artifact(Uuid.random(), ORG_A, ALICE.toString(), "T", ArtifactAccess.LINK, 1, slug, t0, t0)
        val file = ArtifactFile("a.txt", "sha-a", 3, "text/plain; charset=utf-8")
        return artifact to ArtifactVersion(artifact.id, 1, "a.txt", listOf(file), t0, artifact.ownerId)
    }

    @Test
    fun anotherOrganizationNeitherReadsNorRewritesARoute() = serverTest(listOf(ArtifactsModule)) { server ->
        val repository = server.koin.get<ArtifactsRepository>()
        val (artifact, version) = published("slug-a")
        repository.create(artifact, version, mapOf("sha-a" to byteArrayOf(1)))
        val count = Statement.create("SELECT COUNT(*) AS n FROM artifacts_routes")

        // The test server's driver is the serving role: without an organization, no route at all.
        assertEquals(0L, server.driver.fetchAll(count).getOrThrow().first().get("n").asLong())
        assertEquals(1L, server.driver.inTenant(ORG_A) { fetchAll(count).getOrThrow().first().get("n").asLong() })
        assertEquals(0L, server.driver.inTenant(ORG_B) { fetchAll(count).getOrThrow().first().get("n").asLong() })

        // Organization B can neither move A's link to itself nor write a route in A's name.
        val stolen = server.driver.inTenant(ORG_B) {
            execute(
                Statement.create("UPDATE artifacts_routes SET link_slug = 'stolen' WHERE artifact_id = :id")
                    .bind("id", artifact.id),
            ).getOrThrow()
        }
        assertEquals(0L, stolen)
        assertFailsWith<Exception> {
            server.driver.inTenant(ORG_B) {
                execute(
                    Statement.create(
                        "INSERT INTO artifacts_routes (artifact_id, org_id, link_slug) VALUES (:id, :org, 'forged')",
                    ).bind("id", Uuid.random()).bind("org", ORG_A),
                ).getOrThrow()
            }
        }

        // The content origin's two reads across organizations still find it, and only it.
        assertEquals(artifact, repository.getById(artifact.id))
        assertEquals(artifact, repository.getByLinkSlug("slug-a"))
        assertEquals(null, repository.getByLinkSlug("stolen"))
        assertEquals(null, repository.getById(Uuid.random()))
    }
}
