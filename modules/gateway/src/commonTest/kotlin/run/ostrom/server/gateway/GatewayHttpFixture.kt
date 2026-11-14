// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import run.ostrom.server.gateway.domain.ModelCallObserver
import run.ostrom.server.gateway.domain.ModelCallRecord
import run.ostrom.server.gateway.domain.ModelUsageSink
import run.ostrom.server.gateway.domain.SpendDecision
import run.ostrom.server.gateway.domain.SpendGuard
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.GovernanceConfirmationPending
import run.ostrom.server.kernel.auth.GovernanceConfirmations
import run.ostrom.server.kernel.auth.GovernanceRequest
import run.ostrom.server.kernel.auth.GovernanceScopes
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.http.CallCeilingSettings
import run.ostrom.server.kernel.http.CallsInProgress
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsValues
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.RelayScopes
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.sharing.SharingModule
import run.ostrom.server.sharing.presentation.ShareAccountRequest
import run.ostrom.server.testing.TestServer
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * The world of the gateway's HTTP tests on real PostgreSQL: a fake provider behind the gateway's HTTP client, the
 * tokens and their scopes, and what the provider received and the gateway recorded.
 */
abstract class GatewayHttpFixture {
    protected val byToken = mapOf(
        "admin-a" to Gw.adminA,
        "member-a" to Gw.memberA,
        "other-a" to Gw.otherA,
        "admin-b" to Gw.adminB,
        "agent-a" to Gw.memberA,
        "agent-b" to Gw.adminB,
        "device-a" to Gw.memberA,
        "reader-a" to Gw.memberA,
        "use-a" to Gw.memberA,
        "policy-a" to Gw.memberA,
        "inference-admin-a" to Gw.adminA,
        "elevated-a" to Gw.adminA,
        "elevated-member-a" to Gw.memberA,
        // A browser session of Ada signed in more than five minutes ago (web audit n°2 M3).
        "stale-admin-a" to Gw.adminA,
    )

    /** The tokens of `ostrom login --admin`: a person's API token holding the governance scopes. */
    protected val elevated = setOf("elevated-a", "elevated-member-a", "elevated-orphan")

    /** The governance writes a token asked for, held for the console, as `kind METHOD /path scope`. */
    protected val held = mutableListOf<String>()
    protected val requests = mutableListOf<GovernanceRequest>()

    /** Browser sessions hold every right of the user; the others hold what they list. */
    protected val principals = mapOf(
        "agent-a" to setOf(RelayScopes.AGENT_SESSION),
        "agent-b" to setOf(RelayScopes.AGENT_SESSION),
        "orphan" to setOf(RelayScopes.AGENT_SESSION),
        "reader-a" to setOf(RelayScopes.ACCOUNTS_READ),
        "use-a" to setOf(RelayScopes.INFERENCE_USE),
        "policy-a" to setOf(RelayScopes.INFERENCE_READ),
        "inference-admin-a" to setOf(RelayScopes.INFERENCE_ADMIN),
    )

    protected val records = mutableListOf<ModelCallRecord>()
    protected val traces = mutableListOf<ModelCallRecord>()
    protected val received = mutableListOf<HttpRequestData>()
    protected var failing = false

    /** When set, the provider answers with this channel, which the test breaks midway. */
    protected var broken: ByteChannel? = null

    /** Every recorded call, for a test that waits for one the server records after the agent's answer. */
    protected val recorded = Channel<ModelCallRecord>(Channel.UNLIMITED)

    /** The content type of the JSON answers; null for none. */
    protected var jsonType: String? = "application/json"

    protected val authenticator = module {
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String): AuthenticatedPrincipal? = when {
                    token == "device-a" -> AuthenticatedPrincipal(
                        token,
                        PrincipalKind.DEVICE,
                        setOf(RelayScopes.ALL_USER),
                    )
                    token in elevated -> AuthenticatedPrincipal(token, PrincipalKind.USER, GovernanceScopes.ALL)
                    token in principals -> AuthenticatedPrincipal(
                        token,
                        PrincipalKind.SERVICE,
                        principals.getValue(token),
                    )
                    token in byToken -> AuthenticatedPrincipal(
                        token,
                        PrincipalKind.USER,
                        setOf(RelayScopes.ALL_USER),
                        channel = PrincipalChannel.BROWSER,
                        authenticatedAt = Clock.System.now() - if (token.startsWith("stale-")) 10.minutes else ZERO,
                    )
                    else -> null
                }
            }
        }
    }

    protected val overrides: Module = module {
        includes(authenticator)
        single<GovernanceConfirmations> {
            object : GovernanceConfirmations {
                override suspend fun open(request: GovernanceRequest): GovernanceConfirmationPending {
                    held += "${request.write.kind} ${request.method} ${request.path} ${request.scope}"
                    requests += request
                    return GovernanceConfirmationPending("gcf_1", "http://console/confirm/gcf_1", "later")
                }
            }
        }
        single<CallerResolver> {
            object : CallerResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): Caller? =
                    byToken[principal.subjectId]
            }
        }
        // The provider URLs an administrator saved in the console.
        single<InstanceSettings> {
            InstanceSettings { section ->
                assertEquals("gateway", section)
                SettingsValues(
                    mapOf(
                        "anthropicUrl" to "https://anthropic.test",
                        "openaiUrl" to "https://openai.test",
                        "openrouterUrl" to "https://openrouter.test/api",
                        "codexUrl" to "https://codex.test",
                    ),
                )
            }
        }
        single<ModelUsageSink> {
            ModelUsageSink {
                records += it
                recorded.trySend(it)
            }
        }
        single<ModelCallObserver> { ModelCallObserver { traces += it } }
        single<SpendGuard> {
            SpendGuard { request ->
                if (request.memberId == Gw.otherA.memberId) {
                    SpendDecision.Exhausted("Your monthly budget is reached.", Clock.System.now() + 1.hours)
                } else {
                    SpendDecision.Allowed
                }
            }
        }
        single(GatewayModule.HTTP_CLIENT) { HttpClient(MockEngine { request -> provider(request) }) }
    }

    /** One held place (`OSTROM_MAX_HELD_CALLS`), taken in a test by another call; nothing waits for it. */
    protected val oneHeldPlace = CallsInProgress(CallCeilingSettings(held = 1, queueWait = ZERO), { null })

    protected val oneHeldPlaceOnly: Module = module {
        includes(overrides)
        single { oneHeldPlace }
    }

    protected val events = """
        event: message_start
        data: {"type":"message_start","message":{"usage":{"input_tokens":1200,"cache_read_input_tokens":300,"output_tokens":1}}}

        event: content_block_delta
        data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Hello"}}

        event: message_delta
        data: {"type":"message_delta","usage":{"output_tokens":42}}

    """.trimIndent()

    protected val completion = """{"id":"c1","choices":[],"usage":{"prompt_tokens":20,"completion_tokens":3}}"""

    /** The fake provider: an event stream for Anthropic and Codex, a JSON body for OpenAI and OpenRouter. */
    protected fun MockRequestHandleScope.provider(request: HttpRequestData): HttpResponseData {
        check(!failing) { "connection refused" }
        received += request
        broken?.let { return respond(it, HttpStatusCode.OK, headersOf("Content-Type", "text/event-stream")) }
        val stream = request.url.host == "anthropic.test" || request.url.host == "codex.test"
        val type = if (stream) "text/event-stream" else jsonType
        val headers = listOfNotNull(
            type?.let { "Content-Type" to listOf(it) },
            "request-id" to listOf("req_1"),
            "set-cookie" to listOf("x"),
        )
        return respond(
            ByteReadChannel(if (stream) events else completion),
            HttpStatusCode.OK,
            headersOf(*headers.toTypedArray()),
        )
    }

    protected val modules = listOf(SecretsModule, SharingModule, GatewayModule)

    protected suspend fun TestServer.infer(
        path: String,
        body: String,
        block: HttpRequestBuilder.() -> Unit = { header("x-api-key", "agent-a") },
    ): HttpResponse = client.post("/api/v1/inference/v1/$path") {
        block()
        setBody(TextContent(body, ContentType.Application.Json))
    }

    protected suspend fun TestServer.shareClaude() = client.post("/api/v1/accounts") {
        bearerAuth("member-a")
        contentType(ContentType.Application.Json)
        setBody(
            ShareAccountRequest(
                provider = "claude",
                label = "Max",
                providerAccountId = "claude-account-uuid",
                accessToken = "sk-ant-oat01-shared-access",
                refreshToken = "sk-ant-ort01-shared-refresh",
                expiresAt = (Clock.System.now() + 8.hours).toString(),
            ),
        )
    }.also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }


    protected suspend inline fun <reified T : Any> TestServer.putJson(path: String, token: String, body: T) =
        client.put(path) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    protected suspend fun errorField(response: HttpResponse, field: String): String =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue(field)
            .jsonPrimitive.content

}
