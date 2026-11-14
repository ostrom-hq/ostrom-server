// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.runners.domain.CloudEnvironmentSpec
import run.ostrom.server.runners.domain.CloudRepository
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.CloudSetup
import run.ostrom.server.runners.domain.RunnerGitHubToken
import run.ostrom.server.runners.domain.RunnerGitHubTokens
import run.ostrom.server.runners.domain.RunnerTokens
import run.ostrom.server.runners.domain.RunnerVariableValues
import run.ostrom.server.runners.domain.RunnerVariables
import run.ostrom.server.runners.domain.SessionFailure
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.ALICE
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.fakes.testIdentities
import run.ostrom.server.runners.presentation.CloudEnvironmentDto
import run.ostrom.server.runners.presentation.CloudRepositoryDto
import run.ostrom.server.runners.presentation.CloudSessionResponse
import run.ostrom.server.runners.presentation.CloudSetupDto
import run.ostrom.server.runners.presentation.CreateCloudSessionRequest
import run.ostrom.server.runners.presentation.RunnerEnvironmentResponse
import run.ostrom.server.runners.presentation.RunnerGitIdentityDto
import run.ostrom.server.runners.presentation.RunnerHeartbeatResponse
import run.ostrom.server.runners.presentation.toDomain
import run.ostrom.server.runners.presentation.toDto
import run.ostrom.server.runners.presentation.toResponse
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A session's recipe, from its creation to its runner's preparation, against a real PostgreSQL and real HTTP. */
class RunnerEnvironmentPostgresTest {
    private var answer = RunnerVariableValues(mapOf("NPM_TOKEN" to "npm-alice"), emptyList())
    private val members = module {
        single<MemberDirectory> {
            object : MemberDirectory {
                override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean =
                    memberId == ALICE.toString()
            }
        }
    }
    private val overrides = module {
        includes(testIdentities, members)
        single<RunnerVariables> { RunnerVariables { _, _ -> answer } }
        single<RunnerGitHubTokens> {
            object : RunnerGitHubTokens {
                override suspend fun available(): Boolean = true

                override suspend fun token(orgId: Uuid, memberId: String): RunnerGitHubToken =
                    RunnerGitHubToken.NotLinked
            }
        }
        single<MemberLogins> {
            object : MemberLogins {
                override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> = emptyMap()

                override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> =
                    memberIds.filter { it == ALICE }.associateWith { "alice" }
            }
        }
    }

    private fun environmentTest(block: suspend (TestServer) -> Unit) =
        serverTest(listOf(RunnersModule), overrides) { server -> block(server) }

    @Test
    fun withoutTheOrgsModuleThePreparationIsNotServedSoTheRunnerClonesAsBefore() =
        serverTest(listOf(RunnersModule), testIdentities) { server ->
            val (id, token) = server.started()
            for (path in listOf("environment", "preparation")) {
                val body = """{"sessionId":"$id","epoch":1,"phase":"running_setup"}"""
                assertEquals(HttpStatusCode.NotFound, server.runner(path, "Bearer $token", body).status, path)
            }
        }

    @Test
    fun withoutTheMembersLoginsTheOwnerCommitsUnderTheirId() =
        serverTest(listOf(RunnersModule), module { includes(testIdentities, members) }) { server ->
            val (id, token) = server.started(recipe.copy(requiredVariables = emptyList()))
            val asked = server.runner("environment", "Bearer $token", """{"sessionId":"$id","epoch":1}""")
            assertEquals(HttpStatusCode.OK, asked.status, asked.bodyAsText())
            assertEquals(ALICE.toString(), asked.body<RunnerEnvironmentResponse>().git.name)
        }

    private suspend fun TestServer.runner(path: String, auth: String?, body: String): HttpResponse =
        client.post("/api/v1/cloud/runner/$path") {
            auth?.let { header(HttpHeaders.Authorization, it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private val recipe = CloudEnvironmentDto(
        repositories = listOf(CloudRepositoryDto("https://github.com/acme/api", ref = "main")),
        setup = CloudSetupDto(start = listOf("make dev")),
        requiredVariables = listOf("NPM_TOKEN"),
        gitEmailDomain = "acme.dev",
    )

    /** Creates alice's session through the API, then starts its first execution by hand: its runner's token. */
    private suspend fun TestServer.started(environment: CloudEnvironmentDto = recipe): Pair<String, String> {
        val created = client.post("/api/v1/cloud/sessions") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody(CreateCloudSessionRequest(agent = "claude", environment = environment))
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val id = created.body<CloudSessionResponse>().id
        val repository = koin.get<CloudSessionsRepository>()
        val tokens = koin.get<RunnerTokens>()
        val token = tokens.mint()
        val stored = repository.get(ORG_A, Uuid.parse(id)) ?: error("Not stored")
        repository.compareAndSet(
            stored.copy(epoch = 1, runnerTokenHash = tokens.hash(token), executionStartedAt = T0),
            null,
        ) ?: error("Not started")
        return id to token
    }

    private suspend fun TestServer.read(id: String): CloudSessionResponse =
        client.get("/api/v1/cloud/sessions/$id") { bearerAuth("alice") }.body()

    @Test
    fun theRecipeIsStoredAtCreationAndHandedToTheRunnerWithTheOwnersValues() = environmentTest { server ->
        val (id, token) = server.started()
        val shown = server.read(id)
        val named = recipe.repositories.single().copy(path = "api")
        assertEquals(recipe.copy(repositories = listOf(named)), shown.environment)
        val stored = server.koin.get<CloudSessionsRepository>().get(ORG_A, Uuid.parse(id))
        assertEquals(
            CloudEnvironmentSpec(
                repositories = listOf(CloudRepository("https://github.com/acme/api", "api", "main")),
                setup = CloudSetup(start = listOf("make dev")),
                requiredVariables = listOf("NPM_TOKEN"),
                gitEmailDomain = "acme.dev",
            ),
            stored?.environment,
        )

        val asked = server.runner("environment", "Bearer $token", """{"sessionId":"$id","epoch":1}""")
        assertEquals(HttpStatusCode.OK, asked.status, asked.bodyAsText())
        assertEquals("no-store", asked.headers[HttpHeaders.CacheControl])
        val prepared = asked.body<RunnerEnvironmentResponse>()
        assertEquals("api", prepared.environment.workingDirectory)
        assertEquals(RunnerGitIdentityDto("alice", "alice@acme.dev"), prepared.git)
        assertEquals(mapOf("NPM_TOKEN" to "npm-alice"), prepared.variables)
        assertFalse("npm-alice" in prepared.toString(), "Names only in a log line")

        val fenced = server.runner("environment", "Bearer $token", """{"sessionId":"$id","epoch":2}""")
        assertEquals(HttpStatusCode.Conflict, fenced.status)
        assertEquals(HttpStatusCode.Unauthorized, server.runner("environment", "Bearer wrong", "{}").status)
        assertEquals(HttpStatusCode.Unauthorized, server.runner("environment", null, "{not json").status)
        assertEquals(HttpStatusCode.Unauthorized, server.runner("preparation", "Bearer x", "{not json").status)
        val malformed = server.runner("environment", "Bearer $token", """{"sessionId":"nope","epoch":1}""")
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
    }

    @Test
    fun phasesAreShownOnTheSessionAndAFailureFailsItsPreparation() = environmentTest { server ->
        val (id, token) = server.started()
        fun report(phase: String, failure: String? = null): String {
            val failed = if (failure == null) "" else ",\"failure\":$failure"
            return "{\"sessionId\":\"$id\",\"epoch\":1,\"phase\":\"$phase\"$failed}"
        }

        val cloning = server.runner("preparation", "Bearer $token", report("cloning_repositories"))
        assertEquals(HttpStatusCode.OK, cloning.status, cloning.bodyAsText())
        assertEquals(RunnerHeartbeatResponse("run", "creating"), cloning.body<RunnerHeartbeatResponse>())
        assertEquals("cloning_repositories", server.read(id).phase)

        for (bad in listOf(
            report("securing_workspace"),
            report("running_setup", """{"step":"workspace"}"""),
            report("running_setup", """{"step":"setup_start","index":3}"""),
        )) {
            val refused = server.runner("preparation", "Bearer $token", bad)
            assertEquals(HttpStatusCode.BadRequest, refused.status, bad)
        }

        val failed = server.runner(
            "preparation",
            "Bearer $token",
            report("running_setup", """{"step":"setup_start","index":0,"exitCode":2}"""),
        )
        assertEquals(RunnerHeartbeatResponse("stop", "creating"), failed.body<RunnerHeartbeatResponse>())
        val shown = server.read(id)
        assertEquals("preparation_failed", shown.phase)
        assertEquals("preparation_failed", shown.failure?.code)
        assertEquals(false, shown.failure?.retryable)
        assertTrue(shown.failure?.message.orEmpty().startsWith("Setup command 1 (start) failed (exit code 2)."))

        val again = server.runner("environment", "Bearer $token", """{"sessionId":"$id","epoch":1}""")
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertTrue("not_preparing" in again.bodyAsText(), again.bodyAsText())
    }

    @Test
    fun aMissingRequiredVariableFailsThePreparationOnTheSession() = environmentTest { server ->
        answer = RunnerVariableValues.NONE
        val (id, token) = server.started()
        val refused = server.runner("environment", "Bearer $token", """{"sessionId":"$id","epoch":1}""")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertTrue("preparation_failed" in refused.bodyAsText(), refused.bodyAsText())
        val failure = server.read(id).failure
        assertEquals("preparation_failed", failure?.code)
        assertEquals(false, failure?.retryable)
        assertTrue("NPM_TOKEN" in failure?.message.orEmpty())
    }

    @Test
    fun aRecipeBreakingARuleIsRefusedAtCreation() = environmentTest { server ->
        val created = server.client.post("/api/v1/cloud/sessions") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            val outside = CloudEnvironmentDto(workingDirectory = "/etc")
            setBody(CreateCloudSessionRequest(agent = "claude", environment = outside))
        }
        assertEquals(HttpStatusCode.BadRequest, created.status)
    }

    @Test
    fun onlyAPreparationFailureSaysWhetherItIsRetryable() {
        val bare = session(state = SessionState.PAUSED)
        assertNull(bare.toResponse().environment)
        assertNull(bare.copy(failure = SessionFailure("idle_timeout", "Paused.")).toResponse().failure?.retryable)
        val failed = bare.copy(failure = SessionFailure("preparation_failed", "Fetch failed.", retryable = true))
        assertEquals(true, failed.toResponse().failure?.retryable)
    }

    @Test
    fun aRepositoryNamedWithoutAPathIsClonedUnderItsNameAndTheRecipeReadsBackAsAsked() {
        val asked = CloudEnvironmentDto(
            repositories = listOf(
                CloudRepositoryDto("https://github.com/acme/api.git"),
                CloudRepositoryDto("https://github.com/acme/web", path = "apps/web", ref = "v2", branch = "task"),
            ),
            workingDirectory = "apps/web",
            setup = CloudSetupDto(create = listOf("npm ci"), start = listOf("npm start")),
            requiredVariables = listOf("NPM_TOKEN"),
            requireGitHub = true,
            gitEmailDomain = "acme.dev",
        )
        val spec = asked.toDomain()
        assertEquals(listOf("api", "apps/web"), spec.repositories.map { it.path })
        val named = asked.repositories.first().copy(path = "api")
        assertEquals(asked.copy(repositories = listOf(named, asked.repositories.last())), spec.toDto())
    }

    private companion object {
        val T0: Instant = Instant.parse("2026-10-30T10:00:00Z")
    }
}
