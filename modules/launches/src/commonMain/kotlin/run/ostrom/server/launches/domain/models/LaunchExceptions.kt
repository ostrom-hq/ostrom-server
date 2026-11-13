// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches.domain.models

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import kotlin.time.Duration

/**
 * The organization's policy refuses the launch: the agent does not start.
 *
 * @param reason Why, as the policy says it.
 */
class LaunchRefusedException(reason: String) : OstromException(ErrorKind.FORBIDDEN, reason)

/**
 * A field of the launch is malformed.
 *
 * @param reason Which, and what it must be.
 */
class InvalidLaunchException(reason: String) : OstromException(ErrorKind.INVALID_REQUEST, reason)

/**
 * The person, or the organization, launched as many agents as [LaunchLimit] allows in its window: each launch issues
 * a 24-hour agent token, so a stolen `sessions:write` credential cannot mint thousands of them (AUDIT-SECU-2 B9).
 *
 * @property retryAfter How long until the oldest launch counted leaves the window (the `Retry-After` header).
 */
class LaunchRateLimitedException(val retryAfter: Duration) : OstromException(
    ErrorKind.RATE_LIMITED,
    "Too many launches: try again in ${retryAfter.inWholeSeconds + 1} s.",
)

/**
 * The credential is not a person's member of an organization the request can act in: answered like an unknown
 * organization, so that nothing leaks.
 */
class NoLaunchOrganizationException : OstromException(ErrorKind.NOT_FOUND, "Organization not found.")

/** A member who is not an admin asked for another developer's verdict: only an owner or an admin reads it. */
class OtherDeveloperForbiddenException : OstromException(
    ErrorKind.FORBIDDEN,
    "Only an owner or an admin of the organization reads another developer's verdict.",
)

/**
 * The developer an admin named is not a member of the organization: answered as unknown, whether the login exists
 * elsewhere or not.
 */
class UnknownDeveloperException : OstromException(ErrorKind.NOT_FOUND, "Developer not found.")

/**
 * The workflow a launch names is not one of its organization's: answered like an unknown workflow, whether it exists in
 * another organization or not.
 */
class LaunchWorkflowNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Workflow not found.")

/**
 * The launch does not exist, or the caller is not its own agent token: answered the same, so that a credential
 * learns nothing about launches it does not hold.
 */
class LaunchNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Launch not found.")
