// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import run.ostrom.server.gateway.domain.InferenceProvider
import run.ostrom.server.gateway.domain.TokenUsage
import run.ostrom.server.gateway.domain.UpstreamRequests
import run.ostrom.server.gateway.presentation.InferenceSettingsDto
import run.ostrom.server.gateway.presentation.MAX_INFERENCE_REQUEST_BYTES
import run.ostrom.server.gateway.presentation.ModelPolicyDto
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.GovernanceChange
import run.ostrom.server.kernel.auth.GovernanceConfirmationPending
import run.ostrom.server.kernel.auth.GovernanceConfirmer
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.kernel.http.CallKind
import run.ostrom.server.kernel.http.OstromJson
import run.ostrom.server.secrets.domain.RelayScopes
import run.ostrom.server.secrets.infrastructure.executeInOrg
import run.ostrom.server.secrets.infrastructure.fetchInOrg
import run.ostrom.server.secrets.presentation.SecretWriteRequest
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Real PostgreSQL and HTTP, with a fake provider behind the gateway's HTTP client: streams relayed through shared
 * accounts and org keys, usage measured, scopes per route, errors in the provider's shape, orgs kept apart.
 */
class GatewayPostgresTest : GatewayHttpFixture() {
    @Test
    fun aStreamIsRelayedThroughASharedAccountAndMetered() = serverTest(modules, overrides) { server ->
        with(server) {
            shareClaude()
            val body = """{"model":"claude-opus-5","stream":true,"max_tokens":64,""" +
                """"messages":[{"role":"user","content":"Hi"}]}"""
            val answer = infer("messages", body) {
                header("x-api-key", "agent-a")
                header("anthropic-beta", "fine-grained-tool-streaming-2025-05-14")
            }
            assertEquals(HttpStatusCode.OK, answer.status)
            assertEquals("text/event-stream", answer.headers["Content-Type"]?.substringBefore(';'))
            assertEquals("req_1", answer.headers["request-id"])
            assertEquals(null, answer.headers["set-cookie"], "Provider cookies never reach the agent")
            assertEquals(events, answer.bodyAsText(), "Relayed byte for byte")

            val sent = received.single()
            assertEquals("https://anthropic.test/v1/messages", sent.url.toString())
            assertEquals("Bearer sk-ant-oat01-shared-access", sent.headers["Authorization"])
            assertEquals(null, sent.headers["x-api-key"], "The agent's Ostrom token never reaches the provider")
            assertEquals(
                "fine-grained-tool-streaming-2025-05-14,${UpstreamRequests.OAUTH_BETA}",
                sent.headers["anthropic-beta"],
            )
            assertEquals(body, (sent.body as TextContent).text)

            val record = records.single()
            assertEquals(InferenceProvider.CLAUDE_SHARED, record.provider)
            assertEquals(TokenUsage(input = 1200, output = 42, cacheRead = 300), record.usage)
            assertEquals(Gw.orgA, record.orgId)
            assertEquals("agent-a", record.sessionId, "An agent token's session is its launch")
            assertNotNull(record.accountId)
            assertTrue(record.priced)
            assertEquals(listOf(record), traces)

            val models = client.get("/api/v1/inference/v1/models") { bearerAuth("agent-a") }.bodyAsText()
            assertTrue("\"claude-opus-5\"" in models, "Without a policy, every priced model is listed")
        }
    }

    /** S2-53: the session, which drives the shared account's affinity and the usage rows, comes from the token. */
    @Test
    fun aForgedSessionHeaderIsIgnored() = serverTest(modules, overrides) { server ->
        with(server) {
            shareClaude()
            val forged = mapOf(
                "ostrom-session-id" to "someone-else",
                "x-claude-code-session-id" to "someone-else",
                "session_id" to "someone-else",
            )
            for (token in listOf("agent-a", "use-a")) {
                val answer = infer("messages", """{"model":"claude-opus-5","stream":true}""") {
                    header("x-api-key", token)
                    forged.forEach { (name, value) -> header(name, value) }
                }
                assertEquals(HttpStatusCode.OK, answer.status, answer.bodyAsText())
            }
            assertEquals(listOf("agent-a", null), records.map { it.sessionId })
        }
    }

    @Test
    fun aStreamTheProviderBreaksMidwayIsCutForTheAgent() = serverTest(modules, overrides) { server ->
        with(server) {
            shareClaude()
            val upstream = ByteChannel()
            broken = upstream
            val answer = coroutineScope {
                // The provider sends its first event, and the connection resets once the gateway has had time to read
                // it. The wait is in real time, as the gateway runs on the server's threads; and the provider writes
                // no more than that, as the test client only reads the answer once it ends (on Native, a provider
                // writing more than the answer's buffer was never read).
                launch {
                    upstream.writeStringUtf8(events.substringBefore("event: content_block_delta"))
                    upstream.flush()
                    withContext(Dispatchers.Default) {
                        withTimeout(10.seconds) { while (received.isEmpty()) delay(5.milliseconds) }
                        delay(500.milliseconds)
                    }
                    upstream.cancel(IllegalStateException("connection reset"))
                }
                try {
                    infer("messages", """{"model":"claude-opus-5","stream":true}""").bodyAsText()
                } catch (_: Exception) {
                    ""
                }
            }
            assertFalse("message_delta" in answer, "Never ended as if complete")
            var record = recorded.receive()
            while (record.outcome == "ok") record = recorded.receive()
            assertEquals("upstream_broken", record.outcome)
        }
    }

    @Test
    fun changingTheModelKeysFromTheConsoleNeedsASignInOfLessThanFiveMinutes() =
        serverTest(modules, overrides) { server ->
            with(server) {
                val settings = InferenceSettingsDto()
                val stale = putJson("/api/v1/inference/settings", "stale-admin-a", settings)
                assertEquals(HttpStatusCode.Forbidden, stale.status)
                assertTrue("step_up_required" in stale.bodyAsText(), stale.bodyAsText())
                val fresh = putJson("/api/v1/inference/settings", "admin-a", settings)
                assertEquals(HttpStatusCode.OK, fresh.status, fresh.bodyAsText())
            }
        }

    @Test
    fun anOrgKeyServesChatAndOpenRouter() = serverTest(modules, overrides) { server ->
        with(server) {
            for ((name, value) in listOf("openai-key" to "sk-org-openai", "router-key" to "sk-or-org")) {
                val created = client.post("/api/v1/secrets") {
                    bearerAuth("admin-a")
                    contentType(ContentType.Application.Json)
                    setBody(SecretWriteRequest(name, value))
                }
                assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            }
            val settings = InferenceSettingsDto(openaiKeySecret = "openai-key", openrouterKeySecret = "router-key")
            val stored = client.put("/api/v1/inference/settings") {
                bearerAuth("inference-admin-a")
                contentType(ContentType.Application.Json)
                setBody(settings)
            }
            assertEquals(HttpStatusCode.OK, stored.status, stored.bodyAsText())
            assertFalse("sk-org" in stored.bodyAsText(), "Keys are named, never returned")

            val chat = infer("chat/completions", """{"model":"gpt-5","messages":[]}""") {
                bearerAuth("use-a")
            }
            assertEquals(HttpStatusCode.OK, chat.status)
            assertEquals(completion, chat.bodyAsText())
            assertEquals("application/json", chat.headers["Content-Type"]?.substringBefore(';'))
            assertEquals("Bearer sk-org-openai", received.last().headers["Authorization"])
            assertEquals(TokenUsage(input = 20, output = 3), records.last().usage)
            assertEquals(false, records.last().priced, "No price row for OpenAI models")
            assertEquals(null, records.last().sessionId, "A credential that is not an agent session has none")

            val router = infer("chat/completions", """{"model":"meta/llama-4"}""") {
                bearerAuth("member-a")
            }
            assertEquals(HttpStatusCode.OK, router.status)
            assertEquals("https://openrouter.test/api/v1/chat/completions", received.last().url.toString())
            assertEquals("Bearer sk-or-org", received.last().headers["Authorization"])
            assertEquals(InferenceProvider.OPENROUTER, records.last().provider)
            assertEquals(null, records.last().sessionId)

            val responses = infer("responses", """{"model":"gpt-5"}""") { bearerAuth("agent-a") }
            assertEquals(HttpStatusCode.OK, responses.status)
            assertEquals("https://openai.test/v1/responses", received.last().url.toString())
            assertEquals("agent-a", records.last().sessionId)
        }
    }

    @Test
    fun onlyCredentialsWithTheScopeCallModels() = serverTest(modules, overrides) { server ->
        with(server) {
            val body = """{"model":"claude-opus-5"}"""
            val device = infer("messages", body) { header("x-api-key", "device-a") }
            assertEquals(HttpStatusCode.Forbidden, device.status)
            assertEquals("permission_error", errorField(device, "type"))
            val reader = infer("chat/completions", body) { bearerAuth("reader-a") }
            assertEquals(HttpStatusCode.Forbidden, reader.status)
            assertEquals("credential_not_allowed", errorField(reader, "code"))
            for (unauthenticated in listOf<HttpRequestBuilder.() -> Unit>(
                {},
                { header("x-api-key", "nobody") },
                { header("x-api-key", "  ") },
                { header("Authorization", "Basic YWdlbnQtYQ==") },
                { bearerAuth("orphan") },
            )) {
                val refused = infer("messages", body, unauthenticated)
                assertEquals(HttpStatusCode.Unauthorized, refused.status)
                assertEquals("authentication_error", errorField(refused, "type"))
            }
            val openAiShape = infer("responses", body) {}
            assertEquals("unauthenticated", errorField(openAiShape, "code"))
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/inference/v1/models").status)
        }
    }

    @Test
    fun agentsReadThePolicyOnlyInferenceAdminsChangeItAndTheSettings() = serverTest(modules, overrides) { server ->
        with(server) {
            for (token in listOf("agent-a", "policy-a", "member-a")) {
                assertEquals(HttpStatusCode.OK, client.get("/api/v1/policy/models") { bearerAuth(token) }.status, token)
            }
            for (token in listOf("reader-a", "use-a", "device-a")) {
                assertEquals(
                    HttpStatusCode.Forbidden,
                    client.get("/api/v1/policy/models") {
                        bearerAuth(token)
                    }.status,
                    token,
                )
            }
            val policy = ModelPolicyDto(allowed = listOf("claude-"), defaultModel = "claude-sonnet-5")
            for (token in listOf("agent-a", "policy-a")) {
                assertEquals(HttpStatusCode.Forbidden, putJson("/api/v1/policy/models", token, policy).status, token)
            }
            assertEquals(
                HttpStatusCode.Forbidden,
                putJson("/api/v1/policy/models", "member-a", policy).status,
                "Admins only",
            )
            val saved = putJson("/api/v1/policy/models", "inference-admin-a", policy)
            assertEquals(policy, saved.body<ModelPolicyDto>())
            assertEquals(policy, client.get("/api/v1/policy/models") { bearerAuth("agent-a") }.body<ModelPolicyDto>())
            for (token in listOf("agent-a", "policy-a", "member-a")) {
                assertEquals(
                    HttpStatusCode.Forbidden,
                    client.get("/api/v1/inference/settings") {
                        bearerAuth(token)
                    }.status,
                    token,
                )
                assertEquals(
                    HttpStatusCode.Forbidden,
                    putJson("/api/v1/inference/settings", token, InferenceSettingsDto()).status,
                )
            }
            val settings = client.get("/api/v1/inference/settings") { bearerAuth("admin-a") }
            assertEquals(InferenceSettingsDto(), settings.body<InferenceSettingsDto>())
            val keyed = InferenceSettingsDto(anthropicKeySecret = "anthropic-key")
            assertEquals(HttpStatusCode.OK, putJson("/api/v1/inference/settings", "inference-admin-a", keyed).status)
            assertEquals(
                keyed,
                client.get("/api/v1/inference/settings") { bearerAuth("admin-a") }.body<InferenceSettingsDto>(),
            )

            val models = Json.parseToJsonElement(
                client.get("/api/v1/inference/v1/models") { bearerAuth("agent-a") }.bodyAsText(),
            ).jsonObject
            val ids = models.getValue("data").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
            assertEquals(listOf("claude-sonnet-5", "claude-"), ids)
        }
    }

    @Test
    fun theTokenOfAnAdminLoginSetsNoModelPolicyButHoldsItForTheConsoleAndOnlyForAnOwnerOrAdmin() =
        serverTest(modules, overrides) { server ->
            with(server) {
                val policy = ModelPolicyDto(allowed = listOf("claude-"), defaultModel = "claude-sonnet-5")
                val refused = putJson("/api/v1/policy/models", "elevated-member-a", policy)
                assertEquals(HttpStatusCode.Forbidden, refused.status, "A member's elevated token")
                val orphan = putJson("/api/v1/policy/models", "elevated-orphan", policy)
                assertEquals(HttpStatusCode.NotFound, orphan.status, "A token of nobody's organization")
                assertEquals(emptyList(), held, "Refused before anything is held")
                val asked = putJson("/api/v1/policy/models", "elevated-a", policy)
                assertEquals(HttpStatusCode.Accepted, asked.status)
                assertEquals("gcf_1", asked.body<GovernanceConfirmationPending>().confirmationId)
                assertEquals(listOf("models.policy PUT /api/v1/policy/models policy:write"), held)
                val unchanged = client.get("/api/v1/policy/models") { bearerAuth("admin-a") }.body<ModelPolicyDto>()
                assertEquals(ModelPolicyDto(), unchanged, "Nothing written")
                // Confirmed in the console: shown before and after, then set as the confirmer's.
                val change = koin.get<GovernanceChange>(named("gateway"))
                assertEquals(setOf("models.policy"), change.kinds)
                val write = requests.single().write
                val shown = change.preview(Gw.orgA, write)
                assertEquals(OstromJson.encodeToJsonElement(ModelPolicyDto.serializer(), unchanged), shown.before)
                assertEquals(OstromJson.encodeToJsonElement(ModelPolicyDto.serializer(), policy), shown.after)
                val admin = GovernanceConfirmer(
                    AuthenticatedPrincipal("admin-a", PrincipalKind.USER, setOf(RelayScopes.ALL_USER)),
                    OrgPrincipal(Gw.orgA, Gw.adminA.memberId, OrgRole.ADMIN),
                )
                val stranger = admin.copy(principal = admin.principal.copy(subjectId = "nobody"))
                assertFailsWith<OstromException> { change.apply(Gw.orgA, write, stranger) }
                assertEquals(shown.after, change.apply(Gw.orgA, write, admin))
                val set = client.get("/api/v1/policy/models") { bearerAuth("admin-a") }.body<ModelPolicyDto>()
                assertEquals(policy, set)
                // The console and the service key write at once.
                assertEquals(HttpStatusCode.OK, putJson("/api/v1/policy/models", "admin-a", policy).status)
                assertEquals(HttpStatusCode.OK, putJson("/api/v1/policy/models", "inference-admin-a", policy).status)
                assertEquals(1, held.size)
                // policy:write opens the model policy, never the inference settings.
                val settings = putJson("/api/v1/inference/settings", "elevated-a", InferenceSettingsDto())
                assertEquals(HttpStatusCode.Forbidden, settings.status)
            }
        }

    /**
     * Past the replica's ceiling of held calls (`OSTROM_MAX_HELD_CALLS`), a model call is refused as an overloaded
     * provider refuses, in the agent's API: an agent retries it as it would the provider's 503, and the load pass
     * (ostrom-e2e `load/streams.js`) counts it as refused, never as a cut. The kernel's own `unavailable` body is an
     * Ostrom error that no Anthropic or OpenAI client reads (chart-load 41874269604: 126 of 200 streams).
     */
    @Test
    fun pastTheHeldCeilingAModelCallIsRefusedAsAnOverloadedProviderDoes() =
        serverTest(modules, oneHeldPlaceOnly) { server ->
            with(server) {
                shareClaude()
                // Another call holds the replica's only held place.
                val other = assertNotNull(oneHeldPlace.enter(CallKind.HELD))
                val shapes = listOf("messages" to "overloaded_error", "chat/completions" to "server_error")
                for ((path, type) in shapes) {
                    val refused = infer(path, """{"model":"claude-opus-5","stream":true}""")
                    assertEquals(HttpStatusCode.ServiceUnavailable, refused.status, path)
                    assertEquals(type, errorField(refused, "type"), path)
                    assertEquals("1", refused.headers["retry-after"], path)
                }
                assertTrue(received.isEmpty(), "A refused call never reaches the provider")
                other.leave()
                assertEquals(events, infer("messages", """{"model":"claude-opus-5","stream":true}""").bodyAsText())
            }
        }

    @Test
    fun refusalsTakeTheProviderShape() = serverTest(modules, overrides) { server ->
        with(server) {
            putJson("/api/v1/policy/models", "admin-a", ModelPolicyDto(allowed = listOf("claude-")))
            val notAllowed = infer("chat/completions", """{"model":"gpt-5"}""")
            assertEquals(HttpStatusCode.Forbidden, notAllowed.status)
            assertEquals("model_not_allowed", errorField(notAllowed, "code"))
            assertEquals("false", notAllowed.headers["x-should-retry"], "Retrying never allows the model")
            val invalid = infer("messages", "[]")
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertEquals("invalid_request_error", errorField(invalid, "type"))
            assertEquals("false", invalid.headers["x-should-retry"])
            val budget = infer("messages", """{"model":"claude-opus-5"}""") { header("x-api-key", "other-a") }
            assertEquals(HttpStatusCode.TooManyRequests, budget.status)
            assertEquals("rate_limit_error", errorField(budget, "type"))
            assertTrue(assertNotNull(budget.headers["retry-after"]).toLong() in 3_500..3_600)
            assertEquals("false", budget.headers["x-should-retry"], "Claude Code stops retrying a spent budget")
            val noKey = infer("messages", """{"model":"claude-opus-5"}""")
            assertEquals(HttpStatusCode.ServiceUnavailable, noKey.status)
            assertEquals("api_error", errorField(noKey, "type"))
            assertEquals("no_credential", noKey.headers["x-ostrom-error"], "The gateway's own refusal says so")
            assertEquals("false", noKey.headers["x-should-retry"], "No account comes back by itself")
            val tooLarge = infer("messages", "x".repeat((MAX_INFERENCE_REQUEST_BYTES + 1).toInt()))
            assertEquals(HttpStatusCode.BadRequest, tooLarge.status)
            assertEquals(
                listOf("model_not_allowed", "invalid", "budget_exhausted", "no_credential"),
                records.map {
                    it.outcome
                },
            )
        }
    }

    @Test
    fun aProviderDownIsA502AndAnUnreadableTypeIsJson() = serverTest(modules, overrides) { server ->
        with(server) {
            putJson("/api/v1/inference/settings", "admin-a", InferenceSettingsDto(openaiKeySecret = "openai-key"))
            client.post("/api/v1/secrets") {
                bearerAuth("admin-a")
                contentType(ContentType.Application.Json)
                setBody(SecretWriteRequest("openai-key", "sk-org-openai"))
            }
            failing = true
            val down = infer("chat/completions", """{"model":"gpt-5"}""")
            assertEquals(HttpStatusCode.BadGateway, down.status)
            assertEquals("server_error", errorField(down, "type"))
            failing = false
            for (type in listOf("not a type", null)) {
                jsonType = type
                val answer = infer("chat/completions", """{"model":"gpt-5"}""")
                assertEquals("application/json", answer.headers["Content-Type"]?.substringBefore(';'), type)
                assertEquals(completion, answer.bodyAsText())
            }
            assertEquals(TokenUsage(input = 20, output = 3), records.last().usage, "Metered without a type too")
        }
    }

    @Test
    fun anotherOrgNeitherSeesNorUsesTheSettingsAccountsOrKeys() = serverTest(modules, overrides) { server ->
        with(server) {
            shareClaude()
            putJson("/api/v1/inference/settings", "admin-a", InferenceSettingsDto(anthropicKeySecret = "a-key"))
            putJson("/api/v1/policy/models", "admin-a", ModelPolicyDto(allowed = listOf("claude-")))
            assertEquals(
                InferenceSettingsDto(),
                client.get("/api/v1/inference/settings") {
                    bearerAuth("admin-b")
                }.body(),
            )
            assertEquals(ModelPolicyDto(), client.get("/api/v1/policy/models") { bearerAuth("admin-b") }.body())
            val other = infer("messages", """{"model":"claude-opus-5"}""") { header("x-api-key", "agent-b") }
            assertEquals(HttpStatusCode.ServiceUnavailable, other.status, "Org A's account never serves org B")
            assertTrue(received.isEmpty())

            for (table in listOf("gateway_settings", "gateway_model_policies")) {
                val unfiltered = Statement.create("SELECT org_id FROM $table")
                assertEquals(1, driver.fetchInOrg(Gw.orgA, unfiltered).getOrThrow().size, table)
                assertEquals(0, driver.fetchInOrg(Gw.orgB, unfiltered).getOrThrow().size, "No WHERE, no leak: $table")
                val stolen = Statement.create("DELETE FROM $table")
                assertEquals(0L, driver.executeInOrg(Gw.orgB, stolen).getOrThrow(), table)
            }
            val planted = Statement.create(
                "INSERT INTO gateway_settings (org_id, use_shared_accounts) VALUES (:org, false)",
            ).bind("org", Gw.orgA)
            assertFails("Another org's row is refused") { driver.executeInOrg(Gw.orgB, planted).getOrThrow() }
            val kept = client.get("/api/v1/inference/settings") { bearerAuth("admin-a") }.body<InferenceSettingsDto>()
            assertEquals("a-key", kept.anthropicKeySecret)
        }
    }

    @Test
    fun withNothingBoundNobodyIsAMemberAndEveryDefaultLoads() =
        serverTest(modules, module { includes(authenticator) }) { server ->
            with(server) {
                val refused = infer("messages", """{"model":"claude-opus-5"}""")
                assertEquals(HttpStatusCode.Unauthorized, refused.status, "Without identity's resolver, no org")
                assertEquals(
                    HttpStatusCode.NotFound,
                    client.get("/api/v1/policy/models") {
                        bearerAuth("agent-a")
                    }.status,
                )
                val queue = client.get("/api/v1/inference/queue") { bearerAuth("agent-a") }
                assertEquals(HttpStatusCode.NotFound, queue.status)
                val cancel = client.post("/api/v1/inference/requests/6f1c2b0e-9a4d-4c3e-8b7a-2d5e1f0a9c41/cancel") {
                    bearerAuth("agent-a")
                }
                assertEquals(HttpStatusCode.NotFound, cancel.status)
            }
        }
}
