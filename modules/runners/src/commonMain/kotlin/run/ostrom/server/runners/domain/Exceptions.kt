// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/** The session does not exist or belongs to another organization or member. */
class CloudSessionNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Cloud session not found.")

/** This server prepares no cloud session (no organizations module): the runner clones as before. */
class RunnerPreparationNotServedException :
    OstromException(ErrorKind.NOT_FOUND, "This server prepares no cloud session.")

/**
 * The request is malformed.
 *
 * @param message What to fix.
 */
class InvalidCloudSessionRequestException(message: String) : OstromException(ErrorKind.INVALID_REQUEST, message)

/**
 * Refuses a malformed request.
 *
 * @param condition What a valid request satisfies.
 * @param message What to fix, built only when the request is refused.
 *
 * @throws InvalidCloudSessionRequestException When [condition] is false.
 */
internal inline fun ensureValid(condition: Boolean, message: () -> String) {
    if (!condition) throw InvalidCloudSessionRequestException(message())
}

/** The organization already runs as many sessions as its plan allows. */
class CloudSessionLimitException(max: Int) : OstromException(
    ErrorKind.PAYMENT_REQUIRED,
    "Your plan runs at most $max cloud sessions at once: pause or delete one first.",
)

/** The organization used all the execution hours of its plan this period. */
class CloudHoursExhaustedException(hours: Int) : OstromException(
    ErrorKind.PAYMENT_REQUIRED,
    "Your plan's $hours cloud hours of this period are used up.",
)

/**
 * The session's state does not allow the action.
 *
 * @param message Why.
 */
class CloudSessionStateException(message: String) : OstromException(ErrorKind.CONFLICT, message)

/** The runner's credential is not the current execution's: it must stop and exit (`execution_fenced`, as declared). */
class ExecutionFencedException : OstromException(
    ErrorKind.CONFLICT,
    "This execution is no longer the session's current one.",
    "execution_fenced",
)

/** The runner's credential is unknown. */
class RunnerUnauthenticatedException : OstromException(ErrorKind.UNAUTHORIZED, "A valid runner token is required.")

/** The caller acts for no organization: answered like an unknown organization, so that none leaks. */
class NotAMemberException : OstromException(ErrorKind.NOT_FOUND, "Organization not found.")

/**
 * The caller's credential lacks the scope the action needs.
 *
 * @param scope The missing scope.
 */
class MissingScopeException(scope: String) : OstromException(
    ErrorKind.FORBIDDEN,
    "This credential lacks the $scope scope.",
)

/** Cloud sessions are not open to the organization on this server (audit S2-10). */
class CloudSessionsClosedException : OstromException(
    ErrorKind.FORBIDDEN,
    "Cloud sessions are not open to this organization on this server.",
)

/** Registering a runner is for the organization's owners and admins. */
class RunnerAdminRequiredException : OstromException(
    ErrorKind.FORBIDDEN,
    "Only an owner or an admin of the organization can register a runner.",
)

/** The runner does not exist, or belongs to another organization. */
class RunnerNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Runner not found.")

/** The organization already has a runner of that name, whatever its case. */
class RunnerNameTakenException : OstromException(
    ErrorKind.CONFLICT,
    "This organization already has a runner of that name.",
)

/**
 * The organization already has as many runners as allowed.
 *
 * @param max The most runners an organization may register.
 */
class RunnerLimitException(max: Int) : OstromException(
    ErrorKind.CONFLICT,
    "An organization registers at most $max runners.",
)

/** Only the owner in person changes whose GitHub token a session holds: never an agent or a service key (403). */
class GitHubOwnerOnlyException : OstromException(
    ErrorKind.FORBIDDEN,
    "Only the session's owner, in person, changes its GitHub access.",
    "github_owner_only",
)

/** Removing a session's GitHub access needs it paused: a running execution may hold a token already. */
class SessionNotPausedException : OstromException(
    ErrorKind.CONFLICT,
    "Pause the session before removing its GitHub access.",
    "session_not_paused",
)

/** The session no longer runs: its runner gets no environment variable. */
class VariablesUnavailableException : OstromException(
    ErrorKind.CONFLICT,
    "This session does not run: its environment variables are not served.",
    "variables_unavailable",
)

/** The runner asks for its preparation once its session no longer prepares (ready, paused, or failed already). */
class NotPreparingException : OstromException(
    ErrorKind.CONFLICT,
    "The session is not preparing an environment.",
    "not_preparing",
)

/** The session's environment cannot be prepared: the session records why, and its execution stops. */
class PreparationFailedException : OstromException(
    ErrorKind.CONFLICT,
    "The environment cannot be prepared.",
    CloudEnvironmentRules.PREPARATION_FAILED,
)

/** None of the organization's cloud profiles has this id (`runners.cloud.profiles.v1`). */
class SessionProfileNotFoundException : OstromException(
    ErrorKind.NOT_FOUND,
    "No cloud profile has this id: list the profiles you may use with GET /api/v1/cloud/profiles.",
    "cloud_profile_not_found",
)

/** The profile is not assigned to the session's owner, who is neither an owner nor an admin. */
class ProfileUnassignedException : OstromException(
    ErrorKind.FORBIDDEN,
    "This cloud profile is not assigned to you: list the profiles you may use, or ask an administrator.",
    "profile_unassigned",
)

/** No profile was named, and the owner may use none or several. */
class ProfileRequiredException : OstromException(
    ErrorKind.UNPROCESSABLE,
    "Name the cloud profile the session runs on (profileId): you may use none or several.",
    "profile_required",
)

/** The image is neither the profile's nor one of its allowed overrides. */
class ImageNotAllowedException : OstromException(
    ErrorKind.FORBIDDEN,
    "This image does not match the profile's allowed image overrides.",
    "image_not_allowed",
)

/** The profile's cluster is gone, or its credentials must be entered again. */
class ClusterUnavailableException : OstromException(
    ErrorKind.CONFLICT,
    "The profile's cluster is no longer configured, or its credentials must be entered again.",
    "cluster_unavailable",
)

/** The profile's cluster no longer allows its namespace. */
class NamespaceNotAllowedException : OstromException(
    ErrorKind.CONFLICT,
    "The profile's namespace is no longer allowed on its cluster.",
    "namespace_not_allowed",
)
