// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.Artifact
import run.ostrom.server.artifacts.domain.ArtifactAccess
import run.ostrom.server.artifacts.domain.ArtifactFile
import run.ostrom.server.artifacts.domain.ArtifactVersion
import run.ostrom.server.artifacts.domain.ArtifactsRepository
import run.ostrom.server.artifacts.fakes.ALICE
import run.ostrom.server.artifacts.fakes.ORG_A
import run.ostrom.server.artifacts.fakes.ORG_B
import run.ostrom.server.artifacts.infrastructure.ArtifactsRoutes
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
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
        repository.create(artifact, version)
        val database = server.koin.get<JdbcDatabase>()

        // The serving role, without an organization: no route at all.
        assertEquals(0L, database.query { ArtifactsRoutes.selectAll().count() })
        assertEquals(1L, database.inOrg(ORG_A) { ArtifactsRoutes.selectAll().count() })
        assertEquals(0L, database.inOrg(ORG_B) { ArtifactsRoutes.selectAll().count() })

        // Organization B can neither move A's link to itself nor write a route in A's name.
        val stolen = database.inOrg(ORG_B) {
            ArtifactsRoutes.update({ ArtifactsRoutes.artifactId eq artifact.id }) { it[linkSlug] = "stolen" }
        }
        assertEquals(0, stolen)
        assertFailsWith<Exception> {
            database.inOrg(ORG_B) {
                ArtifactsRoutes.insert {
                    it[artifactId] = Uuid.random()
                    it[orgId] = ORG_A
                    it[linkSlug] = "forged"
                }
            }
        }

        // The content origin's two reads across organizations still find it, and only it.
        assertEquals(artifact, repository.getById(artifact.id))
        assertEquals(artifact, repository.getByLinkSlug("slug-a"))
        assertEquals(null, repository.getByLinkSlug("stolen"))
        assertEquals(null, repository.getById(Uuid.random()))
    }
}
