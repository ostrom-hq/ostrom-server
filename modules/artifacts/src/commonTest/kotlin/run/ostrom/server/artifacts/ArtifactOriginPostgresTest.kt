// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.Artifact
import run.ostrom.server.artifacts.domain.ArtifactAccess
import run.ostrom.server.artifacts.domain.ArtifactFile
import run.ostrom.server.artifacts.domain.ArtifactOrigin
import run.ostrom.server.artifacts.domain.ArtifactVersion
import run.ostrom.server.artifacts.domain.ArtifactsRepository
import run.ostrom.server.artifacts.fakes.ALICE
import run.ostrom.server.artifacts.fakes.ORG_A
import run.ostrom.server.artifacts.fakes.activateArtifacts
import run.ostrom.server.artifacts.fakes.agentIdentities
import run.ostrom.server.artifacts.infrastructure.Sha256ContentDigest
import run.ostrom.server.artifacts.presentation.ArtifactConflictResponse
import run.ostrom.server.artifacts.presentation.ArtifactOriginResponse
import run.ostrom.server.artifacts.presentation.ArtifactResponse
import run.ostrom.server.artifacts.presentation.ArtifactUploadResponse
import run.ostrom.server.artifacts.presentation.ArtifactVersionResponse
import run.ostrom.server.artifacts.presentation.ArtifactVersionsResponse
import run.ostrom.server.artifacts.presentation.ArtifactsResponse
import run.ostrom.server.artifacts.presentation.ArtifactFileRefDto
import run.ostrom.server.artifacts.presentation.CreateArtifactUploadRequest
import run.ostrom.server.artifacts.presentation.FileDto
import run.ostrom.server.artifacts.presentation.PublishArtifactRequest
import run.ostrom.server.artifacts.presentation.PublishVersionRequest
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** TC-ART-39 against a real PostgreSQL: the columns, the routes, the uploads and the MCP server. */
class ArtifactOriginPostgresTest {
    private val t0 = Instant.parse("2026-10-30T10:00:00Z")
    private val cloud = ArtifactOriginResponse("launch-cloud", "session-1")
    private val machine = ArtifactOriginResponse("launch-machine")

    private fun file(sha: String) = ArtifactFile("index.html", sha, 3, "text/html; charset=utf-8")

    private fun version(artifact: Artifact, number: Int, origin: ArtifactOrigin?) =
        ArtifactVersion(
            artifact.id, number, "index.html", listOf(file("sha-$number")), t0, artifact.ownerId, origin = origin,
        )

    @Test
    fun theRepositoryKeepsTheArtifactsOriginAndEachVersionsOwn() = serverTest(listOf(ArtifactsModule)) { server ->
        val repository = server.koin.get<ArtifactsRepository>()
        val origin = ArtifactOrigin("launch-1", "session-1")
        val mine = Artifact(
            Uuid.random(), ORG_A, ALICE.toString(), "T", ArtifactAccess.PERSONAL, 1, null, t0, t0, origin,
        )
        repository.create(mine, version(mine, 1, origin))
        val plain = Artifact(Uuid.random(), ORG_A, ALICE.toString(), "P", ArtifactAccess.PERSONAL, 1, null, t0, t0)
        repository.create(plain, version(plain, 1, null))
        assertEquals(mine, repository.get(ORG_A, mine.id))
        assertNull(repository.get(ORG_A, plain.id)?.origin)

        val machine = ArtifactOrigin("launch-2")
        val updated = assertNotNull(repository.addVersion(ORG_A, version(mine, 2, machine), t0))
        assertEquals(origin, updated.origin, "A later version never changes the artifact's origin")
        repository.updateSettings(updated.copy(title = "U"))
        assertEquals(origin, repository.get(ORG_A, mine.id)?.origin)
        assertNotNull(repository.addVersion(ORG_A, version(mine, 3, null), t0))
        assertNull(repository.version(ORG_A, mine.id, 3)?.origin)
        assertEquals(listOf(null, machine, origin), repository.versions(ORG_A, mine.id).map { it.origin })
        assertEquals(machine, repository.version(ORG_A, mine.id, 2)?.origin)
    }

    @Test
    fun theVersionsSessionColumnIsIndexed() = serverTest(listOf(ArtifactsModule)) { server ->
        val indexes = server.driver.fetchAll(
            Statement.create(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'artifacts_versions'",
            ),
        ).getOrThrow().map { it.get("indexdef").asString() }
        assertTrue(indexes.any { "origin_session_id" in it }, indexes.toString())
    }

    private suspend fun HttpClient.publish(token: String, body: String): HttpResponse = post("/api/v1/artifacts") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun publication(extra: String = "") =
        """{"title":"Report","files":[{"path":"index.html","content":"<h1>x</h1>"}]$extra}"""

    private suspend fun HttpClient.version(token: String, id: String, html: String): ArtifactVersionResponse =
        post("/api/v1/artifacts/$id/versions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(PublishVersionRequest(listOf(FileDto("index.html", html))))
        }.body()

    @Test
    fun theApiAnswersTheOriginOfAnAgentsPublicationAndOfItsVersions() =
        serverTest(listOf(ArtifactsModule), agentIdentities) { server ->
            server.koin.activateArtifacts()
            val client = server.client
            val created = client.publish("agent-cloud", publication()).also {
                assertEquals(HttpStatusCode.Created, it.status)
            }.body<ArtifactResponse>()
            assertEquals(cloud, created.origin)
            val viaMachine = client.publish("agent-machine", publication()).body<ArtifactResponse>()
            assertEquals(machine, viaMachine.origin)
            assertNull(viaMachine.origin?.cloudSessionId)
            val byPerson = client.publish("alice", publication()).body<ArtifactResponse>()
            assertNull(byPerson.origin)
            assertTrue("origin" !in client.get("/api/v1/artifacts/${byPerson.id}") { bearerAuth("alice") }.rawBody())

            // A later version by a person: the artifact keeps its creation origin, the version has none.
            assertNull(client.version("alice", created.id, "<h1>2</h1>").origin)
            val read = client.get("/api/v1/artifacts/${created.id}") { bearerAuth("alice") }.body<ArtifactResponse>()
            assertEquals(cloud, read.origin)
            // A later version by the machine launch records its own.
            assertEquals(machine, client.version("agent-machine", created.id, "<h1>3</h1>").origin)
            val stillRead = client.get("/api/v1/artifacts/${created.id}") { bearerAuth("alice") }
            assertEquals(cloud, stillRead.body<ArtifactResponse>().origin)
            val versions = client.get("/api/v1/artifacts/${created.id}/versions") { bearerAuth("alice") }
                .body<ArtifactVersionsResponse>()
            assertEquals(listOf(machine, null, cloud), versions.items.map { it.origin })
            val listed = client.get("/api/v1/artifacts") { bearerAuth("alice") }.body<ArtifactsResponse>()
            assertEquals(
                setOf(cloud, machine, null),
                listed.items.map { it.origin }.toSet(),
            )
        }

    private suspend fun HttpResponse.rawBody(): String = bodyAsText()

    @Test
    fun theOriginIsNeverTakenFromTheRequest() = serverTest(listOf(ArtifactsModule), agentIdentities) { server ->
        server.koin.activateArtifacts()
        val client = server.client
        val forged = ""","origin":{"launchId":"forged","cloudSessionId":"forged"}"""
        // A person claims one, an agent claims another launch: the credential's is the one that counts.
        val byPerson = client.publish("alice", publication(forged)).body<ArtifactResponse>()
        assertNull(byPerson.origin)
        val byAgent = client.publish("agent-machine", publication(forged)).body<ArtifactResponse>()
        assertEquals(machine, byAgent.origin)
        val version = client.post("/api/v1/artifacts/${byPerson.id}/versions") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody("""{"files":[{"path":"index.html","content":"<p>2</p>"}]$forged}""")
        }.body<ArtifactVersionResponse>()
        assertNull(version.origin)
        // The same through the MCP tool: arguments with an origin change nothing.
        val mcp = client.post("/api/v1/artifacts/mcp") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody(mcpPublish(""","origin":{"launchId":"forged","cloudSessionId":"forged"}"""))
        }
        val id = mcpArtifactId(mcp)
        assertNull(client.get("/api/v1/artifacts/$id") { bearerAuth("alice") }.body<ArtifactResponse>().origin)
    }

    private fun mcpPublish(extra: String = "") = """
        {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"publish","arguments":
        {"title":"Report","files":[{"path":"index.html","content":"<h1>x</h1>"}]$extra}}}
    """.trimIndent().replace("\n", "")

    private suspend fun mcpArtifactId(response: HttpResponse): String {
        val body = response.rawBody()
        val result: JsonObject = checkNotNull(Json.parseToJsonElement(body).jsonObject["result"]) { body }.jsonObject
        val text = result.getValue("content").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content
        return text.substringAfter("Artifact id: ").trim()
    }

    @Test
    fun theMcpPublishToolRecordsTheOriginOfTheAgentThatCallsIt() =
        serverTest(listOf(ArtifactsModule), agentIdentities) { server ->
            server.koin.activateArtifacts()
            val client = server.client
            val id = mcpArtifactId(
                client.post("/api/v1/artifacts/mcp") {
                    bearerAuth("agent-cloud")
                    contentType(ContentType.Application.Json)
                    setBody(mcpPublish())
                },
            )
            val read = client.get("/api/v1/artifacts/$id") { bearerAuth("alice") }.body<ArtifactResponse>()
            assertEquals(cloud, read.origin)
            val versions = client.get("/api/v1/artifacts/$id/versions") { bearerAuth("alice") }
                .body<ArtifactVersionsResponse>()
            assertEquals(listOf(cloud), versions.items.map { it.origin })
            // Republished by a person through the tool: no version origin, the artifact's stays.
            val again = client.post("/api/v1/artifacts/mcp") {
                bearerAuth("alice")
                contentType(ContentType.Application.Json)
                setBody(mcpPublish(""","artifact":"$id""""))
            }
            assertTrue("Published version 2 of $id" in again.rawBody())
            val after = client.get("/api/v1/artifacts/$id/versions") { bearerAuth("alice") }
                .body<ArtifactVersionsResponse>()
            assertEquals(listOf(null, cloud), after.items.map { it.origin })
        }

    @Test
    fun anUploadCommitRecordsTheOriginAndTheConflictCarriesIt() =
        serverTest(listOf(ArtifactsModule), agentIdentities) { server ->
            server.koin.activateArtifacts()
            val client = server.client
            val page = "<p>v1</p>".encodeToByteArray()
            val hash = Sha256ContentDigest().sha256(page)
            suspend fun opened(token: String, artifact: String?) = client.post("/api/v1/artifacts/uploads") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(CreateArtifactUploadRequest(artifact, listOf(ArtifactFileRefDto(hash, page.size.toLong()))))
            }.body<ArtifactUploadResponse>().also { upload ->
                client.put("/api/v1/artifacts/uploads/${upload.uploadId}/blobs/$hash/0") {
                    bearerAuth(token)
                    contentType(ContentType.Application.OctetStream)
                    setBody(page)
                }
            }
            suspend fun commit(token: String, upload: ArtifactUploadResponse, base: Int, path: String) =
                client.post("/api/v1/artifacts/${upload.artifactId}/versions") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    val files = listOf(FileDto(path, sha256 = hash, size = page.size.toLong()))
                    setBody(PublishVersionRequest(files, upload.uploadId, baseVersion = base, title = "Uploaded"))
                }
            val draft = opened("agent-cloud", null)
            val first = commit("agent-cloud", draft, 0, "index.html")
            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(cloud, first.body<ArtifactVersionResponse>().origin)
            val artifact = client.get("/api/v1/artifacts/${draft.artifactId}") { bearerAuth("alice") }
                .body<ArtifactResponse>()
            assertEquals(cloud, artifact.origin, "The draft took its creator's origin")
            // A person commits the next version: none of its own, the artifact keeps the agent's.
            val second = commit("alice", opened("alice", draft.artifactId), 1, "two.html")
            assertEquals(HttpStatusCode.Created, second.status)
            assertNull(second.body<ArtifactVersionResponse>().origin)
            // A commit of the machine launch from a stale base gets the live version back, with its origin.
            val stale = commit("agent-machine", opened("agent-machine", draft.artifactId), 1, "three.html")
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertNull(stale.body<ArtifactConflictResponse>().current?.origin)
            val third = commit("agent-machine", opened("agent-machine", draft.artifactId), 2, "four.html")
            assertEquals(machine, third.body<ArtifactVersionResponse>().origin)
            val versions = client.get("/api/v1/artifacts/${draft.artifactId}/versions") { bearerAuth("alice") }
                .body<ArtifactVersionsResponse>()
            assertEquals(listOf(machine, null, cloud), versions.items.map { it.origin })
            val last = commit("alice", opened("alice", draft.artifactId), 2, "five.html")
            val current = last.body<ArtifactConflictResponse>().current
            assertEquals(machine, current?.origin, "The 409's current is the live version, with its origin")
        }
}
