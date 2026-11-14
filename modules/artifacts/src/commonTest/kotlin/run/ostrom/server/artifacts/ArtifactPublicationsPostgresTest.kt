// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.ArtifactPublicationsRepository
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageRepository
import run.ostrom.server.artifacts.domain.storage.WhoCanPublish
import run.ostrom.server.artifacts.fakes.ALICE
import run.ostrom.server.artifacts.fakes.ORG_A
import run.ostrom.server.artifacts.fakes.ORG_B
import run.ostrom.server.artifacts.fakes.activateArtifacts
import run.ostrom.server.artifacts.fakes.testIdentities
import run.ostrom.server.artifacts.infrastructure.PublicationQueries
import run.ostrom.server.artifacts.infrastructure.fetchInOrg
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Against a real PostgreSQL: the publications of the last hour, who publishes, the routes' refusals, the plan. */
class ArtifactPublicationsPostgresTest {
    private val t0 = Instant.parse("2026-11-14T10:00:00Z")

    @Test
    fun aMembersPublicationsAreCountedInTheirOrganizationOnly() = serverTest(listOf(ArtifactsModule)) { server ->
        val publications = server.koin.get<ArtifactPublicationsRepository>()
        val member = ALICE.toString()
        for (minutes in listOf(30, 10, 50)) publications.record(ORG_A, member, Uuid.random(), t0 - minutes.minutes)
        publications.record(ORG_A, "someone-else", Uuid.random(), t0)
        publications.record(ORG_B, member, Uuid.random(), t0)
        val since = t0 - 40.minutes
        assertEquals(listOf(t0 - 30.minutes, t0 - 10.minutes), publications.since(ORG_A, member, since, 5))
        assertEquals(listOf(t0 - 30.minutes), publications.since(ORG_A, member, since, 1))
        assertEquals(listOf(t0), publications.since(ORG_B, member, since, 5))
    }

    @Test
    fun theRoutesRefuseAMemberWhenOnlyAdministratorsPublishAndPastTheHourlyLimit() =
        serverTest(listOf(ArtifactsModule), testIdentities) { server ->
            server.koin.activateArtifacts()
            val storage = server.koin.get<ArtifactStorageRepository>()
            suspend fun settle(who: WhoCanPublish, perHour: Int) {
                val settings = storage.overview(ORG_A).settings
                val quotas = settings.quotas.copy(publishesPerHour = perHour)
                storage.saveSettings(ORG_A, settings.copy(whoCanPublish = who, quotas = quotas), Clock.System.now())
            }
            suspend fun publish(token: String) = server.client.post("/api/v1/artifacts") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"title": "Report", "files": [{"path": "index.html", "content": "<p>hi</p>"}]}""")
            }
            settle(WhoCanPublish.ADMINS, perHour = 1)
            val forbidden = publish("alice")
            assertEquals(HttpStatusCode.Forbidden, forbidden.status)
            assertTrue("artifact_publish_forbidden" in forbidden.bodyAsText())
            assertEquals(HttpStatusCode.Created, publish("dave").status)
            val limited = publish("dave")
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertTrue("artifact_rate_limited" in limited.bodyAsText())
            assertNotNull(limited.headers[HttpHeaders.RetryAfter])
            settle(WhoCanPublish.EVERYONE, perHour = 2)
            assertEquals(HttpStatusCode.Created, publish("alice").status)
        }

    @Test
    fun theHourlyCountReadsItsIndex() = serverTest(listOf(ArtifactsModule)) { server ->
        val fixtures = server.database.superuser
        // 20 000 publications of 2 000 organizations and their members.
        fixtures.execute(
            "INSERT INTO artifacts_publications (org_id, member_id, artifact_id, published_at) " +
                "SELECT CAST(md5('o' || (i % 2000)) AS uuid), 'm' || (i % 7), CAST(md5('a' || i) AS uuid), " +
                "now() - (i || ' seconds')::interval FROM generate_series(0, 19999) AS i",
        ).getOrThrow()
        fixtures.execute("VACUUM ANALYZE artifacts_publications").getOrThrow()
        val statement = Statement.create("EXPLAIN (ANALYZE, BUFFERS) ${PublicationQueries.SINCE}")
            .bind("org_id", ORG_A).bind("member", ALICE.toString()).bind("since", t0).bind("limit", 60)
        val plan = server.driver.fetchInOrg(ORG_A, statement).getOrThrow()
            .joinToString("\n") { it.get("QUERY PLAN").asString() }
        assertFalse("Seq Scan on artifacts_publications" in plan, "The count scans the publications:\n$plan")
    }
}
