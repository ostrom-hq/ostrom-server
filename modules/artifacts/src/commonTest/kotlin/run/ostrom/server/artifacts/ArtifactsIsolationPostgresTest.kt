// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.fakes.BOB
import run.ostrom.server.artifacts.fakes.CAROL
import run.ostrom.server.artifacts.fakes.activateArtifacts
import run.ostrom.server.artifacts.fakes.testIdentities
import run.ostrom.server.artifacts.presentation.ArtifactMembersResponse
import run.ostrom.server.artifacts.presentation.ArtifactResponse
import run.ostrom.server.artifacts.presentation.ArtifactVersionsResponse
import run.ostrom.server.artifacts.presentation.ArtifactsResponse
import run.ostrom.server.artifacts.presentation.FileDto
import run.ostrom.server.artifacts.presentation.PublishArtifactRequest
import run.ostrom.server.artifacts.presentation.PublishVersionRequest
import run.ostrom.server.artifacts.presentation.UpdateArtifactRequest
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Another organization's administrator (carol, owner of org B) neither lists, reads, changes, publishes to, shares,
 * views nor deletes a team artifact of org A: every route on it answers 404 and its owner reads it unchanged.
 */
class ArtifactsIsolationPostgresTest {
    @Test
    fun anotherOrganizationNeitherSeesNorChangesAnArtifact() = serverTest(listOf(ArtifactsModule), testIdentities) {
        it.koin.activateArtifacts()
        val client = it.client
        val created = client.post("/api/v1/artifacts") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody(PublishArtifactRequest("Team", "team", listOf(FileDto("index.html", "v1"))))
        }.body<ArtifactResponse>()
        val path = "/api/v1/artifacts/${created.id}"
        assertEquals(HttpStatusCode.NoContent, client.put("$path/members/$BOB") { bearerAuth("alice") }.status)
        val before = client.get(path) { bearerAuth("alice") }.body<ArtifactResponse>()
        val versionsBefore = client.get("$path/versions") { bearerAuth("alice") }.body<ArtifactVersionsResponse>()

        val listed = client.get("/api/v1/artifacts") { bearerAuth("carol") }.body<ArtifactsResponse>()
        assertEquals(emptyList(), listed.items, "B lists none of A's artifacts")
        val attempts = listOf(
            client.patch(path) {
                bearerAuth("carol")
                contentType(ContentType.Application.Json)
                setBody(UpdateArtifactRequest(title = "Owned", access = "link"))
            },
            client.post("$path/versions") {
                bearerAuth("carol")
                contentType(ContentType.Application.Json)
                setBody(PublishVersionRequest(listOf(FileDto("index.html", "defaced"))))
            },
            client.get("$path/versions") { bearerAuth("carol") },
            client.get("$path/versions/1/file") { bearerAuth("carol") },
            client.get("$path/members") { bearerAuth("carol") },
            client.put("$path/members/$CAROL") { bearerAuth("carol") },
            client.delete("$path/members/$BOB") { bearerAuth("carol") },
            client.post("$path/tickets") { bearerAuth("carol") },
            client.delete(path) { bearerAuth("carol") },
        )
        attempts.forEach { response ->
            assertEquals(
                HttpStatusCode.NotFound,
                response.status,
                "${response.call.request.method.value} ${response.call.request.url}",
            )
        }

        assertEquals(before, client.get(path) { bearerAuth("alice") }.body<ArtifactResponse>(), "Settings unchanged")
        val versionsAfter = client.get("$path/versions") { bearerAuth("alice") }.body<ArtifactVersionsResponse>()
        assertEquals(versionsBefore, versionsAfter, "No version published by B")
        assertEquals("v1", client.get("$path/versions/1/file") { bearerAuth("alice") }.bodyAsText())
        val members = client.get("$path/members") { bearerAuth("alice") }.body<ArtifactMembersResponse>()
        assertEquals(listOf(BOB.toString()), members.members, "Shares unchanged by B")
    }
}
