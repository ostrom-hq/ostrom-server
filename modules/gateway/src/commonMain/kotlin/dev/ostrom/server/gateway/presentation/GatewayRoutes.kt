package dev.ostrom.server.gateway.presentation

import dev.ostrom.server.gateway.domain.ByteSink
import dev.ostrom.server.gateway.domain.GatewaySettings
import dev.ostrom.server.gateway.domain.GetGatewaySettingsUseCase
import dev.ostrom.server.gateway.domain.GetModelPolicyUseCase
import dev.ostrom.server.gateway.domain.InferenceRefusal
import dev.ostrom.server.gateway.domain.InferenceResponder
import dev.ostrom.server.gateway.domain.ModelPolicy
import dev.ostrom.server.gateway.domain.PriceTable
import dev.ostrom.server.gateway.domain.PutGatewaySettingsUseCase
import dev.ostrom.server.gateway.domain.PutModelPolicyUseCase
import dev.ostrom.server.gateway.domain.RefusalKind
import dev.ostrom.server.gateway.domain.RelayInferenceUseCase
import dev.ostrom.server.gateway.domain.WireProtocol
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.presentation.requireCaller
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeFully
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock
import io.ktor.server.routing.get as routeGet
import io.ktor.server.routing.post as routePost

/** What the gateway routes need. */
data class GatewayRoutesDependencies(
    val tokens: TokenAuthenticator,
    val callers: CallerResolver,
    val relay: RelayInferenceUseCase,
    val getSettings: GetGatewaySettingsUseCase,
    val putSettings: PutGatewaySettingsUseCase,
    val getPolicy: GetModelPolicyUseCase,
    val putPolicy: PutModelPolicyUseCase,
    val clock: Clock,
)

/** The largest request an agent may send (conversations with images). */
const val MAX_INFERENCE_REQUEST_BYTES: Long = 32L * 1024 * 1024

/**
 * Where agents point their base URL: `ANTHROPIC_BASE_URL=<server>/api/v1/inference`,
 * `OPENAI_BASE_URL=<server>/api/v1/inference/v1`.
 */
const val INFERENCE_BASE: String = "/api/v1/inference"

private const val MAX_SESSION = 128

/**
 * The gateway routes. The inference endpoints speak the providers' APIs (Anthropic Messages, OpenAI Responses and
 * Chat Completions), take the member's Ostrom token as `Authorization: Bearer` or `x-api-key`, and answer errors in
 * the provider's shape so agents handle them as usual. Settings and model policy are under the token's org.
 *
 * @param dependencies The use cases.
 */
fun Route.gatewayRoutes(dependencies: GatewayRoutesDependencies) = with(dependencies) {
    routePost("$INFERENCE_BASE/v1/messages") { infer(dependencies, WireProtocol.MESSAGES) }
    routePost("$INFERENCE_BASE/v1/responses") { infer(dependencies, WireProtocol.RESPONSES) }
    routePost("$INFERENCE_BASE/v1/chat/completions") { infer(dependencies, WireProtocol.CHAT) }
    routeGet("$INFERENCE_BASE/v1/models") {
        val caller = call.inferenceCaller(dependencies, WireProtocol.CHAT) ?: return@routeGet
        val policy = getPolicy(caller)
        val listed = (policy.byMember[caller.memberId.toString()] ?: policy.allowed)
            .ifEmpty { PriceTable.ANTHROPIC_LIST_PRICES.map { it.model } }
        val models = (listOfNotNull(policy.default) + listed).distinct()
        val body = buildJsonObject {
            put("object", "list")
            put(
                "data",
                buildJsonArray {
                    models.forEach { model ->
                        add(
                            buildJsonObject {
                                put("id", model)
                                put("object", "model")
                                put("type", "model")
                            },
                        )
                    }
                },
            )
        }
        call.respondText(body.toString(), ContentType.Application.Json)
    }
    authenticated {
        get<ModelPolicyApi> {
            call.respond(getPolicy(call.requireCaller(callers, null)).toDto())
        }
        put<ModelPolicyApi> {
            val caller = call.requireCaller(callers, null)
            val request = call.receive<ModelPolicyDto>()
            call.respond(putPolicy(caller, ModelPolicy(request.allowed, request.defaultModel, request.byMember)).toDto())
        }
        get<InferenceSettingsApi> {
            call.respond(getSettings(call.requireCaller(callers, null)).toDto())
        }
        put<InferenceSettingsApi> {
            val caller = call.requireCaller(callers, null)
            val request = call.receive<InferenceSettingsDto>()
            val settings = GatewaySettings(request.anthropicKeySecret, request.openaiKeySecret, request.openrouterKeySecret, request.useSharedAccounts)
            call.respond(putSettings(caller, settings).toDto())
        }
    }
}

private fun ModelPolicy.toDto() = ModelPolicyDto(allowed, default, byMember)

private fun GatewaySettings.toDto() = InferenceSettingsDto(anthropicKeySecret, openaiKeySecret, openrouterKeySecret, useSharedAccounts)

private suspend fun RoutingContext.infer(dependencies: GatewayRoutesDependencies, protocol: WireProtocol) {
    val caller = call.inferenceCaller(dependencies, protocol) ?: return
    val bytes = call.receiveChannel().readRemaining(MAX_INFERENCE_REQUEST_BYTES + 1).readByteArray()
    val responder = KtorInferenceResponder(call, dependencies.clock)
    if (bytes.size > MAX_INFERENCE_REQUEST_BYTES) {
        responder.refuse(protocol, InferenceRefusal(RefusalKind.INVALID, "The request exceeds 32 MiB."))
        return
    }
    val headers = call.request.headers.entries().associate { (name, values) -> name.lowercase() to values.joinToString(",") }
    val session = headers["ostrom-session-id"] ?: headers["x-claude-code-session-id"] ?: headers["session_id"]
    dependencies.relay(caller, protocol, bytes.decodeToString(), headers, session?.take(MAX_SESSION), responder)
}

/** The member of an inference call, or null after answering 401 in the provider's shape. */
private suspend fun ApplicationCall.inferenceCaller(dependencies: GatewayRoutesDependencies, protocol: WireProtocol): Caller? {
    val token = request.headers["x-api-key"]
        ?: request.headers["Authorization"]?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(BEARER.length)?.trim()
    val principal = token?.takeIf { it.isNotEmpty() }?.let { dependencies.tokens.authenticate(it) }
    val caller = principal?.let { dependencies.callers.resolve(it, null) }
    if (caller == null) {
        KtorInferenceResponder(this, dependencies.clock)
            .refuse(protocol, InferenceRefusal(RefusalKind.UNAUTHENTICATED, "A valid Ostrom token is required."))
    }
    return caller
}

private const val BEARER = "Bearer "

/**
 * [InferenceResponder] on a Ktor call: provider bytes go out as they arrive, flushed chunk by chunk.
 *
 * @param call The call.
 * @param clock For `retry-after`.
 */
class KtorInferenceResponder(private val call: ApplicationCall, private val clock: Clock) : InferenceResponder {
    override suspend fun refuse(protocol: WireProtocol, refusal: InferenceRefusal) {
        refusal.retryAt?.let { call.response.header("retry-after", (it - clock.now()).inWholeSeconds.coerceAtLeast(1).toString()) }
        call.respondText(errorBody(protocol, refusal).toString(), ContentType.Application.Json, HttpStatusCode.fromValue(refusal.kind.status))
    }

    override suspend fun stream(status: Int, headers: List<Pair<String, String>>, body: suspend (ByteSink) -> Unit) {
        headers.filter { it.first != "content-type" }.forEach { (name, value) -> call.response.header(name, value) }
        val type = headers.firstOrNull { it.first == "content-type" }?.second?.let(::contentTypeOrNull) ?: ContentType.Application.Json
        call.respondBytesWriter(type, HttpStatusCode.fromValue(status)) {
            body(
                ByteSink { bytes, count ->
                    writeFully(bytes, 0, count)
                    flush()
                },
            )
        }
    }

    companion object {
        /** The error in the shape of the agent's API. */
        fun errorBody(protocol: WireProtocol, refusal: InferenceRefusal): JsonObject = if (protocol == WireProtocol.MESSAGES) {
            buildJsonObject {
                put("type", "error")
                putJsonObject("error") {
                    put("type", anthropicType(refusal.kind))
                    put("message", refusal.message)
                }
            }
        } else {
            buildJsonObject {
                putJsonObject("error") {
                    put("message", refusal.message)
                    put("type", openAiType(refusal.kind))
                    put("code", refusal.kind.name.lowercase())
                }
            }
        }

        private fun contentTypeOrNull(value: String): ContentType? = try {
            ContentType.parse(value)
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun anthropicType(kind: RefusalKind) = when (kind) {
            RefusalKind.INVALID -> "invalid_request_error"
            RefusalKind.UNAUTHENTICATED -> "authentication_error"
            RefusalKind.MODEL_NOT_ALLOWED -> "permission_error"
            RefusalKind.BUDGET_EXHAUSTED, RefusalKind.NO_CAPACITY -> "rate_limit_error"
            RefusalKind.NO_CREDENTIAL, RefusalKind.UPSTREAM_UNREACHABLE -> "api_error"
        }

        private fun openAiType(kind: RefusalKind) = when (kind) {
            RefusalKind.INVALID -> "invalid_request_error"
            RefusalKind.UNAUTHENTICATED -> "authentication_error"
            RefusalKind.MODEL_NOT_ALLOWED -> "permission_error"
            RefusalKind.BUDGET_EXHAUSTED -> "insufficient_quota"
            RefusalKind.NO_CAPACITY -> "rate_limit_exceeded"
            RefusalKind.NO_CREDENTIAL, RefusalKind.UPSTREAM_UNREACHABLE -> "server_error"
        }
    }
}
