// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.domain.storage

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/**
 * Who administers or asks about the organization's artifacts.
 *
 * @property orgId The organization.
 * @property memberId The member.
 * @property administrator An owner or an administrator of the organization.
 * @property person A person presents the credential (a browser session, the unlocked app, the CLI's sign-in).
 * @property mayWrite The credential holds `artifacts:write`.
 */
data class StorageCaller(
    val orgId: kotlin.uuid.Uuid,
    val memberId: String,
    val administrator: Boolean,
    val person: Boolean,
    val mayWrite: Boolean,
)

/** The bucket and the settings are a person's who owns or administers the organization, never a key's. */
class ArtifactAdministratorRequiredException : OstromException(
    ErrorKind.FORBIDDEN,
    "Only an owner or an administrator of the organization, signed in, configures artifacts.",
)

/**
 * A field of the bucket is malformed.
 *
 * @param message What to fix.
 */
class InvalidArtifactStorageException(message: String) :
    OstromException(ErrorKind.INVALID_REQUEST, message, "invalid_artifact_storage")

/** The endpoint names a link-local, unspecified or cloud metadata host. */
class ArtifactStorageHostNotAllowedException : OstromException(
    ErrorKind.INVALID_REQUEST,
    "The endpoint must not point at a link-local or cloud metadata address.",
    "artifact_storage_host_not_allowed",
)

/** A new endpoint or bucket needs its own secret. */
class ArtifactStorageSecretRequiredException : OstromException(
    ErrorKind.INVALID_REQUEST,
    "Changing the endpoint or the bucket needs the secret access key again.",
    "artifact_storage_secret_required",
)

/** The save names a version that is no longer the current one. */
class ArtifactStorageConflictException : OstromException(
    ErrorKind.CONFLICT,
    "The storage changed since it was read. Read it again.",
    "artifact_storage_conflict",
)

/** Artifacts may have files in the bucket: its endpoint, bucket and prefix are fixed, and it cannot be removed. */
class ArtifactStorageInUseException : OstromException(
    ErrorKind.CONFLICT,
    "Artifacts exist in this bucket: its endpoint, bucket and prefix are fixed. The keys can still be rotated.",
    "artifact_storage_in_use",
)

/** A connection test is already running for the organization. */
class ArtifactStorageTestRunningException : OstromException(
    ErrorKind.CONFLICT,
    "A connection test is already running. Wait for its result.",
    "artifact_storage_test_running",
)

/**
 * Artifacts are not active: the reason is the error's key, as TeamCode answers every publication, read and sharing
 * route while they are off.
 *
 * @param reason Why.
 */
class ArtifactsInactiveException(val reason: InactiveReason) : OstromException(
    ErrorKind.CONFLICT,
    when (reason) {
        InactiveReason.STORAGE_MISSING -> "Artifacts are off: an owner or an administrator configures a bucket first."
        InactiveReason.STORAGE_UNVERIFIED -> "Artifacts are off: the bucket changed or failed its last test."
        InactiveReason.DISABLED -> "Artifacts are off for this organization."
    },
    reason.wire,
)

/**
 * A setting is out of its range.
 *
 * @param message What to fix.
 */
class InvalidArtifactSettingsException(message: String) :
    OstromException(ErrorKind.INVALID_REQUEST, message, "invalid_artifact_settings")

/** The save names a version that is no longer the current one. */
class ArtifactSettingsConflictException : OstromException(
    ErrorKind.CONFLICT,
    "The settings changed since they were read. Read them again.",
    "artifact_settings_conflict",
)

/** No bucket is stored: there is nothing to test or remove. */
class ArtifactStorageMissingException : OstromException(
    ErrorKind.NOT_FOUND,
    "No artifact storage is configured. Save one first.",
    InactiveReason.STORAGE_MISSING.wire,
)
