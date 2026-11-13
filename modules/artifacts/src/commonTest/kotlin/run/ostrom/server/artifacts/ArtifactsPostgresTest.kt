// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.Artifact
import run.ostrom.server.artifacts.domain.ArtifactAccess
import run.ostrom.server.artifacts.domain.ArtifactFile
import run.ostrom.server.artifacts.domain.ArtifactVersion
import run.ostrom.server.artifacts.domain.ArtifactsRepository
import run.ostrom.server.artifacts.fakes.ALICE
import run.ostrom.server.artifacts.fakes.BOB
import run.ostrom.server.artifacts.fakes.CONTENT_ORIGIN
import run.ostrom.server.artifacts.fakes.DAVE
import run.ostrom.server.artifacts.fakes.ORG_A
import run.ostrom.server.artifacts.fakes.ORG_B
import run.ostrom.server.artifacts.fakes.VIEWER
import run.ostrom.server.artifacts.fakes.alice
import run.ostrom.server.artifacts.fakes.bob
import run.ostrom.server.artifacts.fakes.dave
import run.ostrom.server.artifacts.fakes.testIdentities
import run.ostrom.server.artifacts.presentation.ArtifactMembersResponse
import run.ostrom.server.artifacts.presentation.ArtifactResponse
import run.ostrom.server.artifacts.presentation.ArtifactTicketResponse
import run.ostrom.server.artifacts.presentation.ArtifactVersionResponse
import run.ostrom.server.artifacts.presentation.ArtifactVersionsResponse
import run.ostrom.server.artifacts.presentation.ArtifactsResponse
import run.ostrom.server.artifacts.presentation.FileDto
import run.ostrom.server.artifacts.presentation.PublishArtifactRequest
import run.ostrom.server.artifacts.presentation.PublishVersionRequest
import run.ostrom.server.artifacts.presentation.UpdateArtifactRequest
import run.ostrom.server.artifacts.infrastructure.inTenant
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import org.koin.dsl.module
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Against a real PostgreSQL: the repository's SQL, the routes, the content origin and the MCP server. */
@OptIn(ExperimentalAtomicApi::class)
class ArtifactsPostgresTest {
    private val t0 = Instant.parse("2026-10-30T10:00:00Z")

    private fun artifact(org: Uuid = ORG_A, owner: Uuid = ALICE, access: ArtifactAccess = ArtifactAccess.PERSONAL) =
        Artifact(Uuid.random(), org, owner.toString(), "T", access, 1, null, t0, t0)

    private fun version(artifact: Artifact, number: Int = 1, vararg files: Pair<String, String>) = ArtifactVersion(
        artifact.id,
        number,
        files.first().first,
        files.map { ArtifactFile(it.first, it.second, 3, "text/plain; charset=utf-8") },
        t0,
        artifact.ownerId,
    )

    @Test
    fun repositoryStoresVersionsBlobsAndShares() = serverTest(listOf(ArtifactsModule)) { server ->
        val repository = server.koin.get<ArtifactsRepository>()
        val mine = artifact().copy(linkSlug = "slug-1", access = ArtifactAccess.LINK)
        repository.create(mine, version(mine, 1, "a.txt" to "sha-a"), mapOf("sha-a" to byteArrayOf(0, 1, 2)))
        assertEquals(mine, repository.get(ORG_A, mine.id))
        assertNull(repository.get(ORG_B, mine.id))
        assertEquals(mine, repository.getById(mine.id))
        assertEquals(mine, repository.getByLinkSlug("slug-1"))
        assertNull(repository.getByLinkSlug("nope"))
        assertContentEquals(byteArrayOf(0, 1, 2), repository.blob(ORG_A, mine.id, "sha-a"))
        assertNull(repository.blob(ORG_A, mine.id, "sha-z"))

        val second = version(mine, 2, "a.txt" to "sha-a", "b.txt" to "sha-b")
        val updated = assertNotNull(
            repository.addVersion(ORG_A, second, mapOf("sha-b" to byteArrayOf(9)), t0 + 1.minutes),
        )
        assertEquals(2, updated.latestVersion)
        assertEquals(t0 + 1.minutes, updated.updatedAt)
        assertNull(repository.addVersion(ORG_A, second, emptyMap(), t0))
        assertNull(repository.addVersion(ORG_B, version(mine, 3, "a.txt" to "sha-a"), emptyMap(), t0))
        assertEquals(setOf("sha-a", "sha-b"), repository.blobDigests(ORG_A, mine.id))
        assertEquals(listOf(2, 1), repository.versions(ORG_A, mine.id).map { it.number })
        assertEquals(second, repository.version(ORG_A, mine.id, 2))
        assertNull(repository.version(ORG_A, mine.id, 3))

        repository.setMember(ORG_A, mine.id, BOB.toString(), share = true)
        repository.setMember(ORG_A, mine.id, BOB.toString(), share = true)
        repository.setMember(ORG_B, mine.id, "intruder", share = true)
        assertEquals(listOf(BOB.toString()), repository.members(ORG_A, mine.id))

        repository.updateSettings(updated.copy(title = "U", access = ArtifactAccess.TEAM, linkSlug = null))
        assertEquals("U", repository.get(ORG_A, mine.id)?.title)
        assertNull(repository.getByLinkSlug("slug-1"))

        assertFalse(repository.delete(ORG_B, mine.id))
        repository.setMember(ORG_B, mine.id, BOB.toString(), share = false)
        assertEquals(listOf(BOB.toString()), repository.members(ORG_A, mine.id))
        repository.setMember(ORG_A, mine.id, BOB.toString(), share = false)
        assertEquals(emptyList(), repository.members(ORG_A, mine.id))
        assertTrue(repository.delete(ORG_A, mine.id))
        assertNull(repository.blob(ORG_A, mine.id, "sha-a"))
        assertEquals(emptyList(), repository.versions(ORG_A, mine.id))
    }

    @Test
    fun anotherOrganizationsRowsAreNeitherSeenNorWritten() = serverTest(listOf(ArtifactsModule)) { server ->
        val repository = server.koin.get<ArtifactsRepository>()
        val mine = artifact()
        repository.create(mine, version(mine, 1, "a.txt" to "sha-a"), mapOf("sha-a" to byteArrayOf(1)))
        repository.setMember(ORG_A, mine.id, BOB.toString(), share = true)
        suspend fun count(org: Uuid, table: String) = server.driver.inTenant(org) {
            fetchAll(Statement.create("SELECT COUNT(*) AS n FROM $table")).getOrThrow().first().get("n").asLong()
        }
        for (table in listOf("artifacts_artifacts", "artifacts_versions", "artifacts_blobs", "artifacts_members")) {
            assertEquals(1L, count(ORG_A, table), table)
            // No WHERE org_id: the policy alone hides organization A's rows.
            assertEquals(0L, count(ORG_B, table), table)
        }
        // A write for another organization is refused, and no row of B points at an artifact of A.
        for (org in listOf(ORG_A, ORG_B)) {
            assertFailsWith<Exception> {
                server.driver.inTenant(ORG_B) {
                    execute(
                        Statement.create(
                            "INSERT INTO artifacts_blobs (artifact_id, org_id, sha256, bytes) VALUES (:id, :org, " +
                                "'sha-x', CAST('' AS BYTEA))",
                        ).bind("id", mine.id).bind("org", org),
                    ).getOrThrow()
                }
            }
        }
        assertEquals(emptyList(), repository.members(ORG_B, mine.id))
        assertNull(repository.blob(ORG_B, mine.id, "sha-a"))
    }

    @Test
    fun listingShowsWhatEachMemberReadsByKeyset() = serverTest(listOf(ArtifactsModule)) { server ->
        val repository = server.koin.get<ArtifactsRepository>()
        val personal = artifact()
        val shared = artifact()
        val team = artifact(access = ArtifactAccess.TEAM).copy(updatedAt = t0 + 1.minutes)
        val elsewhere = artifact(org = ORG_B, access = ArtifactAccess.TEAM)
        listOf(
            personal,
            shared,
            team,
            elsewhere,
        ).forEach { repository.create(it, version(it, 1, "a" to "s"), emptyMap()) }
        repository.setMember(ORG_A, shared.id, BOB.toString(), share = true)

        assertEquals(setOf(team.id, shared.id), repository.listReadable(bob, null, 10).map { it.id }.toSet())
        assertEquals(listOf(team.id), repository.listReadable(dave, null, 10).map { it.id }, "A role opens nothing")
        assertEquals(3, repository.listReadable(alice, null, 10).size)
        val first = repository.listReadable(alice, null, 1).single()
        assertEquals(team.id, first.id)
        assertEquals(2, repository.listReadable(alice, first.id, 10).size)
    }

    /**
     * A request to the content origin. The test client only reaches `localhost`: the content origin's host is sent in
     * the `Host` header, which is what the content routes read.
     */
    private suspend fun HttpClient.content(url: String): HttpResponse =
        get(url.replace(CONTENT_ORIGIN, "http://localhost")) {
            header(HttpHeaders.Host, CONTENT_ORIGIN.substringAfter("://"))
        }

    private suspend fun HttpClient.publish(token: String, title: String, access: String, html: String): HttpResponse =
        post("/api/v1/artifacts") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                PublishArtifactRequest(
                    title = title,
                    access = access,
                    files = listOf(FileDto("index.html", html), FileDto("img/a.png", "AQID", "base64")),
                ),
            )
        }

    @Test
    fun theApiPublishesReadsSharesAndDeletes() = serverTest(listOf(ArtifactsModule), testIdentities) { server ->
        val client = server.client
        val created = client.publish("alice", "Report", "personal", "<h1>v1</h1>").also {
            assertEquals(HttpStatusCode.Created, it.status)
        }.body<ArtifactResponse>()
        assertEquals("$VIEWER/${created.id}", created.url)
        assertNull(created.publicUrl)
        assertEquals(ALICE.toString(), created.owner)

        val version = client.post("/api/v1/artifacts/${created.id}/versions") {
            bearerAuth("writer")
            contentType(ContentType.Application.Json)
            setBody(PublishVersionRequest(listOf(FileDto("index.html", "<h1>v2</h1>"))))
        }
        assertEquals(HttpStatusCode.Created, version.status)
        assertEquals(2, version.body<ArtifactVersionResponse>().number)

        val read = client.get("/api/v1/artifacts/${created.id}") { bearerAuth("reader") }.body<ArtifactResponse>()
        assertEquals(2, read.latestVersion)
        val versions = client.get("/api/v1/artifacts/${created.id}/versions") { bearerAuth("alice") }
            .body<ArtifactVersionsResponse>()
        assertEquals(listOf(listOf("index.html"), listOf("img/a.png", "index.html")), versions.items.map { it.files })

        val file = client.get("/api/v1/artifacts/${created.id}/versions/1/file?path=img/a.png") { bearerAuth("alice") }
        assertContentEquals(byteArrayOf(1, 2, 3), file.readRawBytes())
        assertEquals("image/png", file.headers[HttpHeaders.ContentType])
        assertTrue(file.headers[HttpHeaders.ContentDisposition].orEmpty().startsWith("attachment"))
        val entry = client.get("/api/v1/artifacts/${created.id}/versions/2/file") { bearerAuth("alice") }
        assertEquals("<h1>v2</h1>", entry.bodyAsText())
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/artifacts/${created.id}/versions/x/file") { bearerAuth("alice") }.status,
        )

    }

    @Test
    fun theApiSharesListsAndDeletes() = serverTest(listOf(ArtifactsModule), testIdentities) { server ->
        val client = server.client
        val created = client.publish("alice", "Report", "personal", "<h1>v1</h1>").body<ArtifactResponse>()
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/artifacts/${created.id}") { bearerAuth("bob") }.status,
        )
        val share = client.put("/api/v1/artifacts/${created.id}/members/$BOB") { bearerAuth("alice") }
        assertEquals(HttpStatusCode.NoContent, share.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/artifacts/${created.id}") { bearerAuth("bob") }.status)
        val members = client.get("/api/v1/artifacts/${created.id}/members") { bearerAuth("bob") }
            .body<ArtifactMembersResponse>()
        assertEquals(listOf(BOB.toString()), members.members)
        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/api/v1/artifacts/${created.id}/members/$BOB") { bearerAuth("alice") }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/artifacts/${created.id}") { bearerAuth("bob") }.status,
        )

        val linked = client.patch("/api/v1/artifacts/${created.id}") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody(UpdateArtifactRequest(title = "Report (final)", access = "link"))
        }.body<ArtifactResponse>()
        assertEquals("Report (final)", linked.title)
        assertTrue(linked.publicUrl.orEmpty().startsWith("$CONTENT_ORIGIN/l/"))

        val page = client.get("/api/v1/artifacts?limit=1") { bearerAuth("bob") }.body<ArtifactsResponse>()
        assertEquals(listOf(created.id), page.items.map { it.id })
        assertEquals(created.id, page.next)
        val rest = client.get("/api/v1/artifacts?before=${page.next}") { bearerAuth("bob") }.body<ArtifactsResponse>()
        assertEquals(emptyList(), rest.items)
        assertNull(rest.next)

        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/api/v1/artifacts/${created.id}") { bearerAuth("alice") }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/artifacts/${created.id}") { bearerAuth("alice") }.status,
        )
    }

    @Test
    fun refusalsMapToStatuses() = serverTest(listOf(ArtifactsModule), testIdentities) { server ->
        val client = server.client
        val team = client.publish("alice", "Team", "team", "<p>x</p>").body<ArtifactResponse>()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/artifacts").status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/artifacts") { bearerAuth("cloud") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.publish("reader", "No", "team", "x").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/artifacts") { bearerAuth("nobody") }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/artifacts/${team.id}") { bearerAuth("carol") }.status)
        assertEquals("owner", team.relation)
        val asBob = client.get("/api/v1/artifacts/${team.id}") { bearerAuth("bob") }.body<ArtifactResponse>()
        assertEquals("team", asBob.relation)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/artifacts/not-a-uuid") { bearerAuth("alice") }.status)
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/api/v1/artifacts?before=nope") { bearerAuth("alice") }.status,
        )
        assertEquals(HttpStatusCode.BadRequest, client.publish("alice", "Bad", "public", "x").status)
        val secret = client.publish("alice", "Leak", "team", "key: sk-ant-" + "a".repeat(30))
        assertEquals(HttpStatusCode.BadRequest, secret.status)
        assertFalse("sk-ant" in secret.bodyAsText())
        val forbidden = client.post("/api/v1/artifacts/${team.id}/versions") {
            bearerAuth("bob")
            contentType(ContentType.Application.Json)
            setBody(PublishVersionRequest(listOf(FileDto("index.html", "mine now"))))
        }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.delete("/api/v1/artifacts/${team.id}") { bearerAuth("bob") }.status,
        )
        val listed = client.get("/api/v1/artifacts") { bearerAuth("bob") }.body<ArtifactsResponse>()
        assertEquals(listOf(team.id), listed.items.map { it.id })
        assertNull(listed.next)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.delete("/api/v1/artifacts/${team.id}") { bearerAuth("dave") }.status,
            "An administrator deletes no other member's artifact",
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            client.delete("/api/v1/artifacts/${team.id}") { bearerAuth("agent") }.status,
            "Its owner's agent neither",
        )
        assertEquals(HttpStatusCode.Forbidden, client.publish("agent", "Public", "link", "x").status)
        val toLink = client.patch("/api/v1/artifacts/${team.id}") {
            bearerAuth("agent")
            contentType(ContentType.Application.Json)
            setBody(UpdateArtifactRequest(access = "link"))
        }
        assertEquals(HttpStatusCode.Forbidden, toLink.status)
        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/api/v1/artifacts/${team.id}") { bearerAuth("alice") }.status,
        )
    }

    @Test
    fun anAdministratorsAgentReadsNoOtherMembersPersonalArtifact() =
        serverTest(listOf(ArtifactsModule), testIdentities) { server ->
            val client = server.client
            val personal = client.publish("alice", "Mine", "personal", "<p>x</p>").body<ArtifactResponse>()
            listOf("dave", "dave-agent").forEach { token ->
                val listed = client.get("/api/v1/artifacts") { bearerAuth(token) }.body<ArtifactsResponse>()
                assertEquals(emptyList(), listed.items, token)
                listOf(
                    "/api/v1/artifacts/${personal.id}",
                    "/api/v1/artifacts/${personal.id}/versions",
                    "/api/v1/artifacts/${personal.id}/versions/1/file",
                    "/api/v1/artifacts/${personal.id}/members",
                ).forEach { path ->
                    assertEquals(HttpStatusCode.NotFound, client.get(path) { bearerAuth(token) }.status, "$token $path")
                }
                assertEquals(
                    HttpStatusCode.NotFound,
                    client.delete("/api/v1/artifacts/${personal.id}") { bearerAuth(token) }.status,
                )
                assertEquals(
                    HttpStatusCode.NotFound,
                    client.put("/api/v1/artifacts/${personal.id}/members/$DAVE") { bearerAuth(token) }.status,
                )
            }
            val shared = client.put("/api/v1/artifacts/${personal.id}/members/$DAVE") { bearerAuth("alice") }
            assertEquals(HttpStatusCode.NoContent, shared.status)
            val read = client.get("/api/v1/artifacts/${personal.id}") { bearerAuth("dave") }.body<ArtifactResponse>()
            assertEquals("viewer", read.relation)
        }

    @Test
    fun pagesAreServedOnTheContentOriginOnly() = serverTest(listOf(ArtifactsModule), testIdentities) { server ->
        val client = server.client
        val created = client.publish("alice", "Report", "personal", "<h1>page</h1>").body<ArtifactResponse>()
        val ticketCall = client.post("/api/v1/artifacts/${created.id}/tickets") { bearerAuth("alice") }
        assertEquals(HttpStatusCode.Created, ticketCall.status)
        val ticket = ticketCall.body<ArtifactTicketResponse>()
        assertEquals(1, ticket.version)
        assertEquals(ticket.url, ticket.contentUrl)
        assertTrue(ticket.url.startsWith("$CONTENT_ORIGIN/a/${created.id}/"))
        val versioned = client.post("/api/v1/artifacts/${created.id}/tickets") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody("""{"version":1}""")
        }
        assertEquals(HttpStatusCode.Created, versioned.status)

        val page = client.content(ticket.url)
        assertEquals(HttpStatusCode.OK, page.status)
        assertEquals("<h1>page</h1>", page.bodyAsText())
        assertEquals("private, max-age=30, no-transform", page.headers["Cache-Control"])
        val byAgent = client.post("/api/v1/artifacts/${created.id}/tickets") { bearerAuth("agent") }
        assertEquals(HttpStatusCode.Forbidden, byAgent.status, "A token opens no page")
        assertTrue(page.headers["Content-Security-Policy"].orEmpty().startsWith("sandbox allow-scripts"))
        assertEquals("no-referrer", page.headers["Referrer-Policy"])
        val image = client.content(ticket.url.replace("index.html", "img/a.png"))
        assertContentEquals(byteArrayOf(1, 2, 3), image.readRawBytes())

        val elsewhere = client.get(ticket.url.replace(CONTENT_ORIGIN, "http://localhost"))
        assertEquals(HttpStatusCode.NotFound, elsewhere.status)
        assertTrue(elsewhere.headers["Content-Security-Policy"].orEmpty().startsWith("sandbox"))
        assertEquals("no-store, no-transform", elsewhere.headers["Cache-Control"])
        assertEquals(HttpStatusCode.NotFound, client.content("$CONTENT_ORIGIN/a/not-a-uuid/t/index.html").status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.content("$CONTENT_ORIGIN/a/${created.id}/1-1-00/index.html").status,
        )

        // Sixty tickets a minute per credential: two were minted above.
        repeat(58) {
            val again = client.post("/api/v1/artifacts/${created.id}/tickets") { bearerAuth("alice") }
            assertEquals(HttpStatusCode.Created, again.status)
        }
        val limited = client.post("/api/v1/artifacts/${created.id}/tickets") { bearerAuth("alice") }
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertTrue((limited.headers[HttpHeaders.RetryAfter]?.toInt() ?: 0) in 1..60)

        val linked = client.patch("/api/v1/artifacts/${created.id}") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody(UpdateArtifactRequest(access = "link"))
        }.body<ArtifactResponse>()
        val publicUrl = assertNotNull(linked.publicUrl)
        assertEquals("<h1>page</h1>", client.content(publicUrl).bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.content("$CONTENT_ORIGIN/l/unknown/").status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get(publicUrl.replace(CONTENT_ORIGIN, "http://localhost")).status,
        )

        client.post("/api/v1/artifacts/${created.id}/versions") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody(PublishVersionRequest(listOf(FileDto("README.md", "# Title\n\n<b>x</b>"))))
        }
        val markdown = client.content(publicUrl)
        assertTrue("<h1>Title</h1>" in markdown.bodyAsText())
        assertTrue("&lt;b&gt;" in markdown.bodyAsText())
        assertTrue(markdown.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/html"))
    }

    @Test
    fun aLargePublicationIsAcceptedAndNoTicketOrLinkReachesTheLogs() {
        // Written by the server's threads, read by the test.
        val written = AtomicReference(emptyList<String>())
        val overrides = module {
            includes(testIdentities)
            single<Logger> {
                JsonLogger(LogLevel.INFO, sink = { line ->
                    while (true) {
                        val current = written.load()
                        if (written.compareAndSet(current, current + line)) break
                    }
                })
            }
        }
        serverTest(listOf(ArtifactsModule), overrides) { server ->
            val client = server.client
            // S2-46: publishing declares its own body limit, over the kernel's 1 MiB default.
            val large = client.publish("alice", "Large", "link", "<p>${"x".repeat(1_200_000)}</p>")
            assertEquals(HttpStatusCode.Created, large.status)
            val created = large.body<ArtifactResponse>()
            val ticket = client.post("/api/v1/artifacts/${created.id}/tickets") { bearerAuth("alice") }
                .body<ArtifactTicketResponse>()
            assertEquals(HttpStatusCode.OK, client.content(ticket.url).status)
            val publicUrl = assertNotNull(created.publicUrl)
            assertEquals(HttpStatusCode.OK, client.content(publicUrl).status)

            // S2-45: the ticket and the slug are in the paths; the request lines carry the route templates only.
            val ticketSegment = ticket.url.removePrefix("$CONTENT_ORIGIN/a/${created.id}/").substringBefore('/')
            val slug = publicUrl.removePrefix("$CONTENT_ORIGIN/l/").substringBefore('/')
            var waits = 0
            while (written.load().count { "request served" in it } < REQUESTS_MADE && waits++ < 50) {
                delay(20.milliseconds)
            }
            val lines = written.load()
            assertTrue(lines.any { "\"route\":\"/a/{id}/{ticket}/{...}\"" in it }, lines.joinToString("\n"))
            assertTrue(lines.any { "\"route\":\"/l/{slug}/{...}\"" in it }, lines.joinToString("\n"))
            assertTrue(ticketSegment.length > 8 && slug.length > 8)
            assertFalse(lines.any { ticketSegment in it || slug in it }, "A ticket or a link slug was logged")
        }
    }

    private companion object {
        /** The publication, the ticket, and the two pages. */
        const val REQUESTS_MADE = 4
    }
}
