// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches

import dev.ostrom.server.identity.domain.ApiToken
import dev.ostrom.server.identity.domain.ApiTokensRepository
import dev.ostrom.server.identity.domain.CLI_SCOPES
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.SecretHasher
import dev.ostrom.server.identity.domain.TokenKind
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.launches.presentation.LaunchResponse
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.Org
import dev.ostrom.server.orgs.domain.OrgsRepository
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The end of `ostrom <agent>` (AUDIT-SECU-RUNNER-2 R2b): the CLI ends its launch with the launch's own agent token,
 * which stops acting at once instead of living out its 24 hours. Nothing else ends a launch.
 */
class EndLaunchPostgresTest {
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    private suspend fun TestServer.cliToken(login: String): String {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        val org = Org(Uuid.random(), "$login-team", "$login team", null, null, now)
        koin.get<OrgsRepository>().create(org, Member(Uuid.random(), org.id, user.id, OrgRole.OWNER, now)) { false }
        val secret = "osc_" + Uuid.random().toHexString()
        val token = ApiToken(
            Uuid.random(), user.id, org.id, "cli", TokenKind.CLI, CLI_SCOPES, "hint", null, now, now + 1.hours, null,
            null,
        )
        koin.get<ApiTokensRepository>().create(token, koin.get<SecretHasher>().hash(secret))
        return secret
    }

    private suspend fun TestServer.post(token: String, path: String, body: String): HttpResponse =
        client.post(path) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun TestServer.end(token: String, launchId: String): HttpResponse =
        client.delete("/api/v1/launches/$launchId") { header(HttpHeaders.Authorization, "Bearer $token") }

    private suspend fun TestServer.launch(cliToken: String): LaunchResponse {
        val created = post(cliToken, "/api/v1/launches", LAUNCH)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        return created.body()
    }

    @Test
    fun theLaunchsOwnAgentTokenEndsItAndActsNoMore() =
        serverTest(launchModules()) { server ->
            val ada = server.cliToken("ada")
            val launch = server.launch(ada)
            val started = server.post(launch.agentToken, "/api/v1/sessions", session("s-1"))
            assertEquals(HttpStatusCode.OK, started.status, started.bodyAsText())

            val ended = server.end(launch.agentToken, launch.launchId)
            assertEquals(HttpStatusCode.NoContent, ended.status, ended.bodyAsText())

            val after = server.post(launch.agentToken, "/api/v1/sessions", session("s-2"))
            assertEquals(HttpStatusCode.Unauthorized, after.status, "The ended launch's token acts no more")
            val again = server.end(launch.agentToken, launch.launchId)
            assertEquals(HttpStatusCode.Unauthorized, again.status, "Ending twice finds the token already revoked")
        }

    @Test
    fun noOtherCredentialEndsALaunch() =
        serverTest(launchModules()) { server ->
            val ada = server.cliToken("ada")
            val mallory = server.cliToken("mallory")
            val first = server.launch(ada)
            val second = server.launch(ada)
            val elsewhere = server.launch(mallory)
            // Another launch's agent token, the person's CLI token, another organization's agent token: not found,
            // the same answer as a launch that does not exist.
            for (token in listOf(second.agentToken, ada, elsewhere.agentToken)) {
                val refused = server.end(token, first.launchId)
                assertEquals(HttpStatusCode.NotFound, refused.status, refused.bodyAsText())
            }
            assertEquals(HttpStatusCode.NotFound, server.end(first.agentToken, "lch_unknown").status)
            val anonymous = server.client.delete("/api/v1/launches/${first.launchId}")
            assertEquals(HttpStatusCode.Unauthorized, anonymous.status, "No credential, no answer about the launch")
            val still = server.post(first.agentToken, "/api/v1/sessions", session("s-1"))
            assertEquals(HttpStatusCode.OK, still.status, "The launch nobody else could end still acts")
        }

    private companion object {
        const val LAUNCH = """{"repo":"acme/api","agent":"claude-code","model":"claude-opus-4-1"}"""

        fun session(id: String) = """{"id":"$id","agent":"claude-code","repo":"acme/api"}"""
    }
}
