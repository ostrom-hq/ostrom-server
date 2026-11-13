// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts.domain.storage

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The organization's S3-compatible bucket, as stored; the secret access key is never part of it (it stays sealed in
 * the repository).
 *
 * @property orgId The organization.
 * @property endpoint `https://host[:port]`.
 * @property region The signing region.
 * @property bucket The bucket.
 * @property prefix The key prefix, empty for the whole bucket.
 * @property pathStyle Path-style addressing.
 * @property accessKeyId The key id.
 * @property serverSideEncryption What writes ask for.
 * @property verifiedAt The last passed connection test of this [version]; null after any change or a failed test.
 * @property version 1 after the first save, one more at each.
 * @property updatedAt The last save.
 */
data class ArtifactStorage(
    val orgId: Uuid,
    val endpoint: String,
    val region: String,
    val bucket: String,
    val prefix: String,
    val pathStyle: Boolean,
    val accessKeyId: String,
    val serverSideEncryption: ServerSideEncryption,
    val verifiedAt: Instant?,
    val version: Int,
    val updatedAt: Instant,
)

/**
 * What an owner or an administrator saves.
 *
 * @property endpoint `https://host[:port]`.
 * @property region The signing region.
 * @property bucket The bucket.
 * @property prefix The key prefix, empty for the whole bucket.
 * @property pathStyle Path-style addressing.
 * @property accessKeyId The key id.
 * @property secretAccessKey The secret, or null to keep the stored one (refused when the endpoint or bucket changes).
 * @property serverSideEncryption What writes ask for.
 */
class ArtifactStorageInput(
    val endpoint: String,
    val region: String,
    val bucket: String,
    val prefix: String,
    val pathStyle: Boolean,
    val accessKeyId: String,
    val secretAccessKey: String?,
    val serverSideEncryption: ServerSideEncryption,
) {
    override fun toString(): String =
        "ArtifactStorageInput(endpoint=$endpoint, bucket=$bucket, prefix=$prefix, secretAccessKey=[REDACTED])"
}

/** Who may publish once artifacts are on. */
enum class WhoCanPublish(val wire: String) {
    /** Every member. */
    EVERYONE("everyone"),

    /** Owners and administrators only. */
    ADMINS("admins"),
    ;

    /** Reads the wire names. */
    companion object {
        /**
         * Reads a wire name.
         *
         * @return The value, or null when unknown.
         */
        fun parse(value: String): WhoCanPublish? = entries.singleOrNull { it.wire == value }
    }
}

/** Who may make an artifact public (`link`): its owner, its owner only when an administrator, or nobody. */
enum class PublicSharing(val wire: String) {
    /** The artifact's owner. */
    OWNERS("owners_of_artifacts"),

    /** The owner, when an owner or an administrator of the organization. */
    ADMINS("admins"),

    /** Nobody: every public link stops at once. */
    OFF("off"),
    ;

    /** Reads the wire names. */
    companion object {
        /**
         * Reads a wire name.
         *
         * @return The value, or null when unknown.
         */
        fun parse(value: String): PublicSharing? = entries.singleOrNull { it.wire == value }
    }
}

/**
 * The quotas, TeamCode's defaults.
 *
 * @property memberBytes What one member's artifacts may hold.
 * @property orgBytes What the organization's may hold.
 * @property artifactsPerMember Published artifacts per member.
 * @property publishesPerHour Publications per member and hour.
 */
data class ArtifactQuotas(
    val memberBytes: Long = 2L * GIB,
    val orgBytes: Long = 50L * GIB,
    val artifactsPerMember: Int = 500,
    val publishesPerHour: Int = 60,
) {
    /** The units. */
    companion object {
        /** One mebibyte. */
        const val MIB: Long = 1024L * 1024

        /** One gibibyte. */
        const val GIB: Long = 1024L * MIB
    }
}

/**
 * Retention counts from an artifact's last publication; a change applies to artifacts published afterwards.
 *
 * @property personalDays Days a `personal` artifact is kept, or null to keep it.
 * @property sharedDays Days a `team` or `link` artifact is kept, or null to keep it.
 * @property maxVersionsPerArtifact Older versions are pruned beyond it.
 */
data class ArtifactRetention(
    val personalDays: Int? = null,
    val sharedDays: Int? = null,
    val maxVersionsPerArtifact: Int = 50,
)

/**
 * The organization's artifact settings.
 *
 * @property enabled The switch; it needs a tested bucket.
 * @property whoCanPublish Who publishes.
 * @property publicSharing Who makes an artifact public.
 * @property quotas The quotas.
 * @property retention The retention.
 * @property version 0 until saved once; a save names the version it replaces.
 * @property updatedAt The last save.
 */
data class ArtifactSettings(
    val enabled: Boolean = false,
    val whoCanPublish: WhoCanPublish = WhoCanPublish.EVERYONE,
    val publicSharing: PublicSharing = PublicSharing.OWNERS,
    val quotas: ArtifactQuotas = ArtifactQuotas(),
    val retention: ArtifactRetention = ArtifactRetention(),
    val version: Int = 0,
    val updatedAt: Instant? = null,
)

/** Why artifacts do not work, TeamCode's `inactiveReason`. */
enum class InactiveReason(val wire: String) {
    /** No bucket is stored. */
    STORAGE_MISSING("artifact_storage_missing"),

    /** The bucket changed since its last passed test, or its last test failed. */
    STORAGE_UNVERIFIED("artifact_storage_unverified"),

    /** The bucket works but the switch is off. */
    DISABLED("artifacts_disabled"),
}

/**
 * Whether artifacts work right now: switched on, with a stored bucket whose current version passed a test.
 *
 * @property reason Why not, or null when they work.
 */
data class ArtifactAvailability(val reason: InactiveReason?) {
    /** True when artifacts work. */
    val active: Boolean get() = reason == null

    /** How it is decided. */
    companion object {
        /**
         * Decides it.
         *
         * @return The availability.
         */
        fun of(settings: ArtifactSettings, storage: ArtifactStorage?): ArtifactAvailability = ArtifactAvailability(
            when {
                storage == null -> InactiveReason.STORAGE_MISSING
                storage.verifiedAt == null -> InactiveReason.STORAGE_UNVERIFIED
                !settings.enabled -> InactiveReason.DISABLED
                else -> null
            },
        )
    }
}

/**
 * The bucket, the settings and whether artifacts may have files in the bucket, read together.
 *
 * @property storage The bucket, or null.
 * @property settings The settings (defaults before the first save).
 * @property inUse True once any artifact exists: the endpoint, bucket and prefix are then fixed.
 */
data class ArtifactStorageOverview(
    val storage: ArtifactStorage?,
    val settings: ArtifactSettings,
    val inUse: Boolean,
) {
    /** Whether artifacts work. */
    val availability: ArtifactAvailability get() = ArtifactAvailability.of(settings, storage)
}

/**
 * One step of a connection test.
 *
 * @property step `put`, `get`, `list`, `delete` or `record`.
 * @property ok Whether it passed.
 * @property status The bucket's HTTP status, when it answered one.
 * @property code The S3 error code or a short local code, never a body.
 */
data class StorageTestStep(val step: String, val ok: Boolean, val status: Int? = null, val code: String? = null)

/**
 * A connection test.
 *
 * @property ok Whether every step passed and the result was recorded.
 * @property steps The steps, in order.
 */
data class StorageTestResult(val ok: Boolean, val steps: List<StorageTestStep>)

/** Why a member may not publish now, besides [InactiveReason]. */
enum class PublishRefusal(val wire: String) {
    /** Only owners and administrators publish, and the caller is neither. */
    FORBIDDEN("artifact_publish_forbidden"),

    /** The credential lacks `artifacts:write`. */
    CREDENTIAL_SCOPE("credential_scope"),
}

/**
 * The publication policy (`GET /api/v1/artifacts/policy`).
 *
 * @property reason Why the caller may not publish, or null when they may: an [InactiveReason] or a
 *   [PublishRefusal] wire name.
 */
data class ArtifactPolicy(val reason: String?) {
    /** True when the caller may publish now. */
    val canPublish: Boolean get() = reason == null
}
