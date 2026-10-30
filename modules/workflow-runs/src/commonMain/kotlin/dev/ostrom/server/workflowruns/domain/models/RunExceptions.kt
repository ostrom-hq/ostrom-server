// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.domain.models

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException

/** No package of the organization has this digest. */
class RunPackageNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Package not found.")

/**
 * The package holds no such script or notification.
 *
 * @param what What was asked for.
 */
class NotInPackageException(what: String) : OstromException(ErrorKind.NOT_FOUND, "$what is not in this package.")

/** The package was revoked: Ostrom sends nothing in its name. */
class RunPackageRevokedException : OstromException(ErrorKind.CONFLICT, "Package revoked.")

/** The session runs another package than the one the report names. */
class RunPackageMismatchException : OstromException(ErrorKind.CONFLICT, "This session runs another package.")

/**
 * The session is still open: its workflow receipt is signed once, after it ends, with everything it reported.
 *
 * @param sessionId The session.
 */
class SessionStillOpenException(sessionId: String) : OstromException(
    ErrorKind.CONFLICT,
    "Session ${sessionId.take(ID_SHOWN)} is still open: end it before signing its workflow receipt.",
)

/** No session of the organization, or none the caller may see, has this id. */
class RunSessionNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Session not found.")

/**
 * A field of the request is malformed.
 *
 * @param reason Which, and what it must be.
 */
class InvalidRunException(reason: String) : OstromException(ErrorKind.INVALID_REQUEST, reason)

// The most of an id a message shows.
private const val ID_SHOWN = 200
