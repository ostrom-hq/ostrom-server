package dev.ostrom.server.mcprelay.presentation

import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.mcprelay.domain.AssignMcpServerUseCase
import dev.ostrom.server.mcprelay.domain.CreateArgumentPolicyUseCase
import dev.ostrom.server.mcprelay.domain.CreateMcpServerUseCase
import dev.ostrom.server.mcprelay.domain.DeleteArgumentPolicyUseCase
import dev.ostrom.server.mcprelay.domain.DeleteMcpServerUseCase
import dev.ostrom.server.mcprelay.domain.GetMcpSettingsUseCase
import dev.ostrom.server.mcprelay.domain.InvalidMcpServerException
import dev.ostrom.server.mcprelay.domain.ListArgumentPoliciesUseCase
import dev.ostrom.server.mcprelay.domain.ListMcpAssignmentsUseCase
import dev.ostrom.server.mcprelay.domain.ListMcpCallsUseCase
import dev.ostrom.server.mcprelay.domain.ListMcpServersUseCase
import dev.ostrom.server.mcprelay.domain.ListMemberServersUseCase
import dev.ostrom.server.mcprelay.domain.McpSecretNotFoundException
import dev.ostrom.server.mcprelay.domain.McpServerNotFoundException
import dev.ostrom.server.mcprelay.domain.McpSettings
import dev.ostrom.server.mcprelay.domain.McpUsageUseCase
import dev.ostrom.server.mcprelay.domain.PutMcpSettingsUseCase
import dev.ostrom.server.mcprelay.domain.RelayMcpMessageUseCase
import dev.ostrom.server.mcprelay.domain.RelayMcpMessageUseCaseImpl
import dev.ostrom.server.mcprelay.domain.ToolPolicy
import dev.ostrom.server.mcprelay.domain.UnassignMcpServerUseCase
import dev.ostrom.server.mcprelay.domain.UpdateMcpServerUseCase
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.RelayScopes
import dev.ostrom.server.secrets.domain.SecretDirectoryUseCase
import dev.ostrom.server.secrets.presentation.mayUse
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
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import kotlin.uuid.Uuid
import io.ktor.server.routing.delete as routeDelete
import io.ktor.server.routing.get as routeGet
import io.ktor.server.routing.post as routePost

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
    val directory: SecretDirectoryUseCase,
)

/** The largest MCP message an agent may send. */
const val MAX_MCP_REQUEST_BYTES: Long = 1024 * 1024

/**
 * The relay routes, in the org of the caller's token: administration under `/api/v1/mcp` (servers addressed by name,
 * secrets by name), the agent's list (`GET /api/v1/mcp/servers?mine=true`) and the Streamable HTTP endpoint
 * `POST /api/v1/mcp/{server}` (stateless: no session, no server-initiated stream).
 *
 * @param dependencies The use cases.
 */
fun Route.mcpRelayRoutes(dependencies: McpRelayRoutesDependencies) = with(dependencies) {
    authenticated {
        get<McpApi.Servers> { params ->
            val caller = call.requireCaller(
                callers,
                RelayScopes.MCP_READ,
                RelayScopes.MCP_USE,
                RelayScopes.AGENT_SESSION,
            )
            // The whole list, with secret names, is for an admin's console credential; an agent gets its own list.
            val console = call.requirePrincipal().mayUse(arrayOf(RelayScopes.MCP_READ))
            if (params.mine || !caller.role.isAdmin || !console) {
                call.respond(McpServersPage(listMemberServers(caller).map { it.toDto() }))
            } else {
                val servers = listServers(caller)
                val names = directory.namesById(caller.orgId, servers.flatMap { it.secretIds() })
                call.respond(McpServersPage(servers.map { it.toDto(names) }))
            }
        }
        post<McpApi.Servers> {
            val caller = call.requireCaller(callers, RelayScopes.MCP_ADMIN)
            val request = call.receive<McpServerDto>()
            val server =
                createServer(
                    caller,
                    request.toPayload(directory.idsByName(caller.orgId, null, request.secretNamesUsed())),
                )
            call.respond(HttpStatusCode.Created, server.toDto(directory.namesById(caller.orgId, server.secretIds())))
        }
        put<McpApi.Servers.Name> { params ->
            val caller = call.requireCaller(callers, RelayScopes.MCP_ADMIN)
            val request = call.receive<McpServerDto>()
            if (request.name !=
                params.name
            ) {
                throw InvalidMcpServerException("The name of the body differs from the name of the path.")
            }
            val payload = request.toPayload(directory.idsByName(caller.orgId, null, request.secretNamesUsed()))
            val existing = listServers(caller).firstOrNull { it.name == params.name }
            val server = if (existing ==
                null
            ) {
                createServer(caller, payload)
            } else {
                updateServer(caller, existing.id, payload)
            }
            call.respond(server.toDto(directory.namesById(caller.orgId, server.secretIds())))
        }
        delete<McpApi.Servers.Name> { params ->
            val caller = call.requireCaller(callers, RelayScopes.MCP_ADMIN)
            deleteServer(caller, serverId(caller, params.name))
            call.respond(HttpStatusCode.NoContent)
        }
        get<McpApi.Servers.Name.Assignments> { params ->
            val caller = call.requireCaller(callers, RelayScopes.MCP_READ)
            val assignments = listAssignments(caller, serverId(caller, params.parent.name))
            val names = directory.namesById(caller.orgId, assignments.mapNotNull { it.credentialSecretId })
            call.respond(
                McpAssignmentsPage(
                    assignments.map {
                        it.toResponse(params.parent.name, it.credentialSecretId?.let(names::get))
                    },
                ),
            )
        }
        put<McpApi.Servers.Name.Assignments.Member> { params ->
            val caller = call.requireCaller(callers, RelayScopes.MCP_ADMIN)
            val request = call.receive<McpAssignmentRequest>()
            val member = params.member.toMemberId()
            val credential = request.credentialSecret?.let { name ->
                // A member's own credential is one of their personal secrets.
                val owner =
                    member ?: throw InvalidMcpServerException("A credential is assigned to one member, not to all.")
                directory.idsByName(caller.orgId, owner, listOf(name))[name] ?: throw McpSecretNotFoundException()
            }
            val assignment =
                assign(
                    caller,
                    serverId(caller, params.parent.parent.name),
                    member,
                    ToolPolicy(request.tools?.toSet()),
                    credential,
                )
            call.respond(assignment.toResponse(params.parent.parent.name, request.credentialSecret))
        }
        delete<McpApi.Servers.Name.Assignments.Member> { params ->
            val caller = call.requireCaller(callers, RelayScopes.MCP_ADMIN)
            unassign(caller, serverId(caller, params.parent.parent.name), params.member.toMemberId())
            call.respond(HttpStatusCode.NoContent)
        }
        get<McpApi.Servers.Name.Policies> { params ->
            val caller = call.requireCaller(callers, RelayScopes.MCP_READ)
            val policies = listPolicies(caller, serverId(caller, params.parent.name))
            call.respond(ArgumentPoliciesResponse(policies.map { it.toResponse() }))
        }
        post<McpApi.Servers.Name.Policies> { params ->
            val caller = call.requireCaller(callers, RelayScopes.MCP_ADMIN)
            val request = call.receive<ArgumentPolicyRequest>()
            val policy = createPolicy(caller, serverId(caller, params.parent.name), request.toPayload())
            call.respond(HttpStatusCode.Created, policy.toResponse())
        }
        delete<McpApi.Servers.Name.Policies.PolicyId> { params ->
            val caller = call.requireCaller(callers, RelayScopes.MCP_ADMIN)
            deletePolicy(
                caller,
                serverId(caller, params.parent.parent.name),
                params.policyId.toIdOr(::McpServerNotFoundException),
            )
            call.respond(HttpStatusCode.NoContent)
        }
        get<McpApi.Settings> {
            val caller = call.requireCaller(callers, RelayScopes.MCP_READ)
            call.respond(McpSettingsDto(getSettings(caller).agentPrefix))
        }
        put<McpApi.Settings> {
            val caller = call.requireCaller(callers, RelayScopes.MCP_ADMIN)
            call.respond(
                McpSettingsDto(
                    putSettings(caller, McpSettings(call.receive<McpSettingsDto>().agentPrefix)).agentPrefix,
                ),
            )
        }
        get<McpApi.Calls> { params ->
            val caller = call.requireCaller(callers, RelayScopes.MCP_READ)
            val before = params.before?.let {
                try {
                    Instant.parse(it)
                } catch (_: IllegalArgumentException) {
                    throw OstromException(ErrorKind.INVALID_REQUEST, "before is not an ISO 8601 instant.")
                }
            }
            val member = params.dev?.let {
                Uuid.parseOrNull(it)
                    ?: throw OstromException(ErrorKind.INVALID_REQUEST, "dev is not a member id.")
            }
            val limit = params.limit.coerceIn(1, 200)
            val calls = listCalls(caller, before, limit, params.server, member)
            call.respond(
                McpCallsPage(
                    calls.map {
                        it.toDto()
                    },
                    calls.takeIf { it.size == limit }?.last()?.startedAt?.toString(),
                ),
            )
        }
        get<McpApi.Usage> { params ->
            val caller = call.requireCaller(callers, RelayScopes.MCP_READ)
            call.respond(usage(caller, params.days).toResponse())
        }
        route("/api/v1/mcp/{server}") {
            routePost {
                // Streamable HTTP from a browser is refused (DNS rebinding): agents never send an Origin.
                if (call.request.headers[HttpHeaders.Origin] != null) {
                    throw OstromException(ErrorKind.FORBIDDEN, "The MCP relay does not accept browser requests.")
                }
                val version = call.request.headers["MCP-Protocol-Version"]
                if (version != null && version !in RelayMcpMessageUseCaseImpl.PROTOCOL_VERSIONS) {
                    throw OstromException(ErrorKind.INVALID_REQUEST, "Unsupported MCP-Protocol-Version.")
                }
                val caller = call.requireCaller(callers, RelayScopes.AGENT_SESSION, RelayScopes.MCP_USE)
                val bytes = call.receiveChannel().readRemaining(MAX_MCP_REQUEST_BYTES + 1).readByteArray()
                if (bytes.size >
                    MAX_MCP_REQUEST_BYTES
                ) {
                    throw OstromException(ErrorKind.PAYLOAD_TOO_LARGE, "The MCP message exceeds 1 MiB.")
                }
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
                    call.respondText(
                        body.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.fromValue(answer.status),
                    )
                }
            }
            routeGet { call.respond(HttpStatusCode.MethodNotAllowed) }
            routeDelete { call.respond(HttpStatusCode.MethodNotAllowed) }
        }
    }
}

private suspend fun McpRelayRoutesDependencies.serverId(caller: Caller, name: String): Uuid =
    listServers(caller).firstOrNull { it.name == name }?.id ?: throw McpServerNotFoundException()

private fun String.toMemberId(): Uuid? = if (this == "all") null else toIdOr(::McpServerNotFoundException)
