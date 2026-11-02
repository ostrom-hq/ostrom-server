// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.domain.models

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
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
