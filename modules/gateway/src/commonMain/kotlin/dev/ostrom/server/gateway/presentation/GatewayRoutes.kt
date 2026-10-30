package dev.ostrom.server.gateway.presentation

import dev.ostrom.server.gateway.domain.GatewaySettings
import dev.ostrom.server.gateway.domain.InferenceCall
import dev.ostrom.server.gateway.domain.InferenceRefusal
import dev.ostrom.server.gateway.domain.ModelPolicy
import dev.ostrom.server.gateway.domain.PriceTable
import dev.ostrom.server.gateway.domain.RefusalKind
import dev.ostrom.server.gateway.domain.WireProtocol
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.RelayScopes
import dev.ostrom.server.secrets.presentation.mayUse
import dev.ostrom.server.secrets.presentation.requireCaller
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import io.ktor.server.routing.get as routeGet
import io.ktor.server.routing.post as routePost

/** The largest request an agent may send (conversations with images). */
const val MAX_INFERENCE_REQUEST_BYTES: Long = 32L * 1024 * 1024

/**
 * Where agents point their base URL: `ANTHROPIC_BASE_URL=<server>/api/v1/inference`,
 * `OPENAI_BASE_URL=<server>/api/v1/inference/v1`.
 */
const val INFERENCE_BASE: String = "/api/v1/inference"

/** The credentials that call models: an agent session, a personal token with the scope, a browser session. */
private val INFERENCE_SCOPES = arrayOf(RelayScopes.AGENT_SESSION, RelayScopes.INFERENCE_USE)

private const val MAX_SESSION = 128
private const val BEARER = "Bearer "

/** The agent headers never passed down, not even to the planner: its credentials. */
private val CREDENTIAL_HEADERS = setOf("authorization", "x-api-key", "cookie")

/**
 * The gateway routes. The inference endpoints speak the providers' APIs (Anthropic Messages, OpenAI Responses and
 * Chat Completions), take the member's Ostrom token as `Authorization: Bearer` or `x-api-key`, and answer errors in
 * the provider's shape. An agent session reads the model policy; only `inference:admin` (or a browser session)
 * changes it and reads or changes the settings (SECURITY-AUDIT S2-03).
 *
 * @param dependencies The use cases.
 */
fun Route.gatewayRoutes(dependencies: GatewayRoutesDependencies) = with(dependencies) {
    routePost("$INFERENCE_BASE/v1/messages") { infer(dependencies, WireProtocol.MESSAGES) }
    routePost("$INFERENCE_BASE/v1/responses") { infer(dependencies, WireProtocol.RESPONSES) }
    routePost("$INFERENCE_BASE/v1/chat/completions") { infer(dependencies, WireProtocol.CHAT) }
    routeGet("$INFERENCE_BASE/v1/models") {
        call.inferenceCaller(dependencies, WireProtocol.CHAT)?.let { caller ->
            call.respondText(modelList(getPolicy(caller), caller).toString(), ContentType.Application.Json)
        }
    }
    authenticated {
        get<ModelPolicyApi> {
            val caller = call.requireCaller(callers, RelayScopes.INFERENCE_READ, RelayScopes.AGENT_SESSION)
            call.respond(getPolicy(caller).toDto())
        }
        put<ModelPolicyApi> {
            val caller = call.requireCaller(callers, RelayScopes.INFERENCE_ADMIN)
            val request = call.receive<ModelPolicyDto>()
            call.respond(
                putPolicy(caller, ModelPolicy(request.allowed, request.defaultModel, request.byMember)).toDto(),
            )
        }
        get<InferenceSettingsApi> {
            call.respond(getSettings(call.requireCaller(callers, RelayScopes.INFERENCE_ADMIN)).toDto())
        }
        put<InferenceSettingsApi> {
            val caller = call.requireCaller(callers, RelayScopes.INFERENCE_ADMIN)
            val request = call.receive<InferenceSettingsDto>()
            val settings = GatewaySettings(
                request.anthropicKeySecret,
                request.openaiKeySecret,
                request.openrouterKeySecret,
                request.useSharedAccounts,
            )
            call.respond(putSettings(caller, settings).toDto())
        }
    }
}

private fun ModelPolicy.toDto() = ModelPolicyDto(allowed, default, byMember)

private fun GatewaySettings.toDto() =
    InferenceSettingsDto(anthropicKeySecret, openaiKeySecret, openrouterKeySecret, useSharedAccounts)

/** The models the member may name, as `/v1/models` lists them: the default first, every priced model if all are. */
private fun modelList(policy: ModelPolicy, caller: Caller): JsonObject {
    val listed = policy.listFor(caller.memberId).ifEmpty { PriceTable.ANTHROPIC_LIST_PRICES.map { it.model } }
    return buildJsonObject {
        put("object", "list")
        putJsonArray("data") {
            (listOfNotNull(policy.default) + listed).distinct().forEach { model ->
                addJsonObject {
                    put("id", model)
                    put("object", "model")
                    put("type", "model")
                }
            }
        }
    }
}

private suspend fun RoutingContext.infer(dependencies: GatewayRoutesDependencies, protocol: WireProtocol) {
    val caller = call.inferenceCaller(dependencies, protocol) ?: return
    val bytes = call.receiveChannel().readRemaining(MAX_INFERENCE_REQUEST_BYTES + 1).readByteArray()
    val responder = KtorInferenceResponder(call, dependencies.clock)
    if (bytes.size > MAX_INFERENCE_REQUEST_BYTES) {
        responder.refuse(protocol, InferenceRefusal(RefusalKind.INVALID, "The request exceeds 32 MiB."))
    } else {
        val headers = call.request.headers.entries()
            .associate { (name, values) -> name.lowercase() to values.joinToString(",") } - CREDENTIAL_HEADERS
        val session = headers["ostrom-session-id"] ?: headers["x-claude-code-session-id"] ?: headers["session_id"]
        val inference = InferenceCall(protocol, bytes.decodeToString(), headers, session?.take(MAX_SESSION))
        dependencies.relay(caller, inference, responder)
    }
}

/**
 * The member of an inference call, or null after answering in the provider's shape: 401 without a valid token, 403
 * for a credential that may not call models (a device, a token without the scope).
 */
private suspend fun ApplicationCall.inferenceCaller(
    dependencies: GatewayRoutesDependencies,
    protocol: WireProtocol,
): Caller? {
    val principal = inferenceToken()?.let { dependencies.tokens.authenticate(it) }
    val allowed = principal?.takeIf { it.mayUse(INFERENCE_SCOPES) }
    val caller = allowed?.let { dependencies.callers.resolve(it, null) }
    if (caller == null) {
        val refusal = if (principal != null && allowed == null) {
            InferenceRefusal(RefusalKind.CREDENTIAL_NOT_ALLOWED, "This credential does not allow model calls.")
        } else {
            InferenceRefusal(RefusalKind.UNAUTHENTICATED, "A valid Ostrom token is required.")
        }
        KtorInferenceResponder(this, dependencies.clock).refuse(protocol, refusal)
    }
    return caller
}

/** The token of an inference call: `x-api-key` (Anthropic clients) or `Authorization: Bearer`. */
private fun ApplicationCall.inferenceToken(): String? {
    val bearer = request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith(BEARER, ignoreCase = true) }
        ?.substring(BEARER.length)
    return (request.headers["x-api-key"] ?: bearer)?.trim()?.takeIf { it.isNotEmpty() }
}
