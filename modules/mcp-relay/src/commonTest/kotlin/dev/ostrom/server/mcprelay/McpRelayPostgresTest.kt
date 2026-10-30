package dev.ostrom.server.mcprelay

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.mcprelay.presentation.ArgumentConditionDto
import dev.ostrom.server.mcprelay.presentation.ArgumentPolicyRequest
import dev.ostrom.server.mcprelay.presentation.McpAssignmentRequest
import dev.ostrom.server.mcprelay.presentation.McpCallsResponse
import dev.ostrom.server.mcprelay.presentation.McpServerRequest
import dev.ostrom.server.mcprelay.presentation.McpServerResponse
import dev.ostrom.server.mcprelay.presentation.McpServersResponse
import dev.ostrom.server.mcprelay.presentation.McpSettingsDto
import dev.ostrom.server.mcprelay.presentation.McpUsagesResponse
import dev.ostrom.server.mcprelay.presentation.MemberMcpServersResponse
import dev.ostrom.server.mcprelay.presentation.StdioSpecDto
import dev.ostrom.server.secrets.SecretsModule
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.SecretCipher
import dev.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import dev.ostrom.server.secrets.presentation.CreateSecretRequest
import dev.ostrom.server.secrets.presentation.SecretResponse
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Real PostgreSQL and real HTTP on the relay side; the upstream MCP server is a fake answering the Streamable HTTP
 * protocol behind the relay's HTTP client.
 */
class McpRelayPostgresTest {
    private val byToken = mapOf("admin-a" to Relay.adminA, "member-a" to Relay.memberA, "other-a" to Relay.otherA, "admin-b" to Relay.adminB)

    /** What the fake upstream received: method, authorization, and the tool called. */
    private val upstreamSeen = mutableListOf<Triple<String, String?, String?>>()

    private val fakeMcp = HttpClient(
        MockEngine { request ->
            if (request.method == HttpMethod.Delete) return@MockEngine respond("", HttpStatusCode.OK)
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val method = body["method"]!!.jsonPrimitive.content
            upstreamSeen += Triple(method, request.headers[HttpHeaders.Authorization], body["params"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
            val id = body["id"]
            val result = when (method) {
                "initialize" -> """{"protocolVersion":"2025-11-25","capabilities":{"tools":{}}}"""
                "tools/list" -> """{"tools":[{"name":"query_loki_logs","inputSchema":{}},{"name":"update_dashboard","inputSchema":{}}]}"""
                "tools/call" -> """{"content":[{"type":"text","text":"3 lines, token upstream_grafana_token"}]}"""
                else -> return@MockEngine respond("", HttpStatusCode.Accepted)
            }
            respond("""{"jsonrpc":"2.0","id":$id,"result":$result}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    ) {
        followRedirects = false
        expectSuccess = false
    }

    private val overrides = module {
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = byToken[token]?.let { AuthenticatedPrincipal(token, PrincipalKind.USER, emptySet()) }
            }
        }
        single<CallerResolver> {
            object : CallerResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): Caller? =
                    byToken[principal.subjectId]?.takeIf { orgId == null || it.orgId == orgId }
            }
        }
        single(McpRelayModule.HTTP_CLIENT) { fakeMcp }
    }

    private val admin = "/v1/orgs/${Relay.orgA}/mcp"

    private suspend fun TestServer.rpc(server: String, token: String, body: String, origin: String? = null): HttpResponse =
        client.post("/v1/mcp/$server") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            origin?.let { header(HttpHeaders.Origin, it) }
            setBody(body)
        }

    private suspend fun TestServer.grafana(token: String = "admin-a"): McpServerResponse {
        val secret = client.post("/v1/orgs/${Relay.orgA}/secrets") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(CreateSecretRequest("GRAFANA_TOKEN", "upstream_grafana_token"))
        }.body<SecretResponse>()
        val created = client.post("$admin/servers") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(McpServerRequest("grafana", "http", url = "https://mcp.grafana.example.com/mcp", credentialSecretId = secret.id))
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        return created.body()
    }

    private suspend fun TestServer.assign(server: McpServerResponse, member: String, tools: List<String>?) {
        val response = client.put("$admin/servers/${server.id}/assignments/$member") {
            bearerAuth("admin-a")
            contentType(ContentType.Application.Json)
            setBody(McpAssignmentRequest(tools))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    @Test
    fun aMemberReachesOnlyItsAssignedToolsThroughTheRelay() = serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
        with(server) {
            val grafana = grafana()
            assign(grafana, Relay.memberA.memberId.toString(), listOf("query_loki_logs"))
            val prefix = client.put("$admin/settings") {
                bearerAuth("admin-a")
                contentType(ContentType.Application.Json)
                setBody(McpSettingsDto("teamcode"))
            }
            assertEquals(HttpStatusCode.OK, prefix.status)

            val mine = client.get("/v1/mcp/servers") { bearerAuth("member-a") }.body<MemberMcpServersResponse>()
            assertEquals(listOf("teamcode-grafana"), mine.servers.map { it.agentName })
            assertEquals("/v1/mcp/grafana", mine.servers.single().path)
            assertEquals(emptyList(), client.get("/v1/mcp/servers") { bearerAuth("other-a") }.body<MemberMcpServersResponse>().servers)

            val initialized = rpc("grafana", "member-a", """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")
            assertEquals(HttpStatusCode.OK, initialized.status)
            assertEquals(HttpStatusCode.Accepted, rpc("grafana", "member-a", """{"jsonrpc":"2.0","method":"notifications/initialized"}""").status)

            val listed = Json.parseToJsonElement(rpc("grafana", "member-a", """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""").bodyAsText())
            val names = listed.jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
            assertEquals(listOf("query_loki_logs"), names)

            val called = rpc("grafana", "member-a", """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"query_loki_logs","arguments":{"q":"x"}}}""")
            val text = called.bodyAsText()
            assertTrue("3 lines" in text)
            assertFalse("upstream_grafana_token" in text, "The credential never reaches the agent")
            assertTrue(upstreamSeen.all { it.second == "Bearer upstream_grafana_token" }, "Injected server-side")

            val refused = rpc("grafana", "member-a", """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"update_dashboard"}}""")
            assertTrue("-32602" in refused.bodyAsText())
            assertFalse(upstreamSeen.any { it.third == "update_dashboard" })

            val calls = client.get("$admin/calls") { bearerAuth("admin-a") }.body<McpCallsResponse>().calls
            assertEquals(listOf("DENIED", "OK", "OK"), calls.map { it.outcome })
            assertEquals(listOf("update_dashboard", "query_loki_logs", null), calls.map { it.tool })
            assertEquals(HttpStatusCode.Forbidden, client.get("$admin/calls") { bearerAuth("member-a") }.status)
            val usage = client.get("$admin/usage") { bearerAuth("member-a") }.body<McpUsagesResponse>().usage.single()
            assertEquals(2L, usage.calls)
            assertEquals(1L, usage.lists)
            assertEquals(1L, usage.denied)
            assertEquals(3L, driver.fetchAll("SELECT COUNT(*) AS n FROM mcp_relay_calls").getOrThrow().single().get("n").asString().toLong())
        }
    }

    @Test
    fun argumentPoliciesStopACallBeforeTheUpstream() = serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
        with(server) {
            val grafana = grafana()
            assign(grafana, "all", null)
            val policy = client.post("$admin/servers/${grafana.id}/policies") {
                bearerAuth("admin-a")
                contentType(ContentType.Application.Json)
                setBody(ArgumentPolicyRequest("*", listOf(ArgumentConditionDto("datasource", "matches", "^prod")), "deny", "Production is read by humans."))
            }
            assertEquals(HttpStatusCode.Created, policy.status, policy.bodyAsText())
            val refused = rpc("grafana", "member-a", """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"query_loki_logs","arguments":{"datasource":"PROD-loki"}}}""")
            assertTrue("Production is read by humans." in refused.bodyAsText())
            assertFalse(upstreamSeen.any { it.first == "tools/call" })
            val allowed = rpc("grafana", "member-a", """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"query_loki_logs","arguments":{"datasource":"staging"}}}""")
            assertTrue("3 lines" in allowed.bodyAsText())
            val badRegex = client.post("$admin/servers/${grafana.id}/policies") {
                bearerAuth("admin-a")
                contentType(ContentType.Application.Json)
                setBody(ArgumentPolicyRequest("*", listOf(ArgumentConditionDto("a", "matches", "(")), "deny", "x"))
            }
            assertEquals(HttpStatusCode.BadRequest, badRegex.status)
        }
    }

    @Test
    fun anotherOrgNeitherSeesNorReachesTheServer() = serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
        with(server) {
            grafana()
            assertEquals(HttpStatusCode.NotFound, client.get("$admin/servers") { bearerAuth("admin-b") }.status)
            val orgB = "/v1/orgs/${Relay.orgB}/mcp/servers"
            assertEquals(emptyList(), client.get(orgB) { bearerAuth("admin-b") }.body<McpServersResponse>().servers)
            assertEquals(emptyList(), client.get("/v1/mcp/servers") { bearerAuth("admin-b") }.body<MemberMcpServersResponse>().servers)
            assertEquals(HttpStatusCode.NotFound, rpc("grafana", "admin-b", """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").status)
            assertTrue(upstreamSeen.isEmpty())
        }
    }

    @Test
    fun theEndpointRefusesBrowsersOtherVerbsAndBadInput() = serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
        with(server) {
            val grafana = grafana()
            assign(grafana, "all", null)
            assertEquals(HttpStatusCode.Forbidden, rpc("grafana", "member-a", """{"jsonrpc":"2.0","id":1,"method":"ping"}""", origin = "https://evil.example").status)
            assertEquals(HttpStatusCode.MethodNotAllowed, client.get("/v1/mcp/grafana") { bearerAuth("member-a") }.status)
            assertEquals(HttpStatusCode.BadRequest, rpc("grafana", "member-a", "not json").status)
            assertEquals(HttpStatusCode.BadRequest, rpc("grafana", "member-a", """[{"jsonrpc":"2.0","id":1,"method":"ping"}]""").status)
            val oldProtocol = client.post("/v1/mcp/grafana") {
                bearerAuth("member-a")
                header("MCP-Protocol-Version", "2024-11-05")
                contentType(ContentType.Application.Json)
                setBody("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, oldProtocol.status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/mcp/grafana") { setBody("{}") }.status)
        }
    }

    @Test
    fun adminRoutesCheckRolesAndInput() = serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
        with(server) {
            val asMember = client.post("$admin/servers") {
                bearerAuth("member-a")
                contentType(ContentType.Application.Json)
                setBody(McpServerRequest("x", "http", url = "https://x.example.com"))
            }
            assertEquals(HttpStatusCode.Forbidden, asMember.status)
            val stdioWithoutHost = client.post("$admin/servers") {
                bearerAuth("admin-a")
                contentType(ContentType.Application.Json)
                setBody(McpServerRequest("discord", "stdio", stdio = StdioSpecDto("npm", "mcp-discord@1.2.3")))
            }
            assertEquals(HttpStatusCode.Conflict, stdioWithoutHost.status)
            assertTrue("OSTROM_MCP_HOST_URL" in stdioWithoutHost.bodyAsText())
            val unknownTransport = client.post("$admin/servers") {
                bearerAuth("admin-a")
                contentType(ContentType.Application.Json)
                setBody(McpServerRequest("x", "carrier-pigeon", url = "https://x.example.com"))
            }
            assertEquals(HttpStatusCode.BadRequest, unknownTransport.status)
            val grafana = grafana()
            val duplicate = client.post("$admin/servers") {
                bearerAuth("admin-a")
                contentType(ContentType.Application.Json)
                setBody(McpServerRequest("grafana", "http", url = "https://other.example.com"))
            }
            assertEquals(HttpStatusCode.Conflict, duplicate.status)
            val deleted = client.request("$admin/servers/${grafana.id}") {
                method = HttpMethod.Delete
                bearerAuth("admin-a")
            }
            assertEquals(HttpStatusCode.NoContent, deleted.status)
            assertEquals(emptyList(), client.get("$admin/servers") { bearerAuth("admin-a") }.body<McpServersResponse>().servers)
        }
    }
}
