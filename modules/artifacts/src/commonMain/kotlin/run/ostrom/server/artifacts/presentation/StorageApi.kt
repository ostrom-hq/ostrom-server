// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts.presentation

import run.ostrom.server.artifacts.domain.storage.ArtifactAdministrationUseCase
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/** `GET`, `PUT` and `DELETE /api/v1/artifacts/storage` (`getArtifactStorage`, `saveArtifactStorage`...). */
@Serializable
@Resource("/api/v1/artifacts/storage")
class ArtifactStorageApi {
    /**
     * `POST /api/v1/artifacts/storage/test` (`testArtifactStorage`).
     *
     * @property parent The bucket.
     */
    @Serializable
    @Resource("test")
    class Test(val parent: ArtifactStorageApi = ArtifactStorageApi())
}

/** `GET` and `PUT /api/v1/artifacts/settings` (`getArtifactSettings`, `saveArtifactSettings`). */
@Serializable
@Resource("/api/v1/artifacts/settings")
class ArtifactSettingsApi

/** `GET /api/v1/artifacts/policy` (`getArtifactPolicy`). */
@Serializable
@Resource("/api/v1/artifacts/policy")
class ArtifactPolicyApi

/**
 * The bucket as an owner or an administrator sees it (`ArtifactStorage`); the secret is never returned.
 *
 * @property configured Whether one is stored.
 * @property endpoint Its endpoint.
 * @property region Its region.
 * @property bucket Its bucket.
 * @property prefix Its prefix.
 * @property pathStyle Path-style addressing.
 * @property accessKeyId Its key id.
 * @property secretConfigured Whether a secret is stored.
 * @property serverSideEncryption `none` or `AES256`.
 * @property verifiedAt The last passed test of this version.
 * @property version Its version, 0 when none is stored.
 * @property updatedAt Its last save.
 * @property inUse Artifacts exist: the endpoint, bucket and prefix are fixed.
 */
@Serializable
data class ArtifactStorageResponse(
    val configured: Boolean,
    val endpoint: String? = null,
    val region: String? = null,
    val bucket: String? = null,
    val prefix: String? = null,
    val pathStyle: Boolean = false,
    val accessKeyId: String? = null,
    val secretConfigured: Boolean = false,
    val serverSideEncryption: String = "none",
    val verifiedAt: String? = null,
    val version: Int = 0,
    val updatedAt: String? = null,
    val inUse: Boolean = false,
)

/**
 * `SaveArtifactStorageRequest`: [version] is the one replaced; a null [secretAccessKey] keeps the stored one.
 *
 * @property version The version replaced.
 * @property endpoint The endpoint.
 * @property region The region.
 * @property bucket The bucket.
 * @property prefix The prefix.
 * @property pathStyle Path-style addressing.
 * @property accessKeyId The key id.
 * @property secretAccessKey The secret, or null.
 * @property serverSideEncryption `none` or `AES256`.
 */
@Serializable
data class SaveArtifactStorageRequest(
    val version: Int,
    val endpoint: String,
    val region: String,
    val bucket: String,
    val prefix: String = "",
    val pathStyle: Boolean = false,
    val accessKeyId: String,
    val secretAccessKey: String? = null,
    val serverSideEncryption: String = "none",
) {
    override fun toString(): String =
        "SaveArtifactStorageRequest(endpoint=$endpoint, bucket=$bucket, secretAccessKey=[REDACTED])"
}

/**
 * One step of a test (`ArtifactStorageTestStep`).
 *
 * @property step Its name.
 * @property ok Whether it passed.
 * @property status The bucket's status.
 * @property code The S3 or local code.
 */
@Serializable
data class ArtifactStorageTestStepResponse(
    val step: String,
    val ok: Boolean,
    val status: Int? = null,
    val code: String? = null,
)

/**
 * A test (`ArtifactStorageTest`).
 *
 * @property ok Whether it passed.
 * @property steps Its steps.
 */
@Serializable
data class ArtifactStorageTestResponse(val ok: Boolean, val steps: List<ArtifactStorageTestStepResponse>)

/**
 * The settings (`ArtifactSettings`), with whether artifacts are active.
 *
 * @property enabled The switch.
 * @property whoCanPublish `everyone` or `admins`.
 * @property publicSharing `owners_of_artifacts`, `admins` or `off`.
 * @property quotaMemberBytes Bytes per member.
 * @property quotaOrgBytes Bytes for the organization.
 * @property quotaArtifactsPerMember Artifacts per member.
 * @property quotaPublishesPerHour Publications per member and hour.
 * @property personalRetentionDays Days a personal artifact is kept, or null.
 * @property sharedRetentionDays Days a shared artifact is kept, or null.
 * @property maxVersionsPerArtifact Versions kept.
 * @property version Their version.
 * @property updatedAt Their last save.
 * @property active Whether artifacts work now.
 * @property inactiveReason Why not.
 */
@Serializable
data class ArtifactSettingsResponse(
    val enabled: Boolean,
    val whoCanPublish: String,
    val publicSharing: String,
    val quotaMemberBytes: Long,
    val quotaOrgBytes: Long,
    val quotaArtifactsPerMember: Int,
    val quotaPublishesPerHour: Int,
    val personalRetentionDays: Int? = null,
    val sharedRetentionDays: Int? = null,
    val maxVersionsPerArtifact: Int,
    val version: Int,
    val updatedAt: String? = null,
    val active: Boolean,
    val inactiveReason: String? = null,
)

/**
 * `SaveArtifactSettingsRequest`: every setting, with the version replaced.
 *
 * @property version The version replaced.
 * @property enabled The switch.
 * @property whoCanPublish `everyone` or `admins`.
 * @property publicSharing `owners_of_artifacts`, `admins` or `off`.
 * @property quotaMemberBytes Bytes per member.
 * @property quotaOrgBytes Bytes for the organization.
 * @property quotaArtifactsPerMember Artifacts per member.
 * @property quotaPublishesPerHour Publications per member and hour.
 * @property personalRetentionDays Days a personal artifact is kept, or null.
 * @property sharedRetentionDays Days a shared artifact is kept, or null.
 * @property maxVersionsPerArtifact Versions kept.
 */
@Serializable
data class SaveArtifactSettingsRequest(
    val version: Int,
    val enabled: Boolean,
    val whoCanPublish: String,
    val publicSharing: String,
    val quotaMemberBytes: Long,
    val quotaOrgBytes: Long,
    val quotaArtifactsPerMember: Int,
    val quotaPublishesPerHour: Int,
    val personalRetentionDays: Int? = null,
    val sharedRetentionDays: Int? = null,
    val maxVersionsPerArtifact: Int,
)

/**
 * The publication policy (`ArtifactPolicy`).
 *
 * @property canPublish Whether the caller may publish now.
 * @property reason Why not.
 */
@Serializable
data class ArtifactPolicyResponse(val canPublish: Boolean, val reason: String? = null)

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
