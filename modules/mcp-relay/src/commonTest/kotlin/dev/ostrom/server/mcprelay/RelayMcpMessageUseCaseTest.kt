// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.mcprelay

import dev.ostrom.server.mcprelay.domain.ApprovalDecision
import dev.ostrom.server.mcprelay.domain.ApprovalRequester
import dev.ostrom.server.mcprelay.domain.ArgumentCondition
import dev.ostrom.server.mcprelay.domain.ArgumentPolicy
import dev.ostrom.server.mcprelay.domain.ConditionOperator
import dev.ostrom.server.mcprelay.domain.ListMemberServersUseCaseImpl
import dev.ostrom.server.mcprelay.domain.McpCallLimiter
import dev.ostrom.server.mcprelay.domain.McpLimits
import dev.ostrom.server.mcprelay.domain.McpOperation
import dev.ostrom.server.mcprelay.domain.McpOutcome
import dev.ostrom.server.mcprelay.domain.McpServer
import dev.ostrom.server.mcprelay.domain.McpSettings
import dev.ostrom.server.mcprelay.domain.McpSharing
import dev.ostrom.server.mcprelay.domain.McpTransport
import dev.ostrom.server.mcprelay.domain.NoApprovalRequester
import dev.ostrom.server.mcprelay.domain.PolicyEffect
import dev.ostrom.server.mcprelay.domain.RelayLimits
import dev.ostrom.server.mcprelay.domain.RelayMcpMessageUseCaseImpl
import dev.ostrom.server.mcprelay.domain.UpstreamResult
import dev.ostrom.server.secrets.domain.Caller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class RelayMcpMessageUseCaseTest {
    private val repository = InMemoryRelayRepository()
    private val reveal = FakeReveal()
    private val upstream = FakeUpstream()
    private val clock = TestClock()
    private var approvals: ApprovalRequester = NoApprovalRequester()
    private var limits = McpLimits()

    private fun relay() = RelayMcpMessageUseCaseImpl(
        repository,
        reveal,
        upstream,
        approvals,
        RelayLimits(McpCallLimiter(limits, clock)),
        clock,
    )

    private fun message(method: String?, params: JsonObject? = null, id: JsonElement? = JsonPrimitive(1)): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            if (method != null) put("method", method)
            if (params != null) put("params", params)
        }

    private fun callParams(tool: String, arguments: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
        put("name", tool)
        put("arguments", arguments)
    }

    private fun tool(name: String) = buildJsonObject { put("name", name) }

    private fun text(result: JsonElement?): String =
        result!!.jsonObject["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

    private fun grafana(tools: Set<String>? = null, member: Uuid? = Relay.memberA.memberId): McpServer {
        val server = Relay.server()
        repository.servers += server
        repository.assign(server, member, tools)
        return server
    }

    @Test
    fun batchesAndNonJsonRpcMessagesAreRefused() = runTest {
        grafana()
        val batch = relay()(Relay.memberA, "grafana", JsonArray(listOf(message("ping"))))
        assertEquals(400, batch.status)
        assertEquals(-32600, batch.body!!.jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        val noVersion = relay()(
            Relay.memberA,
            "grafana",
            buildJsonObject {
                put("id", 1)
                put("method", "ping")
            },
        )
        assertEquals(400, noVersion.status)
        assertEquals(JsonPrimitive(1), noVersion.body!!.jsonObject["id"])
    }

    @Test
    fun anUnassignedServerIsUnknown() = runTest {
        grafana(member = Relay.otherA.memberId)
        val answer = relay()(Relay.memberA, "grafana", message("tools/list"))
        assertEquals(404, answer.status)
        assertEquals(404, relay()(Relay.memberA, "nothing", message("ping")).status)
        assertEquals(404, relay()(Relay.adminB, "grafana", message("ping")).status, "Another org never reaches it")
    }

    @Test
    fun notificationsAndClientResponsesAreAccepted() = runTest {
        grafana()
        assertEquals(202, relay()(Relay.memberA, "grafana", message("notifications/initialized", id = null)).status)
        val response = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 3)
            put("result", JsonObject(emptyMap()))
        }
        val answer = relay()(Relay.memberA, "grafana", response)
        assertEquals(202, answer.status)
        assertNull(answer.body)
    }

    @Test
    fun initializeNegotiatesTheProtocolAndPingAnswers() = runTest {
        grafana()
        val asked =
            relay()(
                Relay.memberA,
                "grafana",
                message(
                    "initialize",
                    buildJsonObject {
                        put("protocolVersion", "2025-06-18")
                    },
                ),
            )
        val result = asked.body!!.jsonObject["result"]!!.jsonObject
        assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("ostrom-grafana", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val unknown =
            relay()(
                Relay.memberA,
                "grafana",
                message(
                    "initialize",
                    buildJsonObject {
                        put("protocolVersion", "1999-01-01")
                    },
                ),
            )
        assertEquals(
            "2025-11-25",
            unknown.body!!.jsonObject["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content,
        )
        val ping = relay()(Relay.memberA, "grafana", message("ping", id = JsonPrimitive("p")))
        assertEquals(JsonObject(emptyMap()), ping.body!!.jsonObject["result"])
        assertEquals(JsonPrimitive("p"), ping.body!!.jsonObject["id"])
        val other = relay()(Relay.memberA, "grafana", message("resources/list"))
        assertEquals(-32601, other.body!!.jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        assertTrue(upstream.targets.isEmpty(), "None of these reach the upstream")
    }

    @Test
    fun onlyAssignedToolsAreListed() = runTest {
        grafana(tools = setOf("query_loki_logs", "search_dashboards"))
        upstream.tools = UpstreamResult.Ok(
            listOf(
                tool("query_loki_logs"),
                tool("update_dashboard"),
                tool("search_dashboards"),
                tool("bad name"),
                buildJsonObject {
                    put("x", 1)
                },
            ),
            123,
        )
        val answer = relay()(Relay.memberA, "grafana", message("tools/list"))
        val names = answer.body!!.jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray.map {
            it.jsonObject["name"]!!.jsonPrimitive.content
        }
        assertEquals(listOf("query_loki_logs", "search_dashboards"), names)
        val journaled = repository.journal.single()
        assertEquals(McpOperation.LIST, journaled.operation)
        assertEquals(McpOutcome.OK, journaled.outcome)
        assertEquals(123, journaled.responseBytes)
    }

    @Test
    fun aFailedListingIsAJsonRpcErrorAndJournaled() = runTest {
        grafana()
        upstream.tools = UpstreamResult.Failure(McpOutcome.TIMEOUT, "late")
        val answer = relay()(Relay.memberA, "grafana", message("tools/list"))
        // An empty list would read as "this server has no tools": the client must see that the server failed.
        assertEquals(200, answer.status)
        val error = answer.body!!.jsonObject["error"]!!.jsonObject
        assertEquals(-32603, error["code"]!!.jsonPrimitive.content.toInt())
        assertEquals("The MCP server could not list its tools: late", error["message"]!!.jsonPrimitive.content)
        assertEquals(McpOutcome.TIMEOUT, repository.journal.single().outcome)
    }

    @Test
    fun anUnassignedToolIsUnknownAndJournaledAsDenied() = runTest {
        grafana(tools = setOf("query_loki_logs"))
        val answer = relay()(Relay.memberA, "grafana", message("tools/call", callParams("update_dashboard")))
        assertEquals(-32602, answer.body!!.jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        assertTrue(upstream.calls.isEmpty())
        assertEquals(McpOutcome.DENIED, repository.journal.single().outcome)
        assertEquals("update_dashboard", repository.journal.single().tool)
        val nameless = relay()(Relay.memberA, "grafana", message("tools/call", buildJsonObject { put("name", 3) }))
        assertEquals(-32602, nameless.body!!.jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        val invalid = relay()(Relay.memberA, "grafana", message("tools/call", callParams("a b")))
        assertEquals(-32602, invalid.body!!.jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun anAllowedCallIsRelayedWithItsCredentialAndScrubbed() = runTest {
        val secret = Uuid.random()
        reveal.values[secret] = "glsa_secret_token_1"
        val server = Relay.server(credential = secret)
        repository.servers += server
        repository.assign(server, null)
        upstream.call = UpstreamResult.Ok(
            buildJsonObject {
                put(
                    "content",
                    Json.parseToJsonElement("""[{"type":"text","text":"token glsa_secret_token_1 leaked"}]"""),
                )
            },
            40,
        )
        val arguments = buildJsonObject { put("query", "{app=\"api\"}") }
        val answer = relay()(Relay.memberA, "grafana", message("tools/call", callParams("query_loki_logs", arguments)))
        assertEquals(200, answer.status)
        assertFalse("glsa_secret_token_1" in answer.body.toString(), "Secrets never reach the agent")
        assertEquals("query_loki_logs" to arguments, upstream.calls.single())
        val target = upstream.targets.single()
        assertEquals("glsa_secret_token_1", target.credential!!.reveal())
        assertEquals("${server.orgId}:${server.id}:1", target.processKey)
        val journaled = repository.journal.single()
        assertEquals(McpOutcome.OK, journaled.outcome)
        assertEquals(Relay.memberA.memberId, journaled.memberId)
        assertEquals(40, journaled.responseBytes)
    }

    @Test
    fun aPerMemberServerUsesTheMembersCredentialAndProcess() = runTest {
        val own = Uuid.random()
        reveal.values[own] = "member_own_token"
        val server = Relay.server(transport = McpTransport.STDIO, sharing = McpSharing.PER_MEMBER).copy(
            stdio = dev.ostrom.server.mcprelay.domain.StdioSpec(
                dev.ostrom.server.mcprelay.domain.StdioLauncher.NPM,
                "@acme/mcp@1.0.0",
                secretEnv = mapOf("ACME_TOKEN" to own),
            ),
        )
        repository.servers += server
        repository.assign(server, Relay.memberA.memberId, credential = own)
        relay()(Relay.memberA, "grafana", message("tools/call", callParams("x")))
        val target = upstream.targets.single()
        assertEquals("member_own_token", target.credential!!.reveal())
        assertEquals("member_own_token", target.secretEnv.getValue("ACME_TOKEN").reveal())
        assertTrue(target.processKey.endsWith(":${Relay.memberA.memberId}:${Relay.epoch}"))
    }

    @Test
    fun oddlyShapedMessagesAreAnsweredWithoutReachingTheUpstream() = runTest {
        grafana()
        fun raw(json: String) = Json.parseToJsonElement(json)
        val numberMethod = relay()(Relay.memberA, "grafana", raw("""{"jsonrpc":"2.0","id":1,"method":5}"""))
        assertEquals(202, numberMethod.status, "A method that is not a string is no request")
        val objectMethod = relay()(Relay.memberA, "grafana", raw("""{"jsonrpc":"2.0","id":1,"method":{}}"""))
        assertEquals(202, objectMethod.status)
        val oddVersion = relay()(
            Relay.memberA,
            "grafana",
            raw("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":{}}}"""),
        )
        assertEquals(
            "2025-11-25",
            oddVersion.body!!.jsonObject["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content,
        )
        val noParams = relay()(Relay.memberA, "grafana", raw("""{"jsonrpc":"2.0","id":1,"method":"initialize"}"""))
        assertEquals(200, noParams.status)
        val versionObject = relay()(Relay.memberA, "grafana", raw("""{"jsonrpc":{},"id":1,"method":"ping"}"""))
        assertEquals(400, versionObject.status)
        for (params in listOf("""{"name":5}""", """{"name":{}}""", """[]""")) {
            val call = relay()(
                Relay.memberA,
                "grafana",
                raw("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":$params}"""),
            )
            assertEquals(-32602, call.body!!.jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        }
        assertTrue(upstream.targets.isEmpty())
        assertEquals(3, repository.journal.count { it.outcome == McpOutcome.DENIED && it.tool == null })
    }

    @Test
    fun aSharedStdioServerGetsItsSecretVariables() = runTest {
        val token = Uuid.random()
        reveal.values[token] = "acme_token_value"
        val server = Relay.server(transport = McpTransport.STDIO).copy(
            stdio = dev.ostrom.server.mcprelay.domain.StdioSpec(
                dev.ostrom.server.mcprelay.domain.StdioLauncher.NPM,
                "@acme/mcp@1.0.0",
                secretEnv = mapOf("ACME_TOKEN" to token),
            ),
        )
        repository.servers += server
        repository.assign(server, null)
        relay()(Relay.memberA, "grafana", message("tools/call", callParams("x")))
        val target = upstream.targets.single()
        assertNull(target.credential)
        assertEquals("acme_token_value", target.secretEnv.getValue("ACME_TOKEN").reveal())
        assertEquals("${server.orgId}:${server.id}:1", target.processKey)
    }

    @Test
    fun aSecretDeletedSinceStopsTheCallInsteadOfRunningWithoutIt() = runTest {
        val token = Uuid.random()
        reveal.values[token] = "acme_token_value"
        val server = Relay.server(transport = McpTransport.STDIO).copy(
            stdio = dev.ostrom.server.mcprelay.domain.StdioSpec(
                dev.ostrom.server.mcprelay.domain.StdioLauncher.NPM,
                "@acme/mcp@1.0.0",
                secretEnv = mapOf("ACME_TOKEN" to token, "GONE" to Uuid.random()),
            ),
        )
        repository.servers += server
        repository.assign(server, null)
        val answer = relay()(Relay.memberA, "grafana", message("tools/call", callParams("x")))
        assertEquals(
            "A secret this MCP server uses no longer exists. Ask an admin to set it again.",
            text(answer.body),
        )
        assertTrue(upstream.targets.isEmpty(), "Fail closed: never started without its variable")
        assertEquals(McpOutcome.UPSTREAM_ERROR, repository.journal.single().outcome)
        val credential = Uuid.random()
        val http = Relay.server("http", credential = credential)
        repository.servers += http
        repository.assign(http, null)
        val missing = relay()(Relay.memberA, "http", message("tools/call", callParams("x")))
        assertEquals(
            "A secret this MCP server uses no longer exists. Ask an admin to set it again.",
            text(missing.body),
        )
        assertTrue(upstream.targets.isEmpty(), "Nor called without its credential")
    }

    @Test
    fun aToolErrorIsJournaledAsSuch() = runTest {
        grafana()
        upstream.call = UpstreamResult.Ok(buildJsonObject { put("isError", true) }, 2)
        relay()(Relay.memberA, "grafana", message("tools/call", callParams("x")))
        assertEquals(McpOutcome.TOOL_ERROR, repository.journal.single().outcome)
    }

    @Test
    fun anUpstreamFailureIsAToolErrorWithoutSecrets() = runTest {
        val secret = Uuid.random()
        reveal.values[secret] = "very_secret_value"
        val server = Relay.server(credential = secret)
        repository.servers += server
        repository.assign(server, null)
        upstream.call = UpstreamResult.Failure(McpOutcome.UPSTREAM_ERROR, "401 for very_secret_value")
        val answer = relay()(Relay.memberA, "grafana", message("tools/call", callParams("x")))
        assertTrue(answer.body!!.jsonObject["result"]!!.jsonObject["isError"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("401 for [REDACTED]", text(answer.body))
        assertEquals(McpOutcome.UPSTREAM_ERROR, repository.journal.single().outcome)
    }

    @Test
    fun aCredentialThatNoLongerDecryptsIsAnUpstreamError() = runTest {
        grafana()
        reveal.failing = true
        val answer = relay()(Relay.memberA, "grafana", message("tools/call", callParams("x")))
        assertEquals("The MCP server could not be reached with its configured credentials.", text(answer.body))
        assertEquals(McpOutcome.UPSTREAM_ERROR, repository.journal.single().outcome)
    }

    @Test
    fun aDenyingPolicyWinsAndIsJournaledWithItsId() = runTest {
        val server = grafana()
        val deny =
            policy(
                server,
                PolicyEffect.DENY,
                "No production deletes.",
                ArgumentCondition("env", ConditionOperator.EQUALS, "prod"),
            )
        policy(
            server,
            PolicyEffect.REQUIRE_APPROVAL,
            "Ask first.",
            ArgumentCondition("env", ConditionOperator.EXISTS, null),
        )
        val prod = buildJsonObject { put("env", "prod") }
        val answer = relay()(Relay.memberA, "grafana", message("tools/call", callParams("delete", prod)))
        assertEquals("Refused by the organization's policy: No production deletes.", text(answer.body))
        assertTrue(upstream.calls.isEmpty())
        assertEquals(McpOutcome.DENIED, repository.journal.single().outcome)
        assertEquals(deny.id, repository.journal.single().policyId)
    }

    @Test
    fun anApprovalPolicyOpensAGate() = runTest {
        val server = grafana()
        policy(
            server,
            PolicyEffect.REQUIRE_APPROVAL,
            "Ask first.",
            ArgumentCondition("env", ConditionOperator.EXISTS, null),
        )
        val seen = mutableListOf<String>()
        approvals = object : ApprovalRequester {
            override suspend fun request(
                caller: Caller,
                server: McpServer,
                tool: String,
                arguments: JsonObject,
                policy: ArgumentPolicy,
            ): ApprovalDecision {
                seen += tool
                return ApprovalDecision.Pending("https://app.ostrom.run/gates/1")
            }
        }
        val answer =
            relay()(
                Relay.memberA,
                "grafana",
                message(
                    "tools/call",
                    callParams(
                        "delete",
                        buildJsonObject {
                            put("env", "dev")
                        },
                    ),
                ),
            )
        assertEquals(
            "This call needs a human approval: Ask first. Approve it here, then retry: https://app.ostrom.run/gates/1",
            text(answer.body),
        )
        assertEquals(listOf("delete"), seen)
        assertEquals(McpOutcome.APPROVAL_REQUIRED, repository.journal.single().outcome)
        approvals = NoApprovalRequester()
        val noGate =
            relay()(
                Relay.memberA,
                "grafana",
                message(
                    "tools/call",
                    callParams(
                        "delete",
                        buildJsonObject {
                            put("env", "dev")
                        },
                    ),
                ),
            )
        assertEquals("This call needs a human approval: Ask first.", text(noGate.body))
    }

    @Test
    fun anApprovedRetryIsRelayedAndAFailingApproverHoldsTheCall() = runTest {
        val server = grafana()
        policy(server, PolicyEffect.REQUIRE_APPROVAL, "Ask first.", ArgumentCondition("env", ConditionOperator.EXISTS))
        var decide: () -> ApprovalDecision = { ApprovalDecision.Approved }
        approvals = object : ApprovalRequester {
            override suspend fun request(
                caller: Caller,
                server: McpServer,
                tool: String,
                arguments: JsonObject,
                policy: ArgumentPolicy,
            ): ApprovalDecision = decide()
        }
        val arguments = buildJsonObject { put("env", "prod") }
        relay()(Relay.memberA, "grafana", message("tools/call", callParams("delete", arguments)))
        assertEquals(listOf("delete" to arguments), upstream.calls, "Approved: the retry reaches the upstream")
        assertEquals(McpOutcome.OK, repository.journal.single().outcome)
        decide = { error("gates down") }
        val held = relay()(Relay.memberA, "grafana", message("tools/call", callParams("delete", arguments)))
        assertEquals(
            "This call needs a human approval: Ask first. No approval could be opened right now; retry shortly.",
            text(held.body),
        )
        assertEquals(1, upstream.calls.size, "Fail closed: a failing approver never lets the call through")
        assertEquals(McpOutcome.APPROVAL_REQUIRED, repository.journal.last().outcome)
        decide = { throw kotlinx.coroutines.CancellationException("gone") }
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            relay()(Relay.memberA, "grafana", message("tools/call", callParams("delete", arguments)))
        }
    }

    @Test
    fun theRateLimitRefusesWithoutReachingTheUpstream() = runTest {
        grafana()
        limits = McpLimits(memberPerMinute = 1)
        val relay = relay()
        relay(Relay.memberA, "grafana", message("tools/call", callParams("x")))
        val refused = relay(Relay.memberA, "grafana", message("tools/call", callParams("x")))
        assertTrue("Too many MCP operations" in text(refused.body))
        assertEquals(1, upstream.calls.size)
        assertEquals(listOf(McpOutcome.OK, McpOutcome.RATE_LIMITED), repository.journal.map { it.outcome })
    }

    @Test
    fun aCancelledCallIsJournaledAndReleasesItsSlot() = runTest {
        grafana()
        limits = McpLimits(memberConcurrency = 1)
        val relay = relay()
        val started = CompletableDeferred<Unit>()
        upstream.onCall = {
            started.complete(Unit)
            awaitCancellation()
        }
        val call = async { relay(Relay.memberA, "grafana", message("tools/call", callParams("x"))) }
        started.await()
        call.cancel(CancellationException("agent left"))
        call.join()
        upstream.onCall = {}
        val next = relay(Relay.memberA, "grafana", message("tools/call", callParams("x")))
        assertEquals(200, next.status)
        assertEquals(listOf(McpOutcome.CANCELLED, McpOutcome.OK), repository.journal.map { it.outcome })
    }

    @Test
    fun aJournalFailureNeverBreaksTheAnswer() = runTest {
        grafana()
        repository.failJournal = true
        assertEquals(200, relay()(Relay.memberA, "grafana", message("tools/call", callParams("x"))).status)
        assertEquals(
            200,
            relay()(Relay.memberA, "grafana", message("tools/call", callParams("unassigned is fine too"))).status,
        )
    }

    @Test
    fun theMemberListsItsServersUnderThePrefix() = runTest {
        grafana(tools = setOf("b", "a"))
        val discord = Relay.server("discord")
        repository.servers += discord
        repository.assign(discord, null)
        val hidden = Relay.server("hidden")
        repository.servers += hidden
        repository.assign(hidden, Relay.otherA.memberId)
        repository.putSettings(Relay.orgA, McpSettings("teamcode"))
        val listed = ListMemberServersUseCaseImpl(repository)(Relay.memberA)
        assertEquals(listOf("teamcode-discord", "teamcode-grafana"), listed.map { it.agentName })
        assertEquals(setOf("a", "b"), listed.last().tools)
        assertEquals(null, listed.first().tools)
    }

    @Test
    fun serversWhoseAgentNamesCollideAreBothLeftOut() = runTest {
        listOf("a.b", "a_b", "c").forEach { name ->
            val server = Relay.server(name)
            repository.servers += server
            repository.assign(server, null)
        }
        assertEquals(listOf("ostrom-c"), ListMemberServersUseCaseImpl(repository)(Relay.memberA).map { it.agentName })
    }

    private fun policy(
        server: McpServer,
        effect: PolicyEffect,
        text: String,
        vararg conditions: ArgumentCondition,
    ): ArgumentPolicy {
        val policy =
            ArgumentPolicy(Uuid.random(), server.orgId, server.id, "*", conditions.toList(), effect, text, clock.now())
        repository.policies += policy
        clock.advance()
        return policy
    }
}
