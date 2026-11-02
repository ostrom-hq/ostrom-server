// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches

import dev.ostrom.server.gates.GatesModule
import dev.ostrom.server.identity.IdentityModule
import dev.ostrom.server.identity.domain.ApiToken
import dev.ostrom.server.identity.domain.ApiTokensRepository
import dev.ostrom.server.identity.domain.CLI_SCOPES
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.SecretHasher
import dev.ostrom.server.identity.domain.TokenKind
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.launches.presentation.LaunchResponse
import dev.ostrom.server.mcprelay.McpRelayModule
import dev.ostrom.server.mcprelay.presentation.McpAssignmentRequest
import dev.ostrom.server.mcprelay.presentation.McpServerDto
import dev.ostrom.server.mcprelay.presentation.McpServersPage
import dev.ostrom.server.orgs.OrgsModule
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.Org
import dev.ostrom.server.orgs.domain.OrgsRepository
import dev.ostrom.server.receipts.ReceiptsModule
import dev.ostrom.server.secrets.SecretsModule
import dev.ostrom.server.secrets.domain.RelayScopes
import dev.ostrom.server.secrets.domain.SecretCipher
import dev.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import dev.ostrom.server.sessions.SessionsModule
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * `ostrom claude` keeps the organization's MCP servers (cli#57): the agent token of a launch lists the servers its
 * person is assigned and reaches them through the relay, on the real identity, orgs, launches, secrets and relay
 * modules and PostgreSQL; another organization's agent sees none of them.
 */
class LaunchRelayCallersPostgresTest {
    private val modules = listOf(
        InstanceModule, IdentityModule, OrgsModule, ReceiptsModule, GatesModule, SessionsModule, LaunchesModule,
        SecretsModule, McpRelayModule,
    )
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    /** The upstream MCP server behind the relay: it answers `initialize` and lists two tools. */
    private val fakeMcp = HttpClient(
        MockEngine { request ->
            if (request.method == HttpMethod.Delete) return@MockEngine respond("", HttpStatusCode.OK)
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val result = when (body["method"]!!.jsonPrimitive.content) {
                "initialize" -> """{"protocolVersion":"2025-11-25","capabilities":{"tools":{}}}"""
                "tools/list" ->
                    """{"tools":[{"name":"query_loki_logs","inputSchema":{}},""" +
                        """{"name":"update_dashboard","inputSchema":{}}]}"""
                else -> return@MockEngine respond("", HttpStatusCode.Accepted)
            }
            respond(
                """{"jsonrpc":"2.0","id":${body["id"]},"result":$result}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    ) {
        followRedirects = false
        expectSuccess = false
    }

    private val overrides = module {
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        single(McpRelayModule.HTTP_CLIENT) { fakeMcp }
    }

    /** The owner of a new organization: their member id, the CLI's token and a console token for the relay. */
    private class Owner(val memberId: Uuid, val cliToken: String, val consoleToken: String)

    private suspend fun TestServer.owner(login: String): Owner {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        val org = Org(Uuid.random(), "$login-team", "$login team", null, null, now)
        val member = Member(Uuid.random(), org.id, user.id, OrgRole.OWNER, now)
        koin.get<OrgsRepository>().create(org, member) { false }
        val tokens = koin.get<ApiTokensRepository>()
        val hasher = koin.get<SecretHasher>()
        suspend fun token(kind: TokenKind, scopes: Set<String>): String {
            val secret = "${kind.prefix}_" + Uuid.random().toHexString()
            val token = ApiToken(
                Uuid.random(), user.id, org.id, kind.name, kind, scopes, "hint", null, now, now + 1.hours, null, null,
            )
            tokens.create(token, hasher.hash(secret))
            return secret
        }
        return Owner(
            member.id,
            token(TokenKind.CLI, CLI_SCOPES),
            token(TokenKind.PERSONAL, setOf(RelayScopes.MCP_ADMIN)),
        )
    }

    private suspend fun TestServer.agentTokenOf(owner: Owner): String {
        val created = client.post("/api/v1/launches") {
            bearerAuth(owner.cliToken)
            contentType(ContentType.Application.Json)
            setBody("""{"repo":"acme/api","agent":"claude-code","model":"claude-opus-4-1"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        return created.body<LaunchResponse>().agentToken
    }

    private suspend fun TestServer.rpc(token: String, body: String): HttpResponse = client.post("/api/v1/mcp/grafana") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun anAgentTokenListsAndReachesTheServersItsPersonIsAssigned() = serverTest(modules, overrides) { server ->
        with(server) {
            val ada = owner("ada")
            val grafana = client.post("/api/v1/mcp/servers") {
                bearerAuth(ada.consoleToken)
                contentType(ContentType.Application.Json)
                setBody(McpServerDto("grafana", "https://mcp.grafana.example.com/mcp", "http"))
            }
            assertEquals(HttpStatusCode.Created, grafana.status, grafana.bodyAsText())
            val assigned = client.put("/api/v1/mcp/servers/grafana/assignments/${ada.memberId}") {
                bearerAuth(ada.consoleToken)
                contentType(ContentType.Application.Json)
                setBody(McpAssignmentRequest(listOf("query_loki_logs")))
            }
            assertEquals(HttpStatusCode.OK, assigned.status, assigned.bodyAsText())
            val agent = agentTokenOf(ada)

            val listed = client.get("/api/v1/mcp/servers") { bearerAuth(agent) }
            assertEquals(HttpStatusCode.OK, listed.status, listed.bodyAsText())
            val mine = listed.body<McpServersPage>().items.single()
            assertEquals("/api/v1/mcp/grafana" to listOf("query_loki_logs"), mine.path to mine.tools)
            assertEquals(emptyList(), mine.secretNames, "An agent never gets the admin's view")

            val init = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}"""
            assertEquals(HttpStatusCode.OK, rpc(agent, init).status)
            val tools = Json.parseToJsonElement(
                rpc(agent, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""").bodyAsText(),
            ).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray.map {
                it.jsonObject["name"]!!.jsonPrimitive.content
            }
            assertEquals(listOf("query_loki_logs"), tools, "Only the person's assigned tools")

            val console = client.post("/api/v1/mcp/servers") {
                bearerAuth(agent)
                contentType(ContentType.Application.Json)
                setBody(McpServerDto("github", "https://mcp.github.example.com/mcp", "http"))
            }
            assertEquals(HttpStatusCode.Forbidden, console.status, "An owner's agent never administers")
        }
    }

    @Test
    fun anotherOrganizationsAgentNeitherSeesNorReachesTheServer() = serverTest(modules, overrides) { server ->
        with(server) {
            val ada = owner("ada")
            val created = client.post("/api/v1/mcp/servers") {
                bearerAuth(ada.consoleToken)
                contentType(ContentType.Application.Json)
                setBody(McpServerDto("grafana", "https://mcp.grafana.example.com/mcp", "http"))
            }
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            val bob = agentTokenOf(owner("bob"))

            val listed = client.get("/api/v1/mcp/servers") { bearerAuth(bob) }
            assertEquals(HttpStatusCode.OK, listed.status, listed.bodyAsText())
            assertEquals(emptyList(), listed.body<McpServersPage>().items)
            val init = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}"""
            assertEquals(HttpStatusCode.NotFound, rpc(bob, init).status)
        }
    }
}
