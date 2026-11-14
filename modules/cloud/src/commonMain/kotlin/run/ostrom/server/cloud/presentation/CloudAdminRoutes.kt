// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.presentation

import run.ostrom.server.cloud.domain.CloudAdminCaller
import run.ostrom.server.cloud.domain.CloudCluster
import run.ostrom.server.cloud.domain.CloudClusterInput
import run.ostrom.server.cloud.domain.CloudClusterNotFoundException
import run.ostrom.server.cloud.domain.CloudProfile
import run.ostrom.server.cloud.domain.CloudProfileInput
import run.ostrom.server.cloud.domain.CloudProfileNotFoundException
import run.ostrom.server.cloud.domain.CloudRuntime
import run.ostrom.server.cloud.domain.InvalidCloudRuntimeException
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.WriteAccess
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.mayWrite
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.auth.requireWrite
import run.ostrom.server.secrets.presentation.requireCaller
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * TeamCode's cloud administration (`/dashboard/cloud`): the runtime and the clusters are the instance's, for a person
 * who administers it, signed in; the profiles an organization's, read by its members (`cloud:read`), written by an
 * owner or an admin signed in (`cloud:write`), as are the organization's cloud settings. The caller is checked
 * before a body is read (TeamCode 3b716702).
 *
 * @param dependencies The use cases.
 */
fun Route.cloudAdminRoutes(dependencies: CloudAdminRoutesDependencies) = with(dependencies) {
    authenticated {
        get<CloudRuntimeApi> {
            call.respond(runtime.get(call.requirePrincipal().cloudAdmin()).toResponse(publicUrl))
        }
        put<CloudRuntimeApi> {
            val caller = call.requirePrincipal().cloudAdmin()
            gate.require(caller)
            val request = call.receive<SaveCloudRuntimeRequest>()
            val expected = request.updatedAt?.let {
                Instant.parseOrNull(it) ?: throw InvalidCloudRuntimeException("updatedAt is a date-time.")
            }
            val saved = runtime.save(
                caller,
                request.toRuntime(),
                expected,
                keepAutomationCap = request.maxRunningAutomationsPerMember == null,
            )
            call.respond(saved.toResponse(publicUrl))
        }
        get<CloudClustersApi> {
            val list = clusters.list(call.requirePrincipal().cloudAdmin())
            call.respond(CloudClusterListResponse(list.map { it.toResponse() }))
        }
        post<CloudClustersApi> {
            val caller = call.requirePrincipal().cloudAdmin()
            gate.require(caller)
            val created = clusters.create(caller, call.receive<SaveCloudClusterRequest>().toInput())
            call.respond(HttpStatusCode.Created, created.toResponse())
        }
        put<CloudClustersApi.One> { params ->
            val caller = call.requirePrincipal().cloudAdmin()
            gate.require(caller)
            val id = Uuid.parseOrNull(params.id) ?: throw CloudClusterNotFoundException()
            call.respond(clusters.update(caller, id, call.receive<SaveCloudClusterRequest>().toInput()).toResponse())
        }
        get<CloudProfilesApi> {
            val caller = call.requireCaller(callers, CLOUD_READ)
            // A team's profiles are its people's, as TeamCode's: never a key's, whatever its scopes.
            call.requirePrincipal().requireWrite(WriteAccess.PERSON)
            call.respond(CloudProfileListResponse(profiles.list(caller).map { it.toResponse() }))
        }
        post<CloudProfilesApi> {
            val caller = call.requireCaller(callers, CLOUD_WRITE)
            call.requirePrincipal().requireWrite(WriteAccess.PERSON)
            caller.requireAdmin()
            val profile = profiles.create(caller, call.receive<SaveCloudProfileRequest>().toInput())
            audit.record(profile.audit(call.requirePrincipal(), "cloud.profile.created"))
            call.respond(HttpStatusCode.Created, profile.toResponse())
        }
        put<CloudProfilesApi.One> { params ->
            val caller = call.requireCaller(callers, CLOUD_WRITE)
            call.requirePrincipal().requireWrite(WriteAccess.PERSON)
            caller.requireAdmin()
            val id = Uuid.parseOrNull(params.id) ?: throw CloudProfileNotFoundException()
            val profile = profiles.update(caller, id, call.receive<SaveCloudProfileRequest>().toInput())
            audit.record(profile.audit(call.requirePrincipal(), "cloud.profile.published"))
            call.respond(profile.toResponse())
        }
        cloudSettingsRoutes(dependencies)
    }
}


internal const val CLOUD_READ = "cloud:read"
internal const val CLOUD_WRITE = "cloud:write"

/** The caller of the instance's cloud administration: a person only when the credential is a person's own. */
internal fun AuthenticatedPrincipal.cloudAdmin(): CloudAdminCaller = CloudAdminCaller(
    userId = Uuid.parseOrNull(subjectId),
    person = mayWrite(WriteAccess.PERSON),
)

internal fun SaveCloudRuntimeRequest.toRuntime() = CloudRuntime(
    enabled = enabled,
    runnerImage = runnerImage,
    runnerBinary = runnerBinary,
    serverUrl = serverUrl,
    workspaceAccessMode = workspaceAccessMode,
    pausedRetentionDays = pausedRetentionDays,
    failedWorkspaceRetentionDays = failedWorkspaceRetentionDays,
    maxRunningSessionsPerMember = maxRunningSessionsPerMember,
    maxRetainedSessionsPerMember = maxRetainedSessionsPerMember,
    maxRunningAutomationsPerMember = maxRunningAutomationsPerMember ?: CloudRuntime().maxRunningAutomationsPerMember,
    transcriptRetentionDays = transcriptRetentionDays,
    memberTransfers = memberTransfers,
)

internal fun CloudRuntime.toResponse(publicUrl: String) = CloudRuntimeResponse(
    enabled = enabled,
    runnerImage = runnerImage,
    runnerBinary = runnerBinary,
    serverUrl = serverUrl,
    effectiveServerUrl = serverUrl ?: publicUrl,
    workspaceAccessMode = workspaceAccessMode,
    pausedRetentionDays = pausedRetentionDays,
    failedWorkspaceRetentionDays = failedWorkspaceRetentionDays,
    maxRunningSessionsPerMember = maxRunningSessionsPerMember,
    maxRetainedSessionsPerMember = maxRetainedSessionsPerMember,
    maxRunningAutomationsPerMember = maxRunningAutomationsPerMember,
    transcriptRetentionDays = transcriptRetentionDays,
    memberTransfers = memberTransfers,
    updatedAt = updatedAt?.toString(),
)

internal fun SaveCloudClusterRequest.toInput() = CloudClusterInput(
    name = name,
    apiUrl = apiUrl,
    caCertificate = caCertificate,
    serviceAccountToken = serviceAccountToken,
    allowedNamespaces = allowedNamespaces,
    maxRunningPods = maxRunningPods,
    defaultStorageClass = defaultStorageClass,
    version = version,
)

internal fun CloudCluster.toResponse() = CloudClusterResponse(
    id = id.toString(),
    name = name,
    apiUrl = apiUrl,
    allowedNamespaces = allowedNamespaces,
    maxRunningPods = maxRunningPods,
    defaultStorageClass = defaultStorageClass,
    version = version,
    caCertificate = CloudSecretStateResponse(hasCaCertificate),
    serviceAccountToken = CloudSecretStateResponse(hasServiceAccountToken),
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

internal fun SaveCloudProfileRequest.toInput() = CloudProfileInput(name, version, spec, assignedMemberIds.toSet())

internal fun CloudProfile.toResponse() = CloudProfileResponse(
    id = id.toString(),
    name = name,
    version = version,
    spec = spec,
    assignedMemberIds = assignedMemberIds.sorted(),
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

private fun CloudProfile.audit(principal: AuthenticatedPrincipal, event: String) = OrgAuditRecord(
    orgId,
    principal,
    event,
    "cloud_profile",
    id.toString(),
    mapOf("version" to version.toString(), "image" to spec.image.take(MAX_DETAIL)),
)

private const val MAX_DETAIL = 200
