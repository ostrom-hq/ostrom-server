// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.orgs.presentation

import run.ostrom.server.kernel.auth.GovernanceConfirmationPending
import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.empty
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.pathId
import run.ostrom.server.kernel.http.docs.queryLimit
import run.ostrom.server.kernel.http.docs.queryText
import io.ktor.http.HttpStatusCode

private const val TAG = "organization"

private const val CONF = "The person already has 10 governance confirmations waiting " +
    "(`confirmation_limit`); expired, refused and answered ones do not count."

private const val INVITE_LIMITS = "An invalid address, role or count (`invalid_request`)."

private const val MEMBER_NF = "No such organization or member (`not_found`)."

private const val ORG_NF = "No such organization, or one the caller is not a member of (`not_found`)."

private const val ORG_REF = "The organization's id or short name."

private const val PENDING = "Not applied: made with a person's API token (a personal token, the CLI's " +
    "included), the write opened a confirmation that an owner or an admin confirms on the console within 15 " +
    "minutes (`getGovernanceConfirmation`)."

private const val RATE = "Too many invitations this hour (`rate_limited`); or the person already has 10 " +
    "governance confirmations waiting (`confirmation_limit`)."

private const val SEATS = "The organization's plan has no seat left (`payment_required`)."

private const val STEP = "A write from the console needs a sign-in less than 5 minutes old " +
    "(`step_up_required`); sign in again, then send it again. Otherwise the caller's role does not allow it " +
    "(`forbidden`)."

/**
 * The orgs module's operations: the caller's organizations, the members and invitations of one, and what a
 * person with an invitation link does with it. The answers are the module's own types.
 */
internal fun ApiCatalog.orgsOperations() {
    tag(TAG, "The organizations a person belongs to, their members, and the invitations that bring people " +
        "in.")
    organizationOperations()
    organizationOperations2()
    organizationLifecycleOperations()
    memberOperations()
    memberOperations2()
    invitationOperations()
    invitationOperations2()
    invitationLinkOperations()
}

/** The caller's organizations: listing, creating, reading and changing one. */
private fun ApiCatalog.organizationOperations() {
    get("/api/v1/me/organizations") {
        operationId = "listMyOrganizations"
        tag(TAG)
        summary = "The organizations the caller is an active member of."
        description = "To choose the one sent in `Ostrom-Org`. Needs no `Ostrom-Org` itself. Scope " +
            "`orgs:read`."
        responses {
            ok<MyOrganizationsResponse>("The organizations, by name.")
        }
    }
    get("/api/v1/orgs") {
        operationId = "listOrgs"
        tag(TAG)
        summary = "The caller's organizations."
        description = "Scope `orgs:read`."
        responses {
            ok<OrgsResponse>("The caller's organizations, each with their role there.")
        }
    }
    post("/api/v1/orgs") {
        operationId = "createOrg"
        tag(TAG)
        summary = "Create an organization; the caller owns it."
        description = "Scope `orgs:write`. On an invite-only instance (`SignInMethods.signUp` `invite`), " +
            "only an owner or an admin of an organization, or the person who used the instance's setup link, " +
            "creates one: anyone else gets 403 `org_creation_closed`."
        body<CreateOrgRequest>("The name, and optionally the short name (derived from the name when " +
            "absent).")
        responses {
            ok<OrgResponse>("The organization, created.", HttpStatusCode.Created)
            badRequest("An invalid name or short name, or a reserved one (`invalid_request`).")
            error(HttpStatusCode.Forbidden, "The instance is invite-only and the caller may not create one " +
                "(`org_creation_closed`).")
            error(HttpStatusCode.PaymentRequired, "A free team already exists for the same GitHub " +
                "organization, domain or person (`payment_required`).")
            conflict("The short name is taken, by a live organization or by a deleted one in its 30 days of " +
                "grace (`conflict`).")
            error(HttpStatusCode.TooManyRequests, "Too many organizations created this hour " +
                "(`rate_limited`).")
        }
    }
    get("/api/v1/orgs/{org}") {
        operationId = "getOrg"
        tag(TAG)
        summary = "One organization."
        description = "Every route under `/api/v1/orgs/{org}` first resolves the caller as a member of that " +
            "organization: a 404 for a missing organization as for someone else's. People only: an agent, " +
            "device or service token gets 403. Scope `orgs:read`."
        parameters {
            pathId("org", ORG_REF)
        }
        responses {
            ok<OrgResponse>("The organization, with the caller's role.")
            notFound(ORG_NF)
        }
    }
}

/** The caller's organizations: listing, creating, reading and changing one. */
private fun ApiCatalog.organizationOperations2() {
    patch("/api/v1/orgs/{org}") {
        operationId = "renameOrg"
        tag(TAG)
        summary = "Change an organization's settings."
        description = "Scope `orgs:write`. The role is checked before the body: a member who is not an " +
            "owner or an admin gets 403 whatever they send. A field left out stays as it is, so an empty body " +
            "changes nothing. The short name is accepted only unchanged."
        parameters {
            pathId("org", ORG_REF)
        }
        body<UpdateOrgRequest>("The new name, time zone and hooks deadline; each field left out stays as it " +
            "is.")
        responses {
            ok<OrgResponse>("The organization, changed.")
            badRequest("An invalid name, time zone or hooks deadline, or a short name that differs " +
                "(`invalid_request`).")
            notFound(ORG_NF)
        }
    }
}

/** Deleting an organization, handing it over and leaving it. */
private fun ApiCatalog.organizationLifecycleOperations() {
    delete("/api/v1/orgs/{org}") {
        operationId = "deleteOrg"
        tag(TAG)
        summary = "Delete an organization."
        description = "Its short name typed again in the body. A browser session only, never a token, " +
            "signed in less than 5 minutes ago. The organization is kept 30 days, then erased; its short name " +
            "stays taken until it is erased. A second deletion changes nothing."
        parameters {
            pathId("org", ORG_REF)
        }
        body<DeleteOrgRequest>("The organization's short name, typed again.")
        responses {
            empty("The organization is deleted: kept 30 days, then erased.")
            badRequest("The short name typed does not match (`invalid_request`).")
            error(HttpStatusCode.Forbidden, "Not the owner, not a browser session, or a sign-in older than " +
                "5 minutes (`step_up_required`).")
            notFound(ORG_NF)
        }
    }
    post("/api/v1/orgs/{org}/transfer") {
        operationId = "transferOrg"
        tag(TAG)
        summary = "Give the ownership to another member."
        description = "A browser session only, never a token, signed in less than 5 minutes ago."
        parameters {
            pathId("org", ORG_REF)
        }
        body<TransferRequest>("The login of the member who becomes the owner.")
        responses {
            empty("The ownership is transferred.")
            badRequest("The new owner is the caller (`invalid_request`).")
            error(HttpStatusCode.Forbidden, STEP)
            notFound("No such organization or member (`not_found`).")
        }
    }
    post("/api/v1/orgs/{org}/leave") {
        operationId = "leaveOrg"
        tag(TAG)
        summary = "The caller leaves the organization."
        description = "An owner transfers the ownership or deletes the organization first."
        parameters {
            pathId("org", ORG_REF)
        }
        responses {
            empty("The caller is no longer a member.")
            error(HttpStatusCode.Forbidden, "The caller is the owner (`forbidden`).")
            notFound(ORG_NF)
        }
    }
}

/** The members of an organization. */
private fun ApiCatalog.memberOperations() {
    get("/api/v1/orgs/{org}/members") {
        operationId = "listOrgMembers"
        tag(TAG)
        summary = "The members, oldest first, a page at a time."
        description = "Scope `orgs:read`. A service key holding `team:read` reads it too, as the person who " +
            "created it."
        parameters {
            pathId("org", ORG_REF)
            queryLimit("The most members of the page, 1 to 1000; 1000 without it.", maximum = 1000)
            queryText("after", "The `next` of the previous page: the members who joined after this one.")
            queryText("q", "Only the members whose login or name starts with it, whatever the case; 1 to " +
                "100 characters.")
        }
        responses {
            ok<MembersResponse>("A page of members, `next` set when another follows.")
            badRequest("A `limit`, `q` or `after` out of bounds (`invalid_request`).")
            notFound(ORG_NF)
        }
    }
    get("/api/v1/orgs/{org}/members/{login}") {
        operationId = "getOrgMember"
        tag(TAG)
        summary = "One member, by login."
        description = "Scope `orgs:read`."
        parameters {
            pathId("org", ORG_REF)
            pathId("login", "The member's login.")
        }
        responses {
            ok<MemberResponse>("The member.")
            notFound(MEMBER_NF)
        }
    }
    patch("/api/v1/orgs/{org}/members/{login}") {
        operationId = "updateOrgMember"
        tag(TAG)
        summary = "Change a member's role."
        description = "Made with a person's API token (`orgs:write`, a personal token), the write is not " +
            "applied: it opens a governance confirmation (202) that an owner or an admin confirms on the " +
            "console within 15 minutes, as the rules' writes do. From the console it applies at once. The role " +
            "is `admin`, `member`, `approver` or `billing`: the ownership moves with the transfer."
        parameters {
            pathId("org", ORG_REF)
            pathId("login", "The member's login.")
        }
        body<UpdateMemberRequest>("The new role.")
        responses {
            ok<MemberResponse>("The member, with their new role.")
            ok<GovernanceConfirmationPending>(PENDING, HttpStatusCode.Accepted)
            badRequest("An unknown role, or `owner` (`invalid_request`).")
            error(HttpStatusCode.Forbidden, STEP)
            notFound(MEMBER_NF)
            error(HttpStatusCode.TooManyRequests, CONF)
        }
    }
}

/** The members of an organization. */
private fun ApiCatalog.memberOperations2() {
    delete("/api/v1/orgs/{org}/members/{login}") {
        operationId = "removeOrgMember"
        tag(TAG)
        summary = "Remove a member."
        description = "Made with a person's API token (`orgs:write`, a personal token), the write is not " +
            "applied: it opens a governance confirmation (202) that an owner or an admin confirms on the " +
            "console within 15 minutes, as the rules' writes do. From the console it applies at once."
        parameters {
            pathId("org", ORG_REF)
            pathId("login", "The member's login.")
        }
        responses {
            empty("The member is removed.")
            ok<GovernanceConfirmationPending>(PENDING, HttpStatusCode.Accepted)
            error(HttpStatusCode.Forbidden, STEP)
            notFound(MEMBER_NF)
            error(HttpStatusCode.TooManyRequests, CONF)
        }
    }
}

/** Inviting by e-mail or by link, and revoking an invitation. */
private fun ApiCatalog.invitationOperations() {
    post("/api/v1/orgs/{org}/members/invites") {
        operationId = "inviteOrgMembers"
        tag(TAG)
        summary = "Invite people by e-mail."
        description = "Members and addresses already invited are left out of `invited`. Made with a " +
            "person's API token (`orgs:write`, a personal token), the write is not applied: it opens a " +
            "governance confirmation (202) that an owner or an admin confirms on the console within 15 minutes, " +
            "as the rules' writes do. From the console it applies at once."
        parameters {
            pathId("org", ORG_REF)
        }
        body<InviteRequestBody>("The invitations, each with an `email` and an optional `role` (`member` by " +
            "default).")
        responses {
            ok<InvitedResponse>("The addresses invited.")
            ok<GovernanceConfirmationPending>(PENDING, HttpStatusCode.Accepted)
            badRequest(INVITE_LIMITS)
            error(HttpStatusCode.PaymentRequired, SEATS)
            error(HttpStatusCode.Forbidden, STEP)
            notFound(ORG_NF)
            error(HttpStatusCode.TooManyRequests, RATE)
        }
    }
    get("/api/v1/orgs/{org}/invitations") {
        operationId = "listInvitations"
        tag(TAG)
        summary = "The live invitations, by e-mail and by link."
        description = "Scope `orgs:read`."
        parameters {
            pathId("org", ORG_REF)
        }
        responses {
            ok<InvitationsResponse>("The live invitations.")
            notFound(ORG_NF)
        }
    }
}

/** Inviting by e-mail or by link, and revoking an invitation. */
private fun ApiCatalog.invitationOperations2() {
    post("/api/v1/orgs/{org}/invitations/links") {
        operationId = "createInvitationLink"
        tag(TAG)
        summary = "Create an invitation link."
        description = "The answer is the only time its URL is shown. Made with a person's API token " +
            "(`orgs:write`, a personal token), the write is not applied: it opens a governance confirmation " +
            "(202) that an owner or an admin confirms on the console within 15 minutes, as the rules' writes " +
            "do. From the console it applies at once. Confirmed, the URL is shown once, to the person who " +
            "asked, in the confirmation's `result` (`GET /confirmations/{id}`): their first read with a token " +
            "returns `{url, invitation}`; later reads, and any console session, get `{invitation, shown: " +
            "true}`, without the URL. A first answer lost on the way is not shown again: revoke the link and " +
            "create another."
        parameters {
            pathId("org", ORG_REF)
        }
        body<CreateLinkRequest>("The role it gives (`member` by default) and how many people it can bring " +
            "in, 1 to 100 (10 by default).")
        responses {
            ok<InvitationLinkResponse>("The invitation and its URL, shown this once.", HttpStatusCode.Created)
            ok<GovernanceConfirmationPending>(PENDING, HttpStatusCode.Accepted)
            badRequest(INVITE_LIMITS)
            error(HttpStatusCode.Forbidden, STEP)
            notFound(ORG_NF)
            error(HttpStatusCode.TooManyRequests, RATE)
        }
    }
    delete("/api/v1/orgs/{org}/invitations/{id}") {
        operationId = "revokeInvitation"
        tag(TAG)
        summary = "Revoke an invitation."
        parameters {
            pathId("org", ORG_REF)
            pathId("id", "The invitation's id.")
        }
        responses {
            empty("The invitation is revoked.")
            notFound("No such organization or live invitation (`not_found`).")
        }
    }
}

/** What a person with an invitation link does with it. */
private fun ApiCatalog.invitationLinkOperations() {
    post("/api/v1/invitations/preview") {
        operationId = "previewInvitation"
        tag(TAG)
        summary = "What an invitation leads to, before signing in."
        description = "The token goes in the body, never in a URL."
        body<InvitationTokenRequest>("The invitation link's token.")
        responses {
            ok<InvitationPreviewResponse>("The organization, role and expiry the invitation leads to.")
            notFound("No live invitation matches the token (`not_found`).")
        }
    }
    post("/api/v1/invitations/accept") {
        operationId = "acceptInvitation"
        tag(TAG)
        summary = "Join the organization an invitation leads to."
        description = "Scope `orgs:write`."
        body<InvitationTokenRequest>("The invitation link's token.")
        responses {
            ok<OrgResponse>("The organization joined.")
            error(HttpStatusCode.PaymentRequired, SEATS)
            notFound("No live invitation matches the token, or it is for another address (`not_found`).")
            conflict("The caller is already a member of the organization (`conflict`).")
        }
    }
}
