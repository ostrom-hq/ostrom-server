// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches

import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.StartWebSessionUseCase
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.identity.presentation.AnswerDeviceRequest
import dev.ostrom.server.identity.presentation.AuthTokenResponse
import dev.ostrom.server.identity.presentation.DeviceCodeRequest
import dev.ostrom.server.identity.presentation.DeviceCodeResponse
import dev.ostrom.server.identity.presentation.DeviceTokenRequest
import dev.ostrom.server.kernel.auth.CSRF_COOKIE
import dev.ostrom.server.kernel.auth.CSRF_HEADER
import dev.ostrom.server.kernel.auth.ORG_HEADER
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.SESSION_COOKIE
import dev.ostrom.server.mcprelay.McpRelayModule
import dev.ostrom.server.mcprelay.domain.CreateMcpServerUseCase
import dev.ostrom.server.mcprelay.presentation.McpServerDto
import dev.ostrom.server.mcprelay.presentation.McpServersPage
import dev.ostrom.server.mcprelay.presentation.toPayload
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.Org
import dev.ostrom.server.orgs.domain.OrgsRepository
import dev.ostrom.server.secrets.SecretsModule
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.MemberRole
import dev.ostrom.server.secrets.domain.SecretCipher
import dev.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * `ostrom login` then `ostrom inbox`, `ostrom logs` and `ostrom claude` (LOGIN-009), on the real identity, orgs,
 * gates, launches and relay modules and PostgreSQL: the token the device flow gives the CLI, bound to no organization,
 * reads its organization's approvals and lists its MCP servers, and reads nothing of an organization it is not in.
 */
class CliTokenReadsPostgresTest {
    private val modules = launchModules(SecretsModule, McpRelayModule)
    private val overrides = module { single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) } }
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    /** A person who owns a new organization with one MCP server, and their CLI token. */
    private class Owner(val orgId: Uuid, val cliToken: String)

    private suspend fun TestServer.owner(login: String, mcpServer: String): Owner {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        val org = Org(Uuid.random(), "$login-team", "$login team", null, null, now)
        val member = Member(Uuid.random(), org.id, user.id, OrgRole.OWNER, now)
        koin.get<OrgsRepository>().create(org, member) { false }
        val dto = McpServerDto(mcpServer, "https://mcp.$mcpServer.example.com/mcp", "http")
        koin.get<CreateMcpServerUseCase>()(Caller(org.id, member.id, MemberRole.OWNER), dto.toPayload(emptyMap()))
        return Owner(org.id, deviceToken(koin.get<StartWebSessionUseCase>()(user.id).let { it.secret to it.csrfToken }))
    }

    // The device flow as the CLI and the console run it: the token is the CLI's own, bound to no organization.
    private suspend fun TestServer.deviceToken(session: Pair<String, String>): String {
        val codes = client.post("/api/v1/auth/device") {
            contentType(ContentType.Application.Json)
            setBody(DeviceCodeRequest("laptop"))
        }.body<DeviceCodeResponse>()
        val answered = client.post("/api/v1/auth/device/answer") {
            header(HttpHeaders.Cookie, "$SESSION_COOKIE=${session.first}; $CSRF_COOKIE=${session.second}")
            header(CSRF_HEADER, session.second)
            header(HttpHeaders.Origin, CONSOLE_ORIGIN)
            contentType(ContentType.Application.Json)
            setBody(AnswerDeviceRequest(codes.userCode, true))
        }
        assertEquals(HttpStatusCode.NoContent, answered.status, answered.bodyAsText())
        val polled = client.post("/api/v1/auth/device/token") {
            contentType(ContentType.Application.Json)
            setBody(DeviceTokenRequest(codes.deviceCode))
        }
        assertEquals(HttpStatusCode.OK, polled.status, polled.bodyAsText())
        return polled.body<AuthTokenResponse>().token
    }

    @Test
    fun theCliTokenReadsItsOrganizationsApprovalsAndMcpServersAndNothingOfAnothers() =
        serverTest(modules, overrides) { server ->
            with(server) {
                val ada = owner("ada", "grafana")
                val bob = owner("bob", "github")
                assertTrue(ada.cliToken.startsWith("osc_"), "The CLI's device token")

                val approvals = client.get("/api/v1/approvals?mine=true") { bearerAuth(ada.cliToken) }
                assertEquals(HttpStatusCode.OK, approvals.status, approvals.bodyAsText())
                assertEquals(0, approvals.body<JsonObject>().getValue("items").jsonArray.size)
                val servers = client.get("/api/v1/mcp/servers") { bearerAuth(ada.cliToken) }
                assertEquals(HttpStatusCode.OK, servers.status, servers.bodyAsText())
                assertEquals(listOf("grafana"), servers.body<McpServersPage>().items.map { it.name }, "Not Bob's")

                val across = client.get("/api/v1/approvals") {
                    bearerAuth(ada.cliToken)
                    header(ORG_HEADER, bob.orgId.toString())
                }
                assertEquals(HttpStatusCode.Forbidden, across.status, "Ada is not in Bob's organization")
                val acrossServers = client.get("/api/v1/mcp/servers") {
                    bearerAuth(ada.cliToken)
                    header(ORG_HEADER, bob.orgId.toString())
                }
                assertEquals(HttpStatusCode.NotFound, acrossServers.status, acrossServers.bodyAsText())
            }
        }

    private companion object {
        /** The console's origin in tests: the default `OSTROM_CONSOLE_URL`. */
        const val CONSOLE_ORIGIN = "http://localhost:5173"
    }
}
