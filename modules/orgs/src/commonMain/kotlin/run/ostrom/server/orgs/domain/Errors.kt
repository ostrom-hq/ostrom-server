// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.orgs.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/**
 * The organization does not exist, or the caller is not a member: the two are answered alike, so
 * neither leaks.
 */
class OrgNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Organization not found.")

/** No member of the organization has this login. */
class MemberNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Member not found.")

/**
 * On an invite-only instance, only owners and admins of an organization (and the person of the instance's setup
 * link) create organizations.
 */
class OrgCreationClosedException : OstromException(
    ErrorKind.FORBIDDEN,
    "On this instance, an owner or an admin of an organization creates organizations: ask one of them.",
    "org_creation_closed",
)

/** No live invitation matches.
class InvitationNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Invitation not found.")

/**
 * The caller's role does not allow the action.
 *
 * @param reason What is refused, safe for the client.
 */
class NotAllowedException(reason: String) : OstromException(ErrorKind.FORBIDDEN, reason)

/**
 * A request value is invalid.
 *
 * @param reason What is wrong, safe for the client.
 */
class InvalidOrgRequestException(reason: String) :
    OstromException(ErrorKind.INVALID_REQUEST, reason)

/**
 * The owner's sign-in is older than [DELETION_STEP_UP], or of unknown age: deleting the organization needs a fresh
 * one, as a yes does (S2-54).
 */
class OrgStepUpRequiredException :
    OstromException(
        ErrorKind.FORBIDDEN,
        "Sign in again to delete the organization: it needs a sign-in of less than 5 minutes.",
        "step_up_required",
    )

/** The short name is already used by another organization. */
class SlugTakenException : OstromException(ErrorKind.CONFLICT, "This short name is already taken.")

/** The user is already a member. */
class AlreadyMemberException :
    OstromException(ErrorKind.CONFLICT, "You are already a member of this organization.")

/**
 * The free plan is already used by another team of the same GitHub organization, company domain or
 * person.
 *
 * @param holder What the free plan is held against, as the client may see it (`the domain
 *   acme.com`).
 */
class FreeTeamTakenException(holder: String) :
    OstromException(
        ErrorKind.PAYMENT_REQUIRED,
        "A free team already exists for $holder: join it, or upgrade it to add another team.",
    )

/** The organization's plan has no room for one more person. */
class SeatLimitException :
    OstromException(
        ErrorKind.PAYMENT_REQUIRED,
        "The organization's plan has no seat left: upgrade it to add people.",
    )

/** Too many attempts from the same caller. */
class OrgRateLimitedException :
    OstromException(ErrorKind.RATE_LIMITED, "Too many attempts. Try again later.")

/**
 * Throws [error] unless [condition] holds: one throw site for the domain's checks.
 *
 * @param condition What must hold.
 * @param error The exception to throw otherwise.
 */
internal inline fun ensure(condition: Boolean, error: () -> OstromException) {
    if (!condition) throw error()
}
