// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts.presentation

import run.ostrom.server.artifacts.domain.MissingScopeException
import run.ostrom.server.artifacts.domain.NotAMemberException
import run.ostrom.server.artifacts.domain.storage.ArtifactAdministrationUseCase
import run.ostrom.server.artifacts.domain.storage.ArtifactQuotas
import run.ostrom.server.artifacts.domain.storage.ArtifactRetention
import run.ostrom.server.artifacts.domain.storage.ArtifactSettings
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageInput
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageOverview
import run.ostrom.server.artifacts.domain.storage.InvalidArtifactSettingsException
import run.ostrom.server.artifacts.domain.storage.InvalidArtifactStorageException
import run.ostrom.server.artifacts.domain.storage.PublicSharing
import run.ostrom.server.artifacts.domain.storage.ServerSideEncryption
import run.ostrom.server.artifacts.domain.storage.StorageCaller
import run.ostrom.server.artifacts.domain.storage.StorageTestResult
import run.ostrom.server.artifacts.domain.storage.WhoCanPublish
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.WriteAccess
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.mayWrite
import run.ostrom.server.kernel.auth.requirePrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * What the bucket, settings and policy routes need.
 *
 * @property administration The use cases.
 * @property orgResolver The member a request acts for.
 */
data class StorageRoutesDependencies(
    val administration: ArtifactAdministrationUseCase,
    val orgResolver: PrincipalOrgResolver,
)

/**
 * The bucket, the settings (a person who owns or administers the organization) and the publication policy (any
 * member credential with `artifacts:read`), under `/api/v1/artifacts`.
 *
 * @param dependencies Read at the first request, so a server without the secrets module still starts.
 */
fun Route.artifactStorageRoutes(dependencies: () -> StorageRoutesDependencies) {
    val resolved by lazy(dependencies)
    authenticated {
        get<ArtifactStorageApi> {
            val caller = resolved.orgResolver.storageCallerOf(call.requirePrincipal())
            call.respond(resolved.administration.overview(caller).toStorageResponse())
        }
        put<ArtifactStorageApi> {
            val caller = resolved.orgResolver.storageCallerOf(call.requirePrincipal())
            val request = call.receive<SaveArtifactStorageRequest>()
            resolved.administration.saveStorage(caller, request.version, request.toInput())
            call.respond(resolved.administration.overview(caller).toStorageResponse())
        }
        delete<ArtifactStorageApi> {
            val caller = resolved.orgResolver.storageCallerOf(call.requirePrincipal())
            resolved.administration.deleteStorage(caller)
            call.respond(HttpStatusCode.NoContent)
        }
        post<ArtifactStorageApi.Test> {
            val caller = resolved.orgResolver.storageCallerOf(call.requirePrincipal())
            call.respond(resolved.administration.testStorage(caller).toResponse())
        }
        get<ArtifactSettingsApi> {
            val caller = resolved.orgResolver.storageCallerOf(call.requirePrincipal())
            call.respond(resolved.administration.overview(caller).toSettingsResponse())
        }
        put<ArtifactSettingsApi> {
            val caller = resolved.orgResolver.storageCallerOf(call.requirePrincipal())
            val request = call.receive<SaveArtifactSettingsRequest>()
            resolved.administration.saveSettings(caller, request.toSettings())
            call.respond(resolved.administration.overview(caller).toSettingsResponse())
        }
        get<ArtifactPolicyApi> {
            val principal = call.requirePrincipal()
            if (!principal.allowsArtifacts(ARTIFACTS_READ)) throw MissingScopeException(ARTIFACTS_READ)
            val policy = resolved.administration.policy(resolved.orgResolver.storageCallerOf(principal))
            call.respond(ArtifactPolicyResponse(policy.canPublish, policy.reason))
        }
    }
}

/**
 * The member a request acts for, their role and what their credential is.
 *
 * @throws NotAMemberException When it acts for no organization.
 */
internal suspend fun PrincipalOrgResolver.storageCallerOf(principal: AuthenticatedPrincipal): StorageCaller {
    val member = resolve(principal, null) ?: throw NotAMemberException()
    return StorageCaller(
        orgId = member.orgId,
        memberId = member.memberId.toString(),
        administrator = member.role == OrgRole.OWNER || member.role == OrgRole.ADMIN,
        person = principal.mayWrite(WriteAccess.PERSON),
        mayWrite = principal.allowsArtifacts(ARTIFACTS_WRITE),
    )
}

internal fun SaveArtifactStorageRequest.toInput(): ArtifactStorageInput = ArtifactStorageInput(
    endpoint = endpoint.trim(),
    region = region.trim(),
    bucket = bucket.trim(),
    prefix = prefix.trim(),
    pathStyle = pathStyle,
    accessKeyId = accessKeyId.trim(),
    secretAccessKey = secretAccessKey?.trim()?.takeIf { it.isNotEmpty() },
    serverSideEncryption = ServerSideEncryption.parse(serverSideEncryption)
        ?: throw InvalidArtifactStorageException("Server-side encryption is none or AES256."),
)

internal fun SaveArtifactSettingsRequest.toSettings(): ArtifactSettings = ArtifactSettings(
    enabled = enabled,
    whoCanPublish = WhoCanPublish.parse(whoCanPublish)
        ?: throw InvalidArtifactSettingsException("Who can publish is everyone or admins."),
    publicSharing = PublicSharing.parse(publicSharing)
        ?: throw InvalidArtifactSettingsException("Public sharing is owners_of_artifacts, admins or off."),
    quotas = ArtifactQuotas(quotaMemberBytes, quotaOrgBytes, quotaArtifactsPerMember, quotaPublishesPerHour),
    retention = ArtifactRetention(personalRetentionDays, sharedRetentionDays, maxVersionsPerArtifact),
    version = version,
)

internal fun ArtifactStorageOverview.toStorageResponse(): ArtifactStorageResponse = storage?.let {
    ArtifactStorageResponse(
        configured = true,
        endpoint = it.endpoint,
        region = it.region,
        bucket = it.bucket,
        prefix = it.prefix,
        pathStyle = it.pathStyle,
        accessKeyId = it.accessKeyId,
        secretConfigured = true,
        serverSideEncryption = it.serverSideEncryption.wire,
        verifiedAt = it.verifiedAt?.toString(),
        version = it.version,
        updatedAt = it.updatedAt.toString(),
        inUse = inUse,
    )
} ?: ArtifactStorageResponse(configured = false, inUse = inUse)

internal fun ArtifactStorageOverview.toSettingsResponse(): ArtifactSettingsResponse = ArtifactSettingsResponse(
    enabled = settings.enabled,
    whoCanPublish = settings.whoCanPublish.wire,
    publicSharing = settings.publicSharing.wire,
    quotaMemberBytes = settings.quotas.memberBytes,
    quotaOrgBytes = settings.quotas.orgBytes,
    quotaArtifactsPerMember = settings.quotas.artifactsPerMember,
    quotaPublishesPerHour = settings.quotas.publishesPerHour,
    personalRetentionDays = settings.retention.personalDays,
    sharedRetentionDays = settings.retention.sharedDays,
    maxVersionsPerArtifact = settings.retention.maxVersionsPerArtifact,
    version = settings.version,
    updatedAt = settings.updatedAt?.toString(),
    active = availability.active,
    inactiveReason = availability.reason?.wire,
)

internal fun StorageTestResult.toResponse(): ArtifactStorageTestResponse =
    ArtifactStorageTestResponse(ok, steps.map { ArtifactStorageTestStepResponse(it.step, it.ok, it.status, it.code) })
