package dev.ostrom.server.mcprelay.presentation

import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.mcprelay.domain.AssignMcpServerUseCase
import dev.ostrom.server.mcprelay.domain.CreateArgumentPolicyUseCase
import dev.ostrom.server.mcprelay.domain.CreateMcpServerUseCase
import dev.ostrom.server.mcprelay.domain.DeleteArgumentPolicyUseCase
import dev.ostrom.server.mcprelay.domain.DeleteMcpServerUseCase
import dev.ostrom.server.mcprelay.domain.GetMcpSettingsUseCase
import dev.ostrom.server.mcprelay.domain.ListArgumentPoliciesUseCase
import dev.ostrom.server.mcprelay.domain.ListMcpAssignmentsUseCase
import dev.ostrom.server.mcprelay.domain.ListMcpCallsUseCase
import dev.ostrom.server.mcprelay.domain.ListMcpServersUseCase
import dev.ostrom.server.mcprelay.domain.ListMemberServersUseCase
import dev.ostrom.server.mcprelay.domain.McpServerNotFoundException
import dev.ostrom.server.mcprelay.domain.McpSettings
import dev.ostrom.server.mcprelay.domain.McpUsageUseCase
import dev.ostrom.server.mcprelay.domain.PutMcpSettingsUseCase
import dev.ostrom.server.mcprelay.domain.RelayMcpMessageUseCase
import dev.ostrom.server.mcprelay.domain.RelayMcpMessageUseCaseImpl
import dev.ostrom.server.mcprelay.domain.ToolPolicy
import dev.ostrom.server.mcprelay.domain.UnassignMcpServerUseCase
import dev.ostrom.server.mcprelay.domain.UpdateMcpServerUseCase
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.presentation.requireCaller
import dev.ostrom.server.secrets.presentation.toIdOr
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get as routeGet
import io.ktor.server.routing.post as routePost
import io.ktor.server.routing.delete as routeDelete
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** What the relay routes need. */
data class McpRelayRoutesDependencies(
    val callers: CallerResolver,
    val createServer: CreateMcpServerUseCase,
    val updateServer: UpdateMcpServerUseCase,
    val deleteServer: DeleteMcpServerUseCase,
    val listServers: ListMcpServersUseCase,
    val assign: AssignMcpServerUseCase,
    val unassign: UnassignMcpServerUseCase,
    val listAssignments: ListMcpAssignmentsUseCase,
    val createPolicy: CreateArgumentPolicyUseCase,
    val deletePolicy: DeleteArgumentPolicyUseCase,
    val listPolicies: ListArgumentPoliciesUseCase,
    val getSettings: GetMcpSettingsUseCase,
    val putSettings: PutMcpSettingsUseCase,
    val listCalls: ListMcpCallsUseCase,
    val usage: McpUsageUseCase,
    val listMemberServers: ListMemberServersUseCase,
    val relay: RelayMcpMessageUseCase,
)

/** The largest MCP message an agent may send. */
const val MAX_MCP_REQUEST_BYTES: Long = 1024 * 1024

/**
 * The relay routes: administration under `/v1/orgs/{orgId}/mcp`, and the agent side, `GET /v1/mcp/servers` and the
 * Streamable HTTP endpoint `POST /v1/mcp/{server}` (stateless: no session, no server-initiated stream).
 *
 * @param dependencies The use cases.
 */
fun Route.mcpRelayRoutes(dependencies: McpRelayRoutesDependencies) = with(dependencies) {
    authenticated {
        get<OrgMcpApi.Servers> { params ->
            val caller = call.requireCaller(callers, params.parent.orgId)
            call.respond(McpServersResponse(listServers(caller).map { it.toResponse() }))
        }
        post<OrgMcpApi.Servers> { params ->
            val caller = call.requireCaller(callers, params.parent.orgId)
            call.respond(HttpStatusCode.Created, createServer(caller, call.receive<McpServerRequest>().toPayload()).toResponse())
        }
        put<OrgMcpApi.Servers.Id> { params ->
            val caller = call.requireCaller(callers, params.parent.parent.orgId)
            val id = params.id.toIdOr(::McpServerNotFoundException)
            call.respond(updateServer(caller, id, call.receive<McpServerRequest>().toPayload()).toResponse())
        }
        delete<OrgMcpApi.Servers.Id> { params ->
            val caller = call.requireCaller(callers, params.parent.parent.orgId)
            deleteServer(caller, params.id.toIdOr(::McpServerNotFoundException))
            call.respond(HttpStatusCode.NoContent)
        }
        get<OrgMcpApi.Servers.Id.Assignments> { params ->
            val caller = call.requireCaller(callers, params.parent.parent.parent.orgId)
            val assignments = listAssignments(caller, params.parent.id.toIdOr(::McpServerNotFoundException))
            call.respond(McpAssignmentsResponse(assignments.map { it.toResponse() }))
        }
        put<OrgMcpApi.Servers.Id.Assignments.Member> { params ->
            val caller = call.requireCaller(callers, params.parent.parent.parent.parent.orgId)
            val request = call.receive<McpAssignmentRequest>()
            val assignment = assign(
                caller,
                params.parent.parent.id.toIdOr(::McpServerNotFoundException),
                params.member.toMemberId(),
                ToolPolicy(request.tools?.toSet()),
                request.credentialSecretId?.toIdOr(::McpServerNotFoundException),
            )
            call.respond(assignment.toResponse())
        }
        delete<OrgMcpApi.Servers.Id.Assignments.Member> { params ->
            val caller = call.requireCaller(callers, params.parent.parent.parent.parent.orgId)
            unassign(caller, params.parent.parent.id.toIdOr(::McpServerNotFoundException), params.member.toMemberId())
            call.respond(HttpStatusCode.NoContent)
        }
        get<OrgMcpApi.Servers.Id.Policies> { params ->
            val caller = call.requireCaller(callers, params.parent.parent.parent.orgId)
            val policies = listPolicies(caller, params.parent.id.toIdOr(::McpServerNotFoundException))
            call.respond(ArgumentPoliciesResponse(policies.map { it.toResponse() }))
        }
        post<OrgMcpApi.Servers.Id.Policies> { params ->
            val caller = call.requireCaller(callers, params.parent.parent.parent.orgId)
            val request = call.receive<ArgumentPolicyRequest>()
            val policy = createPolicy(caller, params.parent.id.toIdOr(::McpServerNotFoundException), request.toPayload())
            call.respond(HttpStatusCode.Created, policy.toResponse())
        }
        delete<OrgMcpApi.Servers.Id.Policies.PolicyId> { params ->
            val caller = call.requireCaller(callers, params.parent.parent.parent.parent.orgId)
            deletePolicy(
                caller,
                params.parent.parent.id.toIdOr(::McpServerNotFoundException),
                params.policyId.toIdOr(::McpServerNotFoundException),
            )
            call.respond(HttpStatusCode.NoContent)
        }
        get<OrgMcpApi.Settings> { params ->
            val caller = call.requireCaller(callers, params.parent.orgId)
            call.respond(McpSettingsDto(getSettings(caller).agentPrefix))
        }
        put<OrgMcpApi.Settings> { params ->
            val caller = call.requireCaller(callers, params.parent.orgId)
            call.respond(McpSettingsDto(putSettings(caller, McpSettings(call.receive<McpSettingsDto>().agentPrefix)).agentPrefix))
        }
        get<OrgMcpApi.Calls> { params ->
            val caller = call.requireCaller(callers, params.parent.orgId)
            val before = params.before?.let {
                try {
                    Instant.parse(it)
                } catch (_: IllegalArgumentException) {
                    throw OstromException(ErrorKind.INVALID_REQUEST, "before is not an ISO 8601 instant.")
                }
            }
            val limit = params.limit.coerceIn(1, 200)
            val calls = listCalls(caller, before, limit)
            call.respond(McpCallsResponse(calls.map { it.toResponse() }, calls.takeIf { it.size == limit }?.last()?.startedAt?.toString()))
        }
        get<OrgMcpApi.Usage> { params ->
            val caller = call.requireCaller(callers, params.parent.orgId)
            call.respond(McpUsagesResponse(usage(caller, params.days).map { it.toResponse() }))
        }
        get<MemberMcpServersApi> {
            val caller = call.requireCaller(callers, null)
            call.respond(MemberMcpServersResponse(listMemberServers(caller).map { it.toResponse() }))
        }
        route("/v1/mcp/{server}") {
            routePost {
                // Streamable HTTP from a browser is refused (DNS rebinding): agents never send an Origin.
                if (call.request.headers[HttpHeaders.Origin] != null) {
                    throw OstromException(ErrorKind.FORBIDDEN, "The MCP relay does not accept browser requests.")
                }
                val version = call.request.headers["MCP-Protocol-Version"]
                if (version != null && version !in RelayMcpMessageUseCaseImpl.PROTOCOL_VERSIONS) {
                    throw OstromException(ErrorKind.INVALID_REQUEST, "Unsupported MCP-Protocol-Version.")
                }
                val caller = call.requireCaller(callers, null)
                val bytes = call.receiveChannel().readRemaining(MAX_MCP_REQUEST_BYTES + 1).readByteArray()
                if (bytes.size > MAX_MCP_REQUEST_BYTES) throw OstromException(ErrorKind.PAYLOAD_TOO_LARGE, "The MCP message exceeds 1 MiB.")
                val message = try {
                    Json.parseToJsonElement(bytes.decodeToString())
                } catch (_: SerializationException) {
                    throw OstromException(ErrorKind.INVALID_REQUEST, "The MCP message is not JSON.")
                }
                val answer = relay(caller, call.parameters["server"].orEmpty(), message)
                val body = answer.body
                if (body == null) {
                    call.respond(HttpStatusCode.fromValue(answer.status))
                } else {
                    call.respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.fromValue(answer.status))
                }
            }
            routeGet { call.respond(HttpStatusCode.MethodNotAllowed) }
            routeDelete { call.respond(HttpStatusCode.MethodNotAllowed) }
        }
    }
}

private fun String.toMemberId(): Uuid? = if (this == "all") null else toIdOr(::McpServerNotFoundException)
