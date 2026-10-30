// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.mcprelay

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.mcprelay.presentation.McpAssignmentRequest
import dev.ostrom.server.mcprelay.presentation.McpServerDto
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.RelayScopes
import dev.ostrom.server.secrets.domain.SecretCipher
import dev.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import dev.ostrom.server.secrets.presentation.SecretWriteRequest
import dev.ostrom.server.testing.TestServer
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.module
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * The shared set-up of the relay tests on real PostgreSQL and real HTTP: callers by token, and a fake upstream MCP
 * server answering the Streamable HTTP protocol behind the relay's HTTP client.
 */
abstract class RelayPostgresHarness {
    protected val byToken = mapOf(
        "admin-a" to Relay.adminA,
        "member-a" to Relay.memberA,
        "other-a" to Relay.otherA,
        "admin-b" to Relay.adminB,
        "agent-a" to Relay.memberA,
        "device-a" to Relay.memberA,
        "cli-a" to Relay.adminA,
    )

    /** Browser sessions by default; an agent, a device, and a console token that may only read. */
    protected val principals = mapOf(
        "agent-a" to AuthenticatedPrincipal("agent-a", PrincipalKind.SERVICE, setOf(RelayScopes.AGENT_SESSION)),
        "device-a" to AuthenticatedPrincipal("device-a", PrincipalKind.DEVICE, setOf(RelayScopes.ALL_USER)),
        "cli-a" to AuthenticatedPrincipal("cli-a", PrincipalKind.USER, setOf(RelayScopes.MCP_READ)),
    )

    /** What the fake upstream received: method, authorization, and the tool called. */
    protected val upstreamSeen = mutableListOf<Triple<String, String?, String?>>()

    protected val fakeMcp = HttpClient(
        MockEngine { request ->
            if (request.method == HttpMethod.Delete) return@MockEngine respond("", HttpStatusCode.OK)
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val method = body["method"]!!.jsonPrimitive.content
            upstreamSeen +=
                Triple(
                    method,
                    request.headers[HttpHeaders.Authorization],
                    body["params"]?.jsonObject?.get("name")?.jsonPrimitive?.content,
                )
            val id = body["id"]
            val result = when (method) {
                "initialize" -> """{"protocolVersion":"2025-11-25","capabilities":{"tools":{}}}"""
                "tools/list" ->
                    """{"tools":[{"name":"query_loki_logs","inputSchema":{}},""" +
                        """{"name":"update_dashboard","inputSchema":{}}]}"""
                "tools/call" -> """{"content":[{"type":"text","text":"3 lines, token upstream_grafana_token"}]}"""
                else -> return@MockEngine respond("", HttpStatusCode.Accepted)
            }
            respond(
                """{"jsonrpc":"2.0","id":$id,"result":$result}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    ) {
        followRedirects = false
        expectSuccess = false
    }

    protected val overrides = module {
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = principals[token]
                    ?: byToken[token]?.let {
                        AuthenticatedPrincipal(token, PrincipalKind.USER, setOf(RelayScopes.ALL_USER))
                    }
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

    protected val admin = "/api/v1/mcp"

    protected suspend inline fun <reified T : Any> TestServer.sendJson(
        verb: HttpMethod,
        path: String,
        body: T,
        token: String = "admin-a",
    ): HttpResponse = client.request(path) {
        method = verb
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    protected suspend fun TestServer.rpc(
        server: String,
        token: String,
        body: String,
        origin: String? = null,
    ): HttpResponse = client.post("/api/v1/mcp/$server") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        origin?.let { header(HttpHeaders.Origin, it) }
        setBody(body)
    }

    protected suspend fun TestServer.grafana(token: String = "admin-a"): McpServerDto {
        client.post("/api/v1/secrets") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(SecretWriteRequest("GRAFANA_TOKEN", "upstream_grafana_token"))
        }
        val created = client.post("$admin/servers") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                McpServerDto(
                    "grafana",
                    "https://mcp.grafana.example.com/mcp",
                    "http",
                    secretNames = listOf("GRAFANA_TOKEN"),
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        return created.body()
    }

    protected suspend fun TestServer.assign(server: McpServerDto, member: String, tools: List<String>?) {
        val response =
            sendJson(HttpMethod.Put, "$admin/servers/${server.name}/assignments/$member", McpAssignmentRequest(tools))
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }
}
