// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.hooks

import run.ostrom.server.hooks.fakes.ADA
import run.ostrom.server.hooks.fakes.ADA_SESSION
import run.ostrom.server.hooks.fakes.GONE_SESSION
import run.ostrom.server.hooks.fakes.PAUSED_SESSION
import run.ostrom.server.hooks.fakes.call
import run.ostrom.server.hooks.fakes.field
import run.ostrom.server.hooks.fakes.publication
import run.ostrom.server.hooks.fakes.testIdentities
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

/** A cloud session's runner installs its owner's team hooks, without a member approval (TC-HOK-9). */
class HooksRunnerPostgresTest {
    private val post = HttpMethod.Post
    private val put = HttpMethod.Put

    private fun execution(session: Uuid) = """{"sessionId": "$session", "epoch": 1}"""

    private fun artifact(session: Uuid, name: String, version: String = "1.0.0") =
        """{"sessionId": "$session", "epoch": 1, "name": "$name", "version": "$version"}"""

    @Test
    fun theRunnerReceivesItsOwnersHooks() = serverTest(listOf(HooksModule), testIdentities()) { server ->
        val client = server.client
        client.call(post, "owner", "/api/v1/hooks/review/versions", publication("1.0.0", allMembers = true))
        client.call(post, "owner", "/api/v1/hooks/draft/versions", publication("1.0.0", allMembers = true))
        client.call(put, "owner", "/api/v1/hooks/review/pin", """{"version": "1.0.0"}""")

        val raw = client.post(HOOKS) {
            header(HttpHeaders.Authorization, "Bearer runner-ada")
            contentType(ContentType.Application.Json)
            setBody(execution(ADA_SESSION))
        }
        assertEquals(200 to "no-store", raw.status.value to raw.headers[HttpHeaders.CacheControl])
        val manifest = client.call(post, "runner-ada", HOOKS, execution(ADA_SESSION))
        assertEquals("$ADA", manifest.text("memberId"))
        assertEquals(listOf("review"), manifest.list("hooks").map { it.field("name") }, "Only a pinned version")

        val files = client.call(post, "runner-ada", ARTIFACT, artifact(ADA_SESSION, "review"))
        assertEquals(200 to "run.sh", files.status to files.text("files.0.path"))
        assertEquals(404, client.call(post, "runner-ada", ARTIFACT, artifact(ADA_SESSION, "draft")).status)
        assertEquals(404, client.call(post, "runner-ada", ARTIFACT, artifact(ADA_SESSION, "review", "2.0.0")).status)

        // An owner who left receives nothing.
        assertEquals(0, client.call(post, "runner-gone", HOOKS, execution(GONE_SESSION)).list("hooks").size)
        assertEquals(404, client.call(post, "runner-gone", ARTIFACT, artifact(GONE_SESSION, "review")).status)
    }

    @Test
    fun onlyTheCurrentExecutionsRunnerIsAnswered() = serverTest(listOf(HooksModule), testIdentities()) { server ->
        val client = server.client
        for (path in listOf(HOOKS, ARTIFACT)) {
            val body = if (path == HOOKS) execution(ADA_SESSION) else artifact(ADA_SESSION, "review")
            for (authorization in listOf(null, "Basic runner-ada", "Bearer nope", "Bearer ada")) {
                val refused = client.post(path) {
                    authorization?.let { header(HttpHeaders.Authorization, it) }
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                assertEquals(401, refused.status.value, "$path $authorization")
            }
            val fenced = body.replace("\"epoch\": 1", "\"epoch\": 2")
            assertEquals(409, client.call(post, "runner-ada", path, fenced).status, "Another execution")
            val paused = if (path == HOOKS) execution(PAUSED_SESSION) else artifact(PAUSED_SESSION, "review")
            val idle = client.call(post, "runner-paused", path, paused)
            assertEquals(409 to "delivery_unavailable", idle.status to idle.error)
            val invalid = client.call(post, "runner-ada", path, body.replace("$ADA_SESSION", "not-a-session"))
            assertEquals(400 to "invalid_runner_request", invalid.status to invalid.error)
        }
    }

    private companion object {
        const val HOOKS = "/api/v1/cloud/runner/hooks"
        const val ARTIFACT = "/api/v1/cloud/runner/hooks/artifact"
    }
}
