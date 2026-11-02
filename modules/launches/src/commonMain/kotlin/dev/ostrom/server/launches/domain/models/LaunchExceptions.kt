// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.domain.models

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException

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
 * The credential is not a person's member of an organization the request can act in: answered like an unknown
 * organization, so that nothing leaks.
 */
class NoLaunchOrganizationException : OstromException(ErrorKind.NOT_FOUND, "Organization not found.")
