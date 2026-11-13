// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gateway.presentation

import run.ostrom.server.gateway.domain.ClientRequestIds
import run.ostrom.server.gateway.domain.GatewaySettings
import run.ostrom.server.gateway.domain.InferenceCall
import run.ostrom.server.gateway.domain.InferenceRefusal
import run.ostrom.server.gateway.domain.ModelPolicy
import run.ostrom.server.gateway.domain.PriceTable
import run.ostrom.server.gateway.domain.RefusalKind
import run.ostrom.server.gateway.domain.WireProtocol
import run.ostrom.server.gateway.domain.Workload
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.GovernanceScopes
import run.ostrom.server.kernel.auth.GovernanceWrite
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.WriteAccess
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.confirmsByConsole
import run.ostrom.server.kernel.auth.holdForConfirmation
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.auth.requireRecentSignIn
import run.ostrom.server.kernel.http.bodyLimit
import run.ostrom.server.kernel.http.heldCalls
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.OrgNotFoundException
import run.ostrom.server.secrets.domain.RelayScopes
import run.ostrom.server.secrets.presentation.mayUse
import run.ostrom.server.secrets.presentation.requireCaller
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.PayloadTooLargeException
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
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
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

/** The header naming a call's class in the admission queue. */
private const val WORKLOAD_HEADER = "x-ostrom-workload"

/** The client's own id of a call, by which it cancels it; never sent to a provider. */
private const val REQUEST_ID_HEADER = "x-ostrom-client-request-id"

/** Who changes the policy and the settings besides a browser session (S2-52, declared): an `inference:admin` key. */
private val INFERENCE_ADMINS = WriteAccess.tokens(PrincipalKind.SERVICE)

/** Who changes the model policy at once, besides a browser session: an `inference:admin` service key. */
private val POLICY_WRITERS = WriteAccess.tokens(PrincipalKind.SERVICE)

private const val MAX_SESSION = 128
private const val BEARER = "Bearer "

/**
 * Inference routes speaking [protocol]: held calls, with bodies up to [MAX_INFERENCE_REQUEST_BYTES], refused past the
 * replica's ceiling as an overloaded provider refuses, in [protocol]'s shape.
 */
private fun Route.modelCalls(protocol: WireProtocol, clock: Clock, build: Route.() -> Unit) =
    heldCalls(refuse = { call -> KtorInferenceResponder(call, clock).refuse(protocol, overloaded(clock)) }) {
        bodyLimit(MAX_INFERENCE_REQUEST_BYTES, build)
    }

/** When an agent refused for [RefusalKind.OVERLOADED] may retry. */
private val OVERLOADED_RETRY = 1.seconds

/** The refusal of a model call past the replica's ceiling of held calls. */
private fun overloaded(clock: Clock) = InferenceRefusal(
    RefusalKind.OVERLOADED,
    "This Ostrom server is relaying as many model calls as it can hold; retry shortly.",
    clock.now() + OVERLOADED_RETRY,
)

/** The agent headers never passed down, not even to the planner: its credentials, and its own id of the call. */
private val CREDENTIAL_HEADERS = setOf("authorization", "x-api-key", "cookie", REQUEST_ID_HEADER)

/**
 * The gateway routes. The inference endpoints speak the providers' APIs (Anthropic Messages, OpenAI Responses and
 * Chat Completions), take the member's Ostrom token as `Authorization: Bearer` or `x-api-key`, and answer errors in
 * the provider's shape. An agent session reads the model policy; only `inference:admin` (or a browser session)
 * changes it and reads or changes the settings (SECURITY-AUDIT S2-03), and a token that changes them is a service key
 * (S2-52). A person's token that sets the model policy writes nothing: an owner's or an admin's opens a confirmation
 * on the console (audit CLI H1).
 *
 * @param dependencies The use cases.
 */
fun Route.gatewayRoutes(dependencies: GatewayRoutesDependencies) = with(dependencies) {
    // A model call carries the whole context: up to 32 MiB instead of the kernel's 1 MiB (S2-46). It lasts as long as
    // the provider streams: a held call, counted apart from the server's other calls (P1 memory) under the replica's
    // ceiling of held calls (OSTROM_MAX_HELD_CALLS). Past it, the call is refused as an overloaded provider refuses,
    // in the agent's API, so that the agent retries it as it would the provider's 503.
    // Each route is written out: the contract check reads the mounted paths in the sources (served-routes.py).
    modelCalls(WireProtocol.MESSAGES, clock) {
        routePost("$INFERENCE_BASE/v1/messages") { infer(dependencies, WireProtocol.MESSAGES) }
    }
    modelCalls(WireProtocol.RESPONSES, clock) {
        routePost("$INFERENCE_BASE/v1/responses") { infer(dependencies, WireProtocol.RESPONSES) }
    }
    modelCalls(WireProtocol.CHAT, clock) {
        routePost("$INFERENCE_BASE/v1/chat/completions") { infer(dependencies, WireProtocol.CHAT) }
    }
    routeGet("$INFERENCE_BASE/v1/models") {
        call.inferenceCaller(dependencies, WireProtocol.CHAT)?.let { (caller, _) ->
            call.respondText(modelList(getPolicy(caller), caller).toString(), ContentType.Application.Json)
        }
    }
    authenticated {
        get<ModelPolicyApi> {
            val caller = call.requireCaller(callers, RelayScopes.INFERENCE_READ, RelayScopes.AGENT_SESSION)
            call.respond(getPolicy(caller).toDto())
        }
        put<ModelPolicyApi> { call.putModelPolicy(dependencies) }
        inferenceControlRoutes(dependencies)
        get<InferenceSettingsApi> {
            call.respond(getSettings(call.requireCaller(callers, RelayScopes.INFERENCE_ADMIN)).toDto())
        }
        put<InferenceSettingsApi> {
            val caller = call.requireCaller(callers, RelayScopes.INFERENCE_ADMIN, writers = INFERENCE_ADMINS)
            call.requireRecentSignIn("change the model keys")
            val principal = call.requirePrincipal()
            val request = call.receive<InferenceSettingsDto>()
            val settings = GatewaySettings(
                request.anthropicKeySecret,
                request.openaiKeySecret,
                request.openrouterKeySecret,
                request.useSharedAccounts,
                request.memberConcurrency,
            )
            val saved = putSettings(caller, settings)
            // The names of the secrets the keys are read from, never a key: repointing a key is in the log.
            val details = auditDetails(
                "anthropic_key_secret" to saved.anthropicKeySecret,
                "openai_key_secret" to saved.openaiKeySecret,
                "openrouter_key_secret" to saved.openrouterKeySecret,
                "use_shared_accounts" to saved.useSharedAccounts,
                "member_concurrency" to saved.memberConcurrency.entries.joinToString(",") { "${it.key}=${it.value}" },
            )
            audit.record(
                OrgAuditRecord(caller.orgId, principal, "inference.settings.changed", "settings", "inference", details),
            )
            call.respond(saved.toDto())
        }
    }
}

/**
 * Changes the model policy: an `inference:admin` key, a browser session or the token of `ostrom login --admin`; a
 * person is an owner or admin, checked at every request, and the elevated token's write is recorded before it happens.
 *
 * @param dependencies The use cases.
 */
private suspend fun ApplicationCall.putModelPolicy(dependencies: GatewayRoutesDependencies) {
    val principal = requirePrincipal()
    if (principal.confirmsByConsole) {
        // A person's token (the CLI's, elevated or not): an owner's or admin's change, held for the console.
        val caller = dependencies.callers.resolve(principal, null) ?: throw OrgNotFoundException()
        caller.requireAdmin()
        val payload = GatewayGovernanceChange.payload(receive<ModelPolicyDto>())
        val write = GovernanceWrite(GatewayGovernanceChange.MODEL_POLICY, payload)
        return holdForConfirmation(caller.orgId, GovernanceScopes.POLICY_WRITE, write)
    }
    val caller = requireCaller(
        dependencies.callers,
        RelayScopes.INFERENCE_ADMIN,
        GovernanceScopes.POLICY_WRITE,
        writers = POLICY_WRITERS,
    )
    // A person signed in to the console: an owner or admin, at every request.
    if (principal.kind == PrincipalKind.USER) caller.requireAdmin()
    val request = receive<ModelPolicyDto>()
    val policy = dependencies.putPolicy(caller, ModelPolicy(request.allowed, request.defaultModel, request.byMember))
    dependencies.audit.record(policy.changedBy(caller.orgId, principal))
    respond(policy.toDto())
}

private fun ModelPolicy.toDto() = ModelPolicyDto(allowed, default, byMember)

private fun GatewaySettings.toDto() =
    InferenceSettingsDto(
        anthropicKeySecret,
        openaiKeySecret,
        openrouterKeySecret,
        useSharedAccounts,
        memberConcurrency,
    )

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
    val (caller, principal) = call.inferenceCaller(dependencies, protocol) ?: return
    // The kernel cuts the body at MAX_INFERENCE_REQUEST_BYTES, chunked or not; the refusal is in the provider's shape.
    val bytes = try {
        call.receiveChannel().readRemaining().readByteArray()
    } catch (_: PayloadTooLargeException) {
        null
    }
    val responder = KtorInferenceResponder(call, dependencies.clock)
    // Sent twice, the values are joined and name no class.
    val workload = Workload.named(call.request.headers.getAll(WORKLOAD_HEADER)?.joinToString(","))
    val requestIdHeader = call.request.headers.getAll(REQUEST_ID_HEADER)?.joinToString(",")
    val requestId = requestIdHeader?.let { ClientRequestIds.parse(it) }
    if (bytes == null) {
        responder.refuse(protocol, InferenceRefusal(RefusalKind.INVALID, "The request exceeds 32 MiB."))
    } else if (workload == null) {
        val refusal = InferenceRefusal(RefusalKind.INVALID_WORKLOAD, "x-ostrom-workload is interactive or background.")
        responder.refuse(protocol, refusal)
    } else if (requestIdHeader != null && requestId == null) {
        val refusal = InferenceRefusal(RefusalKind.INVALID_REQUEST_ID, "$REQUEST_ID_HEADER is one lowercase UUID.")
        responder.refuse(protocol, refusal)
    } else {
        val headers = call.request.headers.entries()
            .associate { (name, values) -> name.lowercase() to values.joinToString(",") } - CREDENTIAL_HEADERS
        // The session comes from the token, never from a header the agent sets (S2-53): an agent session's token is
        // its launch; any other credential has no session, so no account affinity.
        val session = principal.subjectId.takeIf { RelayScopes.AGENT_SESSION in principal.scopes }
        val inference =
            InferenceCall(protocol, bytes.decodeToString(), headers, session?.take(MAX_SESSION), workload, requestId)
        dependencies.relay(caller, inference, responder)
    }
}

/**
 * The member of an inference call and the principal of its token, or null after answering in the provider's shape:
 * 401 without a valid token, 403
 * for a credential that may not call models (a device, a token without the scope).
 */
private suspend fun ApplicationCall.inferenceCaller(
    dependencies: GatewayRoutesDependencies,
    protocol: WireProtocol,
): Pair<Caller, AuthenticatedPrincipal>? {
    val principal = inferenceToken()?.let { dependencies.tokens.authenticate(it) }
    val allowed = principal?.takeIf { it.mayUse(INFERENCE_SCOPES) }
    val caller = allowed?.let { dependencies.callers.resolve(it, null) }
    if (allowed == null || caller == null) {
        val refusal = if (principal != null && allowed == null) {
            InferenceRefusal(RefusalKind.CREDENTIAL_NOT_ALLOWED, "This credential does not allow model calls.")
        } else {
            InferenceRefusal(RefusalKind.UNAUTHENTICATED, "A valid Ostrom token is required.")
        }
        KtorInferenceResponder(this, dependencies.clock).refuse(protocol, refusal)
        return null
    }
    return caller to allowed
}

/** The token of an inference call: `x-api-key` (Anthropic clients) or `Authorization: Bearer`. */
private fun ApplicationCall.inferenceToken(): String? {
    val bearer = request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith(BEARER, ignoreCase = true) }
        ?.substring(BEARER.length)
    // An empty token is refused by the authenticator like any unknown one.
    return (request.headers["x-api-key"] ?: bearer)?.trim()
}
