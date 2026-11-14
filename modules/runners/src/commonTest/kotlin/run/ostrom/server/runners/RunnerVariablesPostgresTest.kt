// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.RunnerTokens
import run.ostrom.server.runners.domain.RunnerVariableValues
import run.ostrom.server.runners.domain.RunnerVariables
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.ALICE
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.fakes.testIdentities
import run.ostrom.server.runners.presentation.RunnerVariablesResponse
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The runner's ask for its owner's environment variables, against a real PostgreSQL and real HTTP. */
class RunnerVariablesPostgresTest {
    private val asked = mutableListOf<Pair<Uuid, String>>()
    private var answer = RunnerVariableValues(mapOf("OPENROUTER_API_KEY" to "sk-or-alice"), listOf("BROKEN"))
    private val overrides = module {
        includes(testIdentities)
        single<RunnerVariables> {
            RunnerVariables { orgId, memberId ->
                asked += orgId to memberId
                answer
            }
        }
    }

    private fun variablesTest(block: suspend (TestServer) -> Unit) =
        serverTest(listOf(RunnersModule), overrides) { server -> block(server) }

    private suspend fun TestServer.ask(auth: String?, body: String): HttpResponse =
        client.post("/api/v1/cloud/runner/variables") {
            auth?.let { header(HttpHeaders.Authorization, it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun theRunnerOfASessionThatRunsGetsItsOwnersVariablesOnEveryCallUncached() = variablesTest { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val tokens = server.koin.get<RunnerTokens>()
        val token = tokens.mint()
        val running = session(state = SessionState.RUNNING, ownerId = ALICE.toString(), createdAt = T0)
            .copy(epoch = 2, runnerTokenHash = tokens.hash(token), executionStartedAt = T0)
        repository.createWithinCap(running, 5)
        val body = """{"sessionId":"${running.id}","epoch":2}"""

        val first = server.ask("Bearer $token", body)
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        assertEquals("no-store", first.headers[HttpHeaders.CacheControl])
        assertEquals(
            RunnerVariablesResponse(mapOf("OPENROUTER_API_KEY" to "sk-or-alice"), listOf("BROKEN")),
            first.body<RunnerVariablesResponse>(),
        )
        answer = RunnerVariableValues.NONE
        assertEquals(RunnerVariablesResponse(emptyMap(), emptyList()), server.ask("Bearer $token", body).body())
        assertEquals(listOf(ORG_A to ALICE.toString(), ORG_A to ALICE.toString()), asked, "Resolved on every call")

        val fenced = server.ask("Bearer $token", """{"sessionId":"${running.id}","epoch":1}""")
        assertEquals(HttpStatusCode.Conflict, fenced.status)
        val other = server.ask("Bearer $token", """{"sessionId":"${Uuid.random()}","epoch":2}""")
        assertEquals(HttpStatusCode.Conflict, other.status)
        assertEquals(HttpStatusCode.Unauthorized, server.ask("Bearer ostrom_runner_wrong", body).status)
        assertEquals(HttpStatusCode.Unauthorized, server.ask(null, body).status)
        assertEquals(HttpStatusCode.Unauthorized, server.ask("Bearer alice", body).status, "A member's token")
        assertEquals(HttpStatusCode.Unauthorized, server.ask("Bearer x", "{not json").status, "Body unread")
        assertEquals(HttpStatusCode.BadRequest, server.ask("Bearer $token", "{not json").status)
        val malformed = server.ask("Bearer $token", """{"sessionId":"nope","epoch":2}""")
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        val padded = """{"sessionId":"${running.id}","epoch":2,"pad":"${"x".repeat(5000)}"}"""
        assertEquals(HttpStatusCode.PayloadTooLarge, server.ask("Bearer $token", padded).status)
        assertEquals(2, asked.size, "No refused call resolved anything")
    }

    @Test
    fun aSessionThatNoLongerRunsOrIsBeingDeletedGetsNone() = variablesTest { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val tokens = server.koin.get<RunnerTokens>()
        for ((state, desired) in listOf(
            SessionState.PAUSED to DesiredState.PAUSED,
            SessionState.RUNNING to DesiredState.TERMINATED,
        )) {
            val token = tokens.mint()
            val stopped = session(state = state, desired = desired, createdAt = T0)
                .copy(epoch = 1, runnerTokenHash = tokens.hash(token), executionStartedAt = T0)
            repository.createWithinCap(stopped, 5)
            val refused = server.ask("Bearer $token", """{"sessionId":"${stopped.id}","epoch":1}""")
            assertEquals(HttpStatusCode.Conflict, refused.status, "$state")
            assertTrue("variables_unavailable" in refused.bodyAsText(), refused.bodyAsText())
        }
        assertEquals(emptyList(), asked)
    }

    private companion object {
        val T0: Instant = Instant.parse("2026-10-30T10:00:00Z")
    }
}
