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
import dev.ostrom.server.mcprelay.domain.AssignMcpServerUseCase
import dev.ostrom.server.mcprelay.domain.CreateMcpServerUseCase
import dev.ostrom.server.mcprelay.domain.McpRelayRepository
import dev.ostrom.server.mcprelay.domain.ToolPolicy
import dev.ostrom.server.mcprelay.presentation.McpServerDto
import dev.ostrom.server.mcprelay.presentation.McpServersPage
import dev.ostrom.server.mcprelay.presentation.toPayload
import dev.ostrom.server.orgs.OrgsModule
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.Org
import dev.ostrom.server.orgs.domain.OrgsRepository
import dev.ostrom.server.receipts.ReceiptsModule
import dev.ostrom.server.runners.RunnersModule
import dev.ostrom.server.secrets.SecretsModule
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.MemberRole
import dev.ostrom.server.secrets.domain.SecretCipher
import dev.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import dev.ostrom.server.sessions.SessionsModule
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import dev.ostrom.server.usage.UsageModule
import dev.ostrom.server.usage.domain.UsageRepository
import dev.ostrom.server.usage.presentation.UsageEventRequest
import dev.ostrom.server.usage.presentation.UsageEventsRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
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
 * person is assigned and reaches them through the relay, on the real identity, orgs, launches, secrets, relay and
 * usage modules and PostgreSQL; its journal rows and usage events name its launch. Another organization's agent sees
 * none of the servers.
 */
class LaunchRelayCallersPostgresTest {
    private val modules = listOf(
        InstanceModule, IdentityModule, OrgsModule, ReceiptsModule, GatesModule, SessionsModule, RunnersModule,
        LaunchesModule, SecretsModule, McpRelayModule, UsageModule,
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

    /** The owner of a new organization, as the relay's admin use cases see them, and the CLI's token. */
    private class Owner(val caller: Caller, val cliToken: String)

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
        return Owner(Caller(org.id, member.id, MemberRole.OWNER), token(TokenKind.CLI, CLI_SCOPES))
    }

    /**
     * The owner declares Grafana, and assigns it to themself with [tools] unless null. Through the use cases: a console
     * write needs a browser session (S2-52), which a test of the agent's routes does not need to sign in for.
     */
    private suspend fun TestServer.grafana(owner: Owner, tools: Set<String>?) {
        val dto = McpServerDto("grafana", "https://mcp.grafana.example.com/mcp", "http")
        val server = koin.get<CreateMcpServerUseCase>()(owner.caller, dto.toPayload(emptyMap()))
        if (tools != null) {
            koin.get<AssignMcpServerUseCase>()(owner.caller, server.id, owner.caller.memberId, ToolPolicy(tools), null)
        }
    }

    private suspend fun TestServer.launch(owner: Owner): LaunchResponse {
        val created = client.post("/api/v1/launches") {
            bearerAuth(owner.cliToken)
            contentType(ContentType.Application.Json)
            setBody("""{"repo":"acme/api","agent":"claude-code","model":"claude-opus-4-1"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        return created.body<LaunchResponse>()
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
            grafana(ada, setOf("query_loki_logs"))
            val launched = launch(ada)
            val agent = launched.agentToken

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
            val journal = koin.get<McpRelayRepository>().listCalls(ada.caller.orgId, null, 10, null, null)
            assertEquals(
                listOf(launched.launchId),
                journal.map { it.launchId }.distinct(),
                "The relay's journal names the agent's launch on each of its calls",
            )
            assertEquals(setOf(ada.caller.memberId), journal.map { it.memberId }.toSet())

            val declared = client.post("/api/v1/usage/events") {
                bearerAuth(agent)
                contentType(ContentType.Application.Json)
                setBody(UsageEventsRequest(listOf(UsageEventRequest("skill", "deploy"))))
            }
            assertEquals(HttpStatusCode.Accepted, declared.status, declared.bodyAsText())
            val events = koin.get<UsageRepository>().list(ada.caller.orgId, null, null, 10)
            assertEquals(
                listOf(launched.launchId to ada.caller.memberId),
                events.map { it.launchId to it.memberId },
                "A usage event an agent sends names its launch, from the token",
            )

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
            grafana(ada, tools = null)
            val bob = launch(owner("bob")).agentToken

            val listed = client.get("/api/v1/mcp/servers") { bearerAuth(bob) }
            assertEquals(HttpStatusCode.OK, listed.status, listed.bodyAsText())
            assertEquals(emptyList(), listed.body<McpServersPage>().items)
            val init = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}"""
            assertEquals(HttpStatusCode.NotFound, rpc(bob, init).status)
        }
    }
}
