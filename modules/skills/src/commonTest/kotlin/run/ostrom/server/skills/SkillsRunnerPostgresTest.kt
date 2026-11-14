// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.skills

import run.ostrom.server.skills.fakes.ADA
import run.ostrom.server.skills.fakes.ADA_SESSION
import run.ostrom.server.skills.fakes.GONE_SESSION
import run.ostrom.server.skills.fakes.PAUSED_SESSION
import run.ostrom.server.skills.fakes.call
import run.ostrom.server.skills.fakes.field
import run.ostrom.server.skills.fakes.publication
import run.ostrom.server.skills.fakes.testIdentities
import run.ostrom.server.testing.serverTest
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** A cloud session's runner installs its owner's skills (TeamCode's execution credential, TC-SKL-11). */
class SkillsRunnerPostgresTest {
    private val post = HttpMethod.Post

    private fun execution(session: Uuid, epoch: Long = 1) = """{"sessionId": "$session", "epoch": $epoch}"""

    private fun artifact(session: Uuid, name: String, version: String = "1.0.0") =
        """{"sessionId": "$session", "epoch": 1, "name": "$name", "version": "$version"}"""

    @Test
    fun theRunnerReceivesItsOwnersSkills() = serverTest(listOf(SkillsModule), testIdentities()) { server ->
        val client = server.client
        val team = publication("1.0.0", pin = true, allMembers = true)
        client.call(post, "owner", "/api/v1/skills/review/versions", team)
        client.call(post, "ada-cli", "/api/v1/me/skills/notes/versions", publication("1.0.0", pin = true))
        client.call(post, "ben-cli", "/api/v1/me/skills/bens/versions", publication("1.0.0", pin = true))

        val manifest = client.post(SKILLS) {
            header(HttpHeaders.Authorization, "Bearer runner-ada")
            contentType(ContentType.Application.Json)
            setBody(execution(ADA_SESSION))
        }
        assertEquals(200, manifest.status.value)
        assertEquals("no-store", manifest.headers[HttpHeaders.CacheControl])
        val mine = client.call(post, "runner-ada", SKILLS, execution(ADA_SESSION))
        assertEquals("$ADA", mine.text("memberId"))
        assertEquals(listOf("notes", "review"), mine.list("skills").map { it.field("name") }, "Never another's")

        val files = client.call(post, "runner-ada", ARTIFACT, artifact(ADA_SESSION, "review"))
        assertEquals(200, files.status)
        assertEquals(DIGEST, files.text("digest"))
        assertEquals("SKILL.md", files.list("files").single().field("path"))
        assertEquals(404, client.call(post, "runner-ada", ARTIFACT, artifact(ADA_SESSION, "review", "2.0.0")).status)
        assertEquals(404, client.call(post, "runner-ada", ARTIFACT, artifact(ADA_SESSION, "bens")).status)

        // An owner who left receives nothing.
        assertEquals(0, client.call(post, "runner-gone", SKILLS, execution(GONE_SESSION)).list("skills").size)
        assertEquals(404, client.call(post, "runner-gone", ARTIFACT, artifact(GONE_SESSION, "review")).status)
    }

    @Test
    fun onlyTheCurrentExecutionsRunnerIsAnswered() = serverTest(listOf(SkillsModule), testIdentities()) { server ->
        val client = server.client
        for (path in listOf(SKILLS, ARTIFACT)) {
            val body = if (path == SKILLS) execution(ADA_SESSION) else artifact(ADA_SESSION, "review")
            for (authorization in listOf(null, "Basic runner-ada", "Bearer ", "Bearer nope", "Bearer ada")) {
                val refused = client.post(path) {
                    authorization?.let { header(HttpHeaders.Authorization, it) }
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                assertEquals(401, refused.status.value, "$path $authorization")
            }
            // Another execution of the session: the runners module's own refusal.
            val fenced = body.replace("\"epoch\": 1", "\"epoch\": 2")
            assertEquals(409, client.call(post, "runner-ada", path, fenced).status)
            val paused = if (path == SKILLS) execution(PAUSED_SESSION) else artifact(PAUSED_SESSION, "review")
            val idle = client.call(post, "runner-paused", path, paused)
            assertEquals(409 to "delivery_unavailable", idle.status to idle.error)
            val invalid = client.call(post, "runner-ada", path, body.replace("$ADA_SESSION", "not-a-session"))
            assertEquals(400 to "invalid_runner_request", invalid.status to invalid.error)
        }
    }

    private companion object {
        const val SKILLS = "/api/v1/cloud/runner/skills"
        const val ARTIFACT = "/api/v1/cloud/runner/skills/artifact"
        const val DIGEST = "sha256:fed02854d013630baa3518562a6b42207967f7c141b6043ff847366365c20588"
    }
}
