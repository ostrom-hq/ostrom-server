// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.GitHubAccessMode
import run.ostrom.server.runners.domain.RunnerGitHubToken
import run.ostrom.server.runners.domain.RunnerGitHubTokens
import run.ostrom.server.runners.domain.RunnerTokens
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.ORG_B
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.fakes.testIdentities
import run.ostrom.server.runners.infrastructure.inTenant
import run.ostrom.server.runners.presentation.CloudSessionResponse
import run.ostrom.server.runners.presentation.RunnerGitHubTokenRequest
import run.ostrom.server.runners.presentation.RunnerGitHubTokenResponse
import run.ostrom.server.runners.presentation.SetCloudSessionGitHubAccessRequest
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A session's GitHub access and the runner's token request, against a real PostgreSQL and real HTTP. */
class RunnerGitHubPostgresTest {
    private var answer: RunnerGitHubToken = RunnerGitHubToken.Linked("ghu_alice", EXPIRY, "octocat")
    private val overrides = module {
        includes(testIdentities)
        single<RunnerGitHubTokens> {
            object : RunnerGitHubTokens {
                override suspend fun available(): Boolean = true

                override suspend fun token(orgId: Uuid, memberId: String): RunnerGitHubToken = answer
            }
        }
    }

    private fun gitHubTest(block: suspend (TestServer) -> Unit) =
        serverTest(listOf(RunnersModule), overrides) { server -> block(server) }

    private suspend fun TestServer.setAccess(token: String?, id: Any, access: String): HttpResponse =
        client.put("/api/v1/cloud/sessions/$id/github") {
            token?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(SetCloudSessionGitHubAccessRequest(access))
        }

    private suspend fun TestServer.ask(auth: String?, body: String): HttpResponse =
        client.post("/api/v1/cloud/runner/github") {
            auth?.let { header(HttpHeaders.Authorization, it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun theRepositoryKeepsASessionsAccessUntilItEnds() = gitHubTest { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val created = session(createdAt = T0)
        repository.createWithinCap(created, 5)
        assertEquals(GitHubAccessMode.OWNER, repository.get(ORG_A, created.id)?.githubAccess, "The default")
        val shared = repository.setGitHubAccess(ORG_A, created.id, GitHubAccessMode.SHARED, T0)
        assertEquals(GitHubAccessMode.SHARED to 1L, shared?.githubAccess to shared?.generation)
        assertNull(repository.setGitHubAccess(ORG_B, created.id, GitHubAccessMode.NONE, T0), "Another organization")
        val over = session(state = SessionState.TERMINATED, createdAt = T0)
        repository.createWithinCap(over, 5)
        assertNull(repository.setGitHubAccess(ORG_A, over.id, GitHubAccessMode.NONE, T0))

        // A name this version does not know grants nothing.
        server.driver.inTenant(ORG_A) {
            execute(
                Statement.create("UPDATE runners_sessions SET github_access = 'everyone' WHERE id = :id")
                    .bind("id", created.id),
            ).getOrThrow()
        }
        assertEquals(GitHubAccessMode.NONE, repository.get(ORG_A, created.id)?.githubAccess)
    }

    @Test
    fun theOwnerSetsTheAccessFromTheApi() = gitHubTest { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val running = session(state = SessionState.RUNNING, createdAt = T0)
        repository.createWithinCap(running, 5)

        val shared = server.setAccess("alice", running.id, "shared")
        assertEquals(HttpStatusCode.OK, shared.status)
        assertEquals("shared", shared.body<CloudSessionResponse>().githubAccess)
        val read = server.client.get("/api/v1/cloud/sessions/${running.id}") { bearerAuth("alice") }
        assertEquals("shared", read.body<CloudSessionResponse>().githubAccess)

        assertEquals(HttpStatusCode.Conflict, server.setAccess("alice", running.id, "none").status, "Pause first")
        assertEquals(HttpStatusCode.BadRequest, server.setAccess("alice", running.id, "everyone").status)
        assertEquals(HttpStatusCode.Unauthorized, server.setAccess(null, running.id, "shared").status)
        assertEquals(HttpStatusCode.Forbidden, server.setAccess("reader", running.id, "shared").status, "Read only")
        assertEquals(HttpStatusCode.NotFound, server.setAccess("bob", running.id, "owner").status, "Not bob's")
        assertEquals(HttpStatusCode.NotFound, server.setAccess("carol", running.id, "owner").status, "Org B")
        assertEquals(HttpStatusCode.NotFound, server.setAccess("alice", "nope", "owner").status)
        assertEquals(HttpStatusCode.NotFound, server.setAccess("dana-cli", Uuid.random(), "owner").status)
        assertEquals(GitHubAccessMode.SHARED, repository.get(ORG_A, running.id)?.githubAccess)
    }

    @Test
    fun theRunnerOfTheCurrentExecutionGetsItsOwnersTokenUncached() = gitHubTest { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val tokens = server.koin.get<RunnerTokens>()
        val token = tokens.mint()
        val running = session(state = SessionState.RUNNING, createdAt = T0)
            .copy(epoch = 2, runnerTokenHash = tokens.hash(token), executionStartedAt = T0)
        repository.createWithinCap(running, 5)
        val body = """{"sessionId":"${running.id}","epoch":2}"""

        val linked = server.ask("Bearer $token", body)
        assertEquals(HttpStatusCode.OK, linked.status)
        assertEquals("no-store", linked.headers[HttpHeaders.CacheControl])
        assertEquals(
            RunnerGitHubTokenResponse("linked", "ghu_alice", EXPIRY.toString(), "octocat"),
            linked.body<RunnerGitHubTokenResponse>(),
        )
        answer = RunnerGitHubToken.Linked("ghu_forever", null, "octocat")
        assertNull(server.ask("Bearer $token", body).body<RunnerGitHubTokenResponse>().expiresAt)
        answer = RunnerGitHubToken.Unavailable
        assertEquals(RunnerGitHubTokenResponse("unavailable"), server.ask("Bearer $token", body).body())

        // Paused then resumed without access: nothing, whatever the link.
        answer = RunnerGitHubToken.Linked("ghu_alice", EXPIRY, "octocat")
        repository.setGitHubAccess(ORG_A, running.id, GitHubAccessMode.NONE, T0)
        assertEquals(RunnerGitHubTokenResponse("not_linked"), server.ask("Bearer $token", body).body())

        val stale = RunnerGitHubTokenRequest(running.id.toString(), 1)
        val fenced = server.ask("Bearer $token", """{"sessionId":"${stale.sessionId}","epoch":${stale.epoch}}""")
        assertEquals(HttpStatusCode.Conflict, fenced.status)
        assertEquals(HttpStatusCode.Unauthorized, server.ask("Bearer ostrom_runner_wrong", body).status)
        assertEquals(HttpStatusCode.Unauthorized, server.ask(null, body).status)
        assertEquals(HttpStatusCode.Unauthorized, server.ask("Bearer alice", body).status, "A member's token")
        assertEquals(HttpStatusCode.Unauthorized, server.ask("Bearer x", "{not json").status, "Body unread")
        assertEquals(HttpStatusCode.BadRequest, server.ask("Bearer $token", "{not json").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            server.ask("Bearer $token", """{"sessionId":"nope","epoch":2}""").status,
        )
        val padded = """{"sessionId":"${running.id}","epoch":2,"pad":"${"x".repeat(5000)}"}"""
        assertEquals(HttpStatusCode.PayloadTooLarge, server.ask("Bearer $token", padded).status)
    }

    private companion object {
        val T0: Instant = Instant.parse("2026-10-30T10:00:00Z")
        val EXPIRY: Instant = Instant.parse("2026-10-30T18:00:00Z")
    }
}
