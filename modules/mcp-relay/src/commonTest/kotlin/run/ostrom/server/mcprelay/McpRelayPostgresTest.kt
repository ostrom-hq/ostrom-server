// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.TokenAuthenticator
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import run.ostrom.server.mcprelay.domain.ToolPolicy
import run.ostrom.server.mcprelay.domain.StdioSpec
import run.ostrom.server.mcprelay.domain.StdioLauncher
import run.ostrom.server.mcprelay.domain.McpTransport
import run.ostrom.server.mcprelay.domain.McpSharing
import run.ostrom.server.mcprelay.domain.McpAssignment
import run.ostrom.server.mcprelay.domain.HostedAssignment
import run.ostrom.server.mcprelay.domain.McpCall
import run.ostrom.server.mcprelay.domain.McpOperation
import run.ostrom.server.mcprelay.domain.McpOutcome
import run.ostrom.server.mcprelay.domain.McpRelayRepository
import run.ostrom.server.mcprelay.presentation.ArgumentConditionDto
import run.ostrom.server.mcprelay.presentation.ArgumentPolicyRequest
import run.ostrom.server.mcprelay.presentation.McpAssignmentRequest
import run.ostrom.server.mcprelay.presentation.McpCallsPage
import run.ostrom.server.mcprelay.presentation.McpServerDto
import run.ostrom.server.mcprelay.presentation.McpServersPage
import run.ostrom.server.mcprelay.presentation.McpSettingsDto
import run.ostrom.server.mcprelay.presentation.McpUsageResponse
import run.ostrom.server.mcprelay.presentation.StdioSpecDto
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.RelayScopes
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.secrets.infrastructure.executeInOrg
import run.ostrom.server.secrets.infrastructure.fetchInOrg
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Real PostgreSQL and real HTTP on the relay side; the upstream MCP server is a fake answering the Streamable HTTP
 * protocol behind the relay's HTTP client.
 */
class McpRelayPostgresTest : RelayPostgresHarness() {
    /** A `tools/call` message. */
    private fun call(id: Int, tool: String, arguments: String) =
        """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}"""

    /** The journal after a list, an allowed call and a refused one by member-a. */
    private suspend fun TestServer.assertTheJournalOfThoseCalls() {
        val calls = client.get("$admin/calls") { bearerAuth("admin-a") }.body<McpCallsPage>().items
        assertEquals(listOf("DENIED", "OK", "OK"), calls.map { it.outcome })
        assertEquals(listOf(false, true, true), calls.map { it.ok })
        assertEquals(listOf("update_dashboard", "query_loki_logs", "tools/list"), calls.map { it.tool })
        assertEquals(
            1,
            client.get("$admin/calls?server=grafana&dev=${Relay.memberA.memberId}&limit=1") {
                bearerAuth("admin-a")
            }.body<McpCallsPage>().items.size,
        )
        assertEquals(
            emptyList(),
            client.get("$admin/calls") {
                bearerAuth("other-a")
            }.body<McpCallsPage>().items,
            "A member reads only their calls",
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("$admin/calls?dev=nope") {
                bearerAuth("admin-a")
            }.status,
        )
        val usage = client.get("$admin/usage") { bearerAuth("member-a") }.body<McpUsageResponse>()
        assertEquals(mapOf("grafana" to 2L), usage.byServer)
        assertEquals(mapOf("grafana/query_loki_logs" to 1L, "grafana/update_dashboard" to 1L), usage.byTool)
        assertEquals(1L, usage.rows.single().lists)
        assertEquals(1L, usage.rows.single().denied)
        assertEquals(
            3L,
            driver.fetchInOrg(
                Relay.orgA,
                Statement.create("SELECT COUNT(*) AS n FROM mcp_relay_calls"),
            ).getOrThrow().single().get("n").asString().toLong(),
        )
    }

    @Test
    fun aMemberReachesOnlyItsAssignedToolsThroughTheRelay() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            with(server) {
                val grafana = grafana()
                assign(grafana, Relay.memberA.memberId.toString(), listOf("query_loki_logs"))
                val prefix = sendJson(HttpMethod.Put, "$admin/settings", McpSettingsDto("teamcode"))
                assertEquals(HttpStatusCode.OK, prefix.status)

                val mine = client.get("$admin/servers?mine=true") { bearerAuth("member-a") }.body<McpServersPage>()
                assertEquals(listOf("teamcode-grafana"), mine.items.map { it.agentName })
                assertEquals("/api/v1/mcp/grafana", mine.items.single().path)
                assertEquals(listOf("query_loki_logs"), mine.items.single().tools)
                assertEquals(
                    emptyList(),
                    client.get("$admin/servers") {
                        bearerAuth("other-a")
                    }.body<McpServersPage>().items,
                    "Members get their own",
                )
                val all = client.get("$admin/servers") { bearerAuth("admin-a") }.body<McpServersPage>().items.single()
                assertEquals(listOf("GRAFANA_TOKEN"), all.secretNames)
                assertEquals("https://mcp.grafana.example.com/mcp", all.url)

                val initialized =
                    rpc(
                        "grafana",
                        "member-a",
                        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""",
                    )
                assertEquals(HttpStatusCode.OK, initialized.status)
                assertEquals(
                    HttpStatusCode.Accepted,
                    rpc("grafana", "member-a", """{"jsonrpc":"2.0","method":"notifications/initialized"}""").status,
                )

                val listed = Json.parseToJsonElement(
                    rpc("grafana", "member-a", """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""").bodyAsText(),
                )
                val names = listed.jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray.map {
                    it.jsonObject["name"]!!.jsonPrimitive.content
                }
                assertEquals(listOf("query_loki_logs"), names)

                val called =
                    rpc(
                        "grafana",
                        "member-a",
                        call(3, "query_loki_logs", """{"q":"x"}"""),
                    )
                val text = called.bodyAsText()
                assertTrue("3 lines" in text)
                assertFalse("upstream_grafana_token" in text, "The credential never reaches the agent")
                assertTrue(upstreamSeen.all { it.second == "Bearer upstream_grafana_token" }, "Injected server-side")

                val refused =
                    rpc(
                        "grafana",
                        "member-a",
                        """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"update_dashboard"}}""",
                    )
                assertTrue("-32602" in refused.bodyAsText())
                assertFalse(upstreamSeen.any { it.third == "update_dashboard" })

                assertTheJournalOfThoseCalls()
            }
        }

    @Test
    fun argumentPoliciesStopACallBeforeTheUpstream() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            with(server) {
                val grafana = grafana()
                assign(grafana, "all", null)
                val policy =
                    sendJson(
                        HttpMethod.Post,
                        "$admin/servers/grafana/policies",
                        ArgumentPolicyRequest(
                            "*",
                            listOf(ArgumentConditionDto("datasource", "matches", "^prod")),
                            "deny",
                            "Production is read by humans.",
                        ),
                    )
                assertEquals(HttpStatusCode.Created, policy.status, policy.bodyAsText())
                val refused =
                    rpc(
                        "grafana",
                        "member-a",
                        call(1, "query_loki_logs", """{"datasource":"PROD-loki"}"""),
                    )
                assertTrue("Production is read by humans." in refused.bodyAsText())
                assertFalse(upstreamSeen.any { it.first == "tools/call" })
                val allowed =
                    rpc(
                        "grafana",
                        "member-a",
                        call(2, "query_loki_logs", """{"datasource":"staging"}"""),
                    )
                assertTrue("3 lines" in allowed.bodyAsText())
                val badRegex =
                    sendJson(
                        HttpMethod.Post,
                        "$admin/servers/grafana/policies",
                        ArgumentPolicyRequest("*", listOf(ArgumentConditionDto("a", "matches", "(")), "deny", "x"),
                    )
                assertEquals(HttpStatusCode.BadRequest, badRegex.status)
            }
        }

    @Test
    fun anotherOrgNeitherSeesNorReachesTheServer() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            with(server) {
                grafana()
                assertEquals(
                    emptyList(),
                    client.get("$admin/servers") {
                        bearerAuth("admin-b")
                    }.body<McpServersPage>().items,
                )
                assertEquals(
                    emptyList(),
                    client.get("$admin/servers?mine=true") {
                        bearerAuth("admin-b")
                    }.body<McpServersPage>().items,
                )
                assertEquals(
                    HttpStatusCode.NotFound,
                    client.delete("$admin/servers/grafana") {
                        bearerAuth("admin-b")
                    }.status,
                )
                assertEquals(
                    emptyList(),
                    client.get("$admin/calls") {
                        bearerAuth("admin-b")
                    }.body<McpCallsPage>().items,
                )
                assertEquals(
                    HttpStatusCode.NotFound,
                    rpc("grafana", "admin-b", """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").status,
                )
                assertTrue(upstreamSeen.isEmpty())
            }
        }

    @Test
    fun theEndpointRefusesBrowsersOtherVerbsAndBadInput() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            with(server) {
                val grafana = grafana()
                assign(grafana, "all", null)
                assertEquals(
                    HttpStatusCode.Forbidden,
                    rpc(
                        "grafana",
                        "member-a",
                        """{"jsonrpc":"2.0","id":1,"method":"ping"}""",
                        origin = "https://evil.example",
                    ).status,
                )
                assertEquals(
                    HttpStatusCode.MethodNotAllowed,
                    client.get("/api/v1/mcp/grafana") {
                        bearerAuth("member-a")
                    }.status,
                )
                assertEquals(HttpStatusCode.BadRequest, rpc("grafana", "member-a", "not json").status)
                assertEquals(
                    HttpStatusCode.BadRequest,
                    rpc("grafana", "member-a", """[{"jsonrpc":"2.0","id":1,"method":"ping"}]""").status,
                )
                val oldProtocol = client.post("/api/v1/mcp/grafana") {
                    bearerAuth("member-a")
                    header("MCP-Protocol-Version", "2024-11-05")
                    contentType(ContentType.Application.Json)
                    setBody("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")
                }
                assertEquals(HttpStatusCode.BadRequest, oldProtocol.status)
                assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/mcp/grafana") { setBody("{}") }.status)
                assertTheEndpointBoundsItsInput()
            }
        }

    private suspend fun TestServer.assertTheEndpointBoundsItsInput() {
        val current = client.post("/api/v1/mcp/grafana") {
            bearerAuth("member-a")
            header("MCP-Protocol-Version", "2025-11-25")
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")
        }
        assertEquals(HttpStatusCode.OK, current.status, "A supported version passes")
        val huge =
            rpc("grafana", "member-a", """{"jsonrpc":"2.0","id":1,"method":"ping","pad":"${"x".repeat(1 shl 20)}"}""")
        assertEquals(HttpStatusCode.PayloadTooLarge, huge.status)
        assertEquals(
            HttpStatusCode.MethodNotAllowed,
            client.delete("/api/v1/mcp/grafana") { bearerAuth("member-a") }.status,
        )
        val mine = client.get("$admin/servers?mine=true") { bearerAuth("admin-a") }.body<McpServersPage>()
        assertEquals(listOf("ostrom-grafana"), mine.items.map { it.agentName }, "An admin can ask for its own list")
    }

    @Test
    fun serverDeclarationsAreCheckedBeforeTheyAreSaved() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            with(server) {
                grafana()
                val stdio = StdioSpecDto("npm", "mcp-discord@1.2.3")
                val refused = listOf(
                    Triple("member-a", McpServerDto("x", "https://x.example.com", "http"), HttpStatusCode.Forbidden),
                    Triple(
                        "admin-a",
                        McpServerDto("discord", transport = "stdio", stdio = stdio),
                        HttpStatusCode.Conflict,
                    ),
                    Triple("admin-a", McpServerDto("x", "https://x.example.com", "pigeon"), HttpStatusCode.BadRequest),
                    Triple(
                        "admin-a",
                        McpServerDto("grafana", "https://other.example.com", "http"),
                        HttpStatusCode.Conflict,
                    ),
                    Triple(
                        "admin-a",
                        McpServerDto("sentry", "https://sentry.example.com", "http", secretNames = listOf("NOPE")),
                        HttpStatusCode.BadRequest,
                    ),
                    Triple(
                        "admin-a",
                        McpServerDto("sentry", "https://sentry.example.com", "http", scope = "everyone"),
                        HttpStatusCode.BadRequest,
                    ),
                )
                for ((token, declared, status) in refused) {
                    val response = sendJson(HttpMethod.Post, "$admin/servers", declared, token)
                    assertEquals(status, response.status, "${declared.name} ${declared.transport}")
                    if (declared.stdio != null) assertTrue("OSTROM_MCP_HOST_URL" in response.bodyAsText())
                }
            }
        }

    @Test
    fun putSavesOrCreatesAServerAndDeleteRemovesIt() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            with(server) {
                grafana()
                val update =
                    McpServerDto("grafana", "https://mcp.grafana.example.com/mcp", "http", listOf("GRAFANA_TOKEN"))
                val saved = sendJson(HttpMethod.Put, "$admin/servers/grafana", update)
                assertEquals(2, saved.body<McpServerDto>().version)
                val moved = update.copy(url = "https://grafana2.example.com/mcp")
                val refused = sendJson(HttpMethod.Put, "$admin/servers/grafana", moved)
                assertEquals(HttpStatusCode.BadRequest, refused.status, "Its credentials never follow a new host")
                val sentry = McpServerDto("sentry", "https://sentry.example.com/mcp", "http")
                val created = sendJson(HttpMethod.Put, "$admin/servers/sentry", sentry)
                assertEquals(1, created.body<McpServerDto>().version, "PUT creates a missing server")
                val mismatch = sendJson(HttpMethod.Put, "$admin/servers/sentry", sentry.copy(name = "other"))
                assertEquals(HttpStatusCode.BadRequest, mismatch.status)
                val forAll = McpAssignmentRequest(credentialSecret = "MINE")
                val perMember = sendJson(HttpMethod.Put, "$admin/servers/sentry/assignments/all", forAll)
                assertEquals(HttpStatusCode.BadRequest, perMember.status)
                val reads = mapOf(
                    "nope/assignments" to HttpStatusCode.NotFound,
                    "grafana/assignments" to HttpStatusCode.OK,
                    "grafana/policies" to HttpStatusCode.OK,
                )
                for ((path, status) in reads) {
                    assertEquals(status, client.get("$admin/servers/$path") { bearerAuth("admin-a") }.status, path)
                }
                client.delete("$admin/servers/sentry") { bearerAuth("admin-a") }
                val deleted = client.request("$admin/servers/grafana") {
                    method = HttpMethod.Delete
                    bearerAuth("admin-a")
                }
                assertEquals(HttpStatusCode.NoContent, deleted.status)
                val left = client.get("$admin/servers") { bearerAuth("admin-a") }.body<McpServersPage>().items
                assertEquals(emptyList(), left)
            }
        }

    @Test
    fun consoleRoutesRefuseAgentsAndDevicesAndCheckScopes() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            with(server) {
                val grafana = grafana()
                assign(grafana, "all", null)
                val console =
                    listOf("$admin/settings", "$admin/calls", "$admin/usage", "$admin/servers/grafana/assignments")
                for (token in listOf("agent-a", "device-a")) {
                    for (path in console) {
                        assertEquals(
                            HttpStatusCode.Forbidden,
                            client.get(path) {
                                bearerAuth(token)
                            }.status,
                            "$token $path",
                        )
                    }
                    val created = client.post("$admin/servers") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(McpServerDto("other", "https://mcp.other.example.com/mcp", "http"))
                    }
                    assertEquals(HttpStatusCode.Forbidden, created.status, token)
                    assertEquals(
                        HttpStatusCode.Forbidden,
                        client.delete("$admin/servers/grafana") {
                            bearerAuth(token)
                        }.status,
                    )
                }
                val listed = client.get("$admin/servers") { bearerAuth("agent-a") }.body<McpServersPage>()
                assertEquals(listOf("grafana"), listed.items.map { it.name }, "An agent lists the servers it reaches")
                assertEquals(HttpStatusCode.Forbidden, client.get("$admin/servers") { bearerAuth("device-a") }.status)
                val tools = rpc("grafana", "agent-a", """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
                assertEquals(HttpStatusCode.OK, tools.status, "An agent calls the relay")
                val byDevice = rpc("grafana", "device-a", """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
                assertEquals(HttpStatusCode.Forbidden, byDevice.status)

                val full = client.get("$admin/servers") { bearerAuth("cli-a") }.body<McpServersPage>()
                assertEquals(
                    listOf("GRAFANA_TOKEN"),
                    full.items.single().secretNames,
                    "mcp:read reads the console list",
                )
                val write = sendJson(HttpMethod.Put, "$admin/settings", McpSettingsDto("ostrom"), "cli-a")
                assertEquals(HttpStatusCode.Forbidden, write.status, "mcp:read does not change")
                val asAgent = client.get("$admin/servers") { bearerAuth("agent-a") }.body<McpServersPage>()
                assertEquals(emptyList(), asAgent.items.single().secretNames, "An agent never sees secret names")
                val asAdminAgent = client.get("$admin/servers") { bearerAuth("admin-agent-a") }.body<McpServersPage>()
                assertEquals(
                    emptyList(),
                    asAdminAgent.items.single().secretNames,
                    "An admin's agent gets the agent list, without secret names",
                )
            }
        }

    @Test
    fun rowLevelSecurityConfinesEveryTableAndRetentionOnlyDeletesOldCalls() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            with(server) {
                val grafana = grafana()
                assign(grafana, Relay.memberA.memberId.toString(), null)
                sendJson(
                    HttpMethod.Post,
                    "$admin/servers/grafana/policies",
                    ArgumentPolicyRequest("*", listOf(ArgumentConditionDto("a", "equals", "b")), "deny", "x"),
                )
                sendJson(HttpMethod.Put, "$admin/settings", McpSettingsDto("teamcode"))
                rpc("grafana", "member-a", """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
                val tables = listOf(
                    "mcp_relay_servers",
                    "mcp_relay_assignments",
                    "mcp_relay_policies",
                    "mcp_relay_settings",
                    "mcp_relay_calls",
                )
                for (table in tables) {
                    val all = Statement.create("SELECT org_id FROM $table")
                    assertEquals(1, driver.fetchInOrg(Relay.adminA.orgId, all).getOrThrow().size, table)
                    assertEquals(0, driver.fetchInOrg(Relay.adminB.orgId, all).getOrThrow().size, "$table: no leak")
                    val stolen = Statement.create("DELETE FROM $table")
                    assertEquals(0L, driver.executeInOrg(Relay.adminB.orgId, stolen).getOrThrow(), table)
                }
                val repository = koin.get<McpRelayRepository>()
                val old = McpCall(
                    id = Uuid.random(),
                    orgId = Relay.adminB.orgId,
                    memberId = Relay.adminB.memberId,
                    serverId = Uuid.random(),
                    serverName = "old",
                    operation = McpOperation.LIST,
                    tool = null,
                    outcome = McpOutcome.OK,
                    startedAt = Instant.parse("2020-01-01T00:00:00Z"),
                    durationMillis = 1,
                    responseBytes = 1,
                )
                repository.record(old)
                // The old retention setting opens nothing any more (RLS audit R4): only mcp_relay_purge deletes.
                val opened = driver.transaction {
                    execute("SELECT set_config('ostrom.mcp_relay_retention', 'on', true)").getOrThrow()
                    execute("DELETE FROM mcp_relay_calls").getOrThrow()
                }
                assertEquals(0L, opened, "No setting opens the calls of every org")
                assertEquals(1L, repository.purgeCalls(Instant.parse("2021-01-01T00:00:00Z")), "Old calls of any org")
                val left = Statement.create("SELECT id FROM mcp_relay_calls")
                assertEquals(1, driver.fetchInOrg(Relay.adminA.orgId, left).getOrThrow().size, "Recent calls stay")
                assertEquals(
                    1,
                    driver.fetchInOrg(Relay.adminA.orgId, Statement.create("SELECT id FROM mcp_relay_servers"))
                        .getOrThrow().size,
                    "Retention touches nothing else",
                )
            }
        }

    @Test
    fun theRetainSweepReadsTheStdioAssignmentsOfEveryOrgAndNothingElse() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            val repository = server.koin.get<McpRelayRepository>()
            val spec = StdioSpec(StdioLauncher.NPM, "mcp-x@1.0.0", emptyList(), emptyMap(), emptyMap())
            val a = Relay.server("a", transport = McpTransport.STDIO, sharing = McpSharing.PER_MEMBER)
                .copy(stdio = spec)
            val b = Relay.server("b", org = Relay.orgB, transport = McpTransport.STDIO).copy(stdio = spec)
            val http = Relay.server("http")
            for (it in listOf(a, b, http)) repository.createServer(it)
            repository.putAssignment(McpAssignment(a.orgId, a.id, null, ToolPolicy.ALL, null, Relay.epoch))
            repository.putAssignment(
                McpAssignment(a.orgId, a.id, Relay.memberA.memberId, ToolPolicy.ALL, null, Relay.epoch),
            )
            repository.putAssignment(McpAssignment(b.orgId, b.id, null, ToolPolicy.ALL, null, Relay.epoch))
            repository.putAssignment(McpAssignment(http.orgId, http.id, null, ToolPolicy.ALL, null, Relay.epoch))
            val rows = assertNotNull(repository.hostedAssignments(10))
            assertEquals(
                setOf(
                    HostedAssignment(a.orgId, a.id, 1, McpSharing.PER_MEMBER, null, Relay.epoch),
                    HostedAssignment(a.orgId, a.id, 1, McpSharing.PER_MEMBER, Relay.memberA.memberId, Relay.epoch),
                    HostedAssignment(b.orgId, b.id, 1, McpSharing.SHARED, null, Relay.epoch),
                ),
                rows.toSet(),
            )
            assertNull(repository.hostedAssignments(2), "More rows than the limit: none")
            // Outside the router's function, row-level security still confines each org.
            val driver = server.driver
            val all = Statement.create("SELECT server_id FROM mcp_relay_assignments")
            assertEquals(1, driver.fetchInOrg(Relay.orgB, all).getOrThrow().size)
        }

    @Test
    fun withoutIdentityEveryRouteIsRefused() {
        val bare = module {
            single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
            single<TokenAuthenticator> {
                object : TokenAuthenticator {
                    override suspend fun authenticate(token: String) =
                        AuthenticatedPrincipal(token, PrincipalKind.USER, setOf(RelayScopes.ALL_USER))
                }
            }
        }
        serverTest(listOf(SecretsModule, McpRelayModule), bare) { server ->
            assertEquals(HttpStatusCode.NotFound, server.client.get("$admin/servers") { bearerAuth("anyone") }.status)
        }
    }
}
