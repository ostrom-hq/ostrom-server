// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.domain

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

/** No such profile in the caller's organization. */
class CloudProfileNotFoundException :
    OstromException(ErrorKind.NOT_FOUND, "No such cloud profile.", "cloud_profile_not_found")

/** Another save of the profile came first. */
class CloudProfileConflictException : OstromException(
    ErrorKind.CONFLICT,
    "The profile was changed meanwhile: reload it and save again.",
    "cloud_profile_conflict",
)
