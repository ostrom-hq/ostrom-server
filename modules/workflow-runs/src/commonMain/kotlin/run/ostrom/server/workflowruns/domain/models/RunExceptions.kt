// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns.domain.models

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

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

/** No workflow of the organization has this id. */
class RunWorkflowNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Workflow not found.")

/** The workflow is a proposal: a person accepts it before anything runs it. */
class WorkflowNotAcceptedException : OstromException(
    ErrorKind.CONFLICT,
    "workflow_not_accepted: accept the workflow before running it.",
)

/** Only an owner or an admin of the organization starts a run, with a person's credential. */
class StartRunForbiddenException : OstromException(
    ErrorKind.FORBIDDEN,
    "Only an owner or an admin of the organization starts a workflow run.",
)

/** No run of the organization has this id. */
class RunNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Run not found.")

/** Only an owner or an admin of the organization cancels a run, with a person's credential. */
class CancelRunForbiddenException : OstromException(
    ErrorKind.FORBIDDEN,
    "Only an owner or an admin of the organization cancels a workflow run.",
)

/** The run already ended: succeeded, failed or cancelled. */
class RunEndedException : OstromException(ErrorKind.CONFLICT, "run_ended: the run already ended.")

/** The run kept changing while it was cancelled (the engine played it meanwhile). */
class RunBusyException : OstromException(ErrorKind.CONFLICT, "run_busy: the run changed meanwhile, try again.")

/**
 * A delivery that is not what the agent node asks for: nothing is stored.
 *
 * @param reason What to deliver instead.
 */
class InvalidDeliverableException(reason: String) :
    OstromException(ErrorKind.INVALID_REQUEST, reason, "invalid_deliverable")

/** The run's current node waits for no delivery, or its delivery already came. */
class NothingToDeliverException : OstromException(
    ErrorKind.CONFLICT,
    "This run is not waiting for a delivery.",
    "nothing_to_deliver",
)

/**
 * The session of the step still has open todo items: the step delivers once its list is done.
 *
 * @param reason Which, as the todos say it.
 */
class TodoOpenException(reason: String) : OstromException(ErrorKind.CONFLICT, reason, "todo_open")
