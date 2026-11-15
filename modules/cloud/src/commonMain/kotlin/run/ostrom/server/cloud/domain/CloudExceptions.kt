// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.domain

import run.ostrom.core.api.OnBehalfOf
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/** The caller is not a person who administers the instance. */
class CloudAdminRequiredException : OstromException(
    ErrorKind.FORBIDDEN,
    "Only an administrator of this instance, signed in, does this.",
    "instance_admin_required",
)

/**
 * A runtime value the server refuses, naming the field, never echoing a secret.
 *
 * @param message Why.
 */
class InvalidCloudRuntimeException(message: String) :
    OstromException(ErrorKind.INVALID_REQUEST, message, "invalid_cloud_runtime")

/** Another save of the runtime came first. */
class CloudRuntimeConflictException : OstromException(
    ErrorKind.CONFLICT,
    "The cloud runtime was changed meanwhile: reload it and save again.",
    "cloud_runtime_conflict",
)

/**
 * A cluster the server refuses, naming the field, never echoing a credential.
 *
 * @param message Why.
 */
class InvalidCloudClusterException(message: String) :
    OstromException(ErrorKind.INVALID_REQUEST, message, "invalid_cloud_cluster")

/** No such cluster. */
class CloudClusterNotFoundException :
    OstromException(ErrorKind.NOT_FOUND, "No such cluster.", "cloud_cluster_not_found")

/** Another save of the cluster came first, or a cluster of that name exists. */
class CloudClusterConflictException : OstromException(
    ErrorKind.CONFLICT,
    "The cluster was changed meanwhile, or another one has this name: reload and save again.",
    "cloud_cluster_conflict",
)

/**
 * A profile the server refuses, naming the field.
 *
 * @param message Why.
 */
class InvalidCloudProfileException(message: String) :
    OstromException(ErrorKind.INVALID_REQUEST, message, "invalid_cloud_profile")

/** A profile's workspace storage class does not exist on its cluster (`runners.cloud.retention.v1`). */
class StorageClassMissingException : OstromException(
    ErrorKind.UNPROCESSABLE,
    "The workspace storage class does not exist on the cluster.",
    "storage_class_missing",
)

/**
 * A profile's workspace storage class keeps the provider's volumes once their claim is deleted: a deleted session would
 * leave its volume behind (`runners.cloud.retention.v1`).
 *
 * @param name The class.
 * @param reclaimPolicy Its reclaim policy.
 */
class StorageClassRetainsVolumesException(name: String, reclaimPolicy: String) : OstromException(
    ErrorKind.UNPROCESSABLE,
    "Storage class $name has reclaimPolicy $reclaimPolicy; workspaces require reclaimPolicy Delete so deleted " +
        "sessions leave no provider volume.",
    "storage_class_retains_volumes",
)

/** No such profile in the caller's organization. */
class CloudProfileNotFoundException :
    OstromException(ErrorKind.NOT_FOUND, "No such cloud profile.", "cloud_profile_not_found")

/** Another save of the profile came first. */
class CloudProfileConflictException : OstromException(
    ErrorKind.CONFLICT,
    "The profile was changed meanwhile: reload it and save again.",
    "cloud_profile_conflict",
)

/**
 * A cloud settings value the server refuses, naming the field.
 *
 * @param message Why.
 */
class InvalidCloudSettingsException(message: String) :
    OstromException(ErrorKind.INVALID_REQUEST, message, "invalid_cloud_settings")

/** Another save of the organization's cloud settings came first. */
class CloudSettingsConflictException : OstromException(
    ErrorKind.CONFLICT,
    "The cloud settings were changed meanwhile: reload them and save again.",
    "cloud_settings_conflict",
)

/**
 * Only an owner or an admin lists another member's profiles (403 `forbidden`, TeamCode's
 * `requireCloudAdministrator`).
 */
class ProfilesMemberForbiddenException : OstromException(
    ErrorKind.FORBIDDEN,
    "Only an owner or an admin of the organization lists another member's profiles.",
)

/**
 * The member named is not an active member of the organization (422 `invalid_effective_member`, as
 * `Ostrom-On-Behalf-Of`).
 */
class InvalidEffectiveMemberException : OstromException(
    ErrorKind.UNPROCESSABLE,
    "The member must be an active member of the organization, named by id or login.",
    OnBehalfOf.INVALID_MEMBER,
)

/**
 * A query parameter read once was sent several times (400 `invalid_query`, TeamCode's).
 *
 * @param name The parameter.
 */
class InvalidQueryException(name: String) :
    OstromException(ErrorKind.INVALID_REQUEST, "Send $name at most once.", "invalid_query")
