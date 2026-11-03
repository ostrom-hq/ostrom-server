// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs.presentation

import dev.ostrom.server.kernel.http.NullsKept
import dev.ostrom.server.orgs.domain.DEFAULT_TIME_ZONE
import io.ktor.resources.Resource
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable

/** `/api/v1/orgs`: the caller's organizations, and creating one. */
@Serializable
@Resource("/api/v1/orgs")
class OrgsApi {
    /**
     * One organization, named by its id or its short name.
     *
     * @property parent The parent resource.
     * @property org The id or the short name.
     */
    @Serializable
    @Resource("{org}")
    class Org(val parent: OrgsApi = OrgsApi(), val org: String) {
        /**
         * Gives the ownership to another member.
         *
         * @property parent The organization.
         */
        @Serializable @Resource("transfer") class Transfer(val parent: Org)

        /**
         * The caller leaves the organization.
         *
         * @property parent The organization.
         */
        @Serializable @Resource("leave") class Leave(val parent: Org)

        /**
         * The members.
         *
         * @property parent The organization.
         * @property limit The most members of the page, 1 to 1000; 1000 without it.
         * @property after The previous page's `next`.
         */
        @Serializable
        @Resource("members")
        class Members(val parent: Org, val limit: Int? = null, val after: String? = null) {
            /**
             * One member, by login.
             *
             * @property parent The members.
             * @property login The login.
             */
            @Serializable @Resource("{login}") class Login(val parent: Members, val login: String)

            /**
             * Invites people by e-mail.
             *
             * @property parent The members.
             */
            @Serializable @Resource("invites") class Invites(val parent: Members)
        }

        /**
         * The live invitations.
         *
         * @property parent The organization.
         */
        @Serializable
        @Resource("invitations")
        class Invitations(val parent: Org) {
            /**
             * Creates an invitation link.
             *
             * @property parent The invitations.
             */
            @Serializable @Resource("links") class Links(val parent: Invitations)

            /**
             * One invitation.
             *
             * @property parent The invitations.
             * @property id The invitation id.
             */
            @Serializable @Resource("{id}") class Id(val parent: Invitations, val id: String)
        }
    }
}

/**
 * `GET /api/v1/me/organizations`: the caller's organizations, so that a person in several can choose the one the
 * `Ostrom-Org` header names. Never needs that header, never answers 409 `org_required`.
 */
@Serializable
@Resource("/api/v1/me/organizations")
class MyOrganizationsApi

/** `/api/v1/invitations`: what a person who received a link does with it. */
@Serializable
@Resource("/api/v1/invitations")
class InvitationLinksApi {
    /**
     * Shows what the link leads to (no sign-in needed: the console shows it before).
     *
     * @property parent The parent resource.
     */
    @Serializable
    @Resource("preview")
    class Preview(val parent: InvitationLinksApi = InvitationLinksApi())

    /**
     * Joins the organization.
     *
     * @property parent The parent resource.
     */
    @Serializable
    @Resource("accept")
    class Accept(val parent: InvitationLinksApi = InvitationLinksApi())
}

/**
 * Creates an organization.
 *
 * @property name The name.
 * @property slug The short name, or null to derive it from the name.
 */
@Serializable data class CreateOrgRequest(val name: String, val slug: String? = null)

/**
 * Changes an organization's settings; a field left out stays as it is.
 *
 * @property name The new name.
 * @property timeZone The new IANA time zone, such as `Europe/Paris`.
 * @property slug The short name: accepted only unchanged, it never changes.
 */
@Serializable
data class UpdateOrgRequest(
    val name: String? = null,
    val timeZone: String? = null,
    val slug: String? = null,
)

/**
 * Confirms a deletion.
 *
 * @property confirm The organization's short name, typed again.
 */
@Serializable data class DeleteOrgRequest(val confirm: String)

/**
 * Names the new owner.
 *
 * @property login The member's login.
 */
@Serializable data class TransferRequest(val login: String)

/**
 * Changes a member.
 *
 * @property role The new role: `admin`, `member`, `approver` or `billing`.
 */
@Serializable data class UpdateMemberRequest(val role: String)

/**
 * Invites people by e-mail (the contract's `inviteMembers`).
 *
 * @property invites Each with an `email` and an optional `role` (`member` by default).
 */
@Serializable data class InviteRequestBody(val invites: List<Map<String, String>>)

/**
 * Who was invited.
 *
 * @property invited The addresses invited; members and addresses already invited are left out.
 */
@Serializable data class InvitedResponse(val invited: List<String>)

/**
 * Creates an invitation link.
 *
 * @property role The role it gives, `member` by default.
 * @property maxUses How many people it can bring in, 1 to 100.
 */
@Serializable
data class CreateLinkRequest(val role: String = "member", val maxUses: Int = DEFAULT_LINK_USES)

/** How many people a link brings in when the request does not say. */
const val DEFAULT_LINK_USES = 10

/**
 * An invitation link's token, sent in the body (never in a URL that logs would keep).
 *
 * @property token The token.
 */
@Serializable data class InvitationTokenRequest(val token: String)

/**
 * An organization as its member sees it.
 *
 * @property id The id.
 * @property slug The short name.
 * @property name The name.
 * @property role The caller's role.
 * @property createdAt When it was created.
 * @property timeZone Its IANA time zone, `UTC` until set (optional in the contract's sense: a
 *   default, so a client built before it still reads the others).
 */
@Serializable
data class OrgResponse(
    val id: String,
    val slug: String,
    val name: String,
    val role: String,
    val createdAt: String,
    val timeZone: String = DEFAULT_TIME_ZONE,
)

/**
 * A list of organizations.
 *
 * @property items The organizations.
 */
@Serializable data class OrgsResponse(val items: List<OrgResponse>)

/**
 * A member (the contract's `Member`).
 *
 * @property id The member's id, stable across their tokens and sessions (`OrgPrincipal.memberId`): the id that
 *   artifacts share with and that receipts name.
 * @property login The login.
 * @property name The name.
 * @property avatarUrl The picture, empty when none.
 * @property email The e-mail, if any.
 * @property role The role.
 * @property joinedAt When they joined.
 */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = MemberResponse.Answer::class)
data class MemberResponse(
    val id: String,
    val login: String,
    val name: String,
    val avatarUrl: String,
    val email: String?,
    val role: String,
    val joinedAt: String,
) {
    /** Writes the fields the contract declares required and nullable as `null` when null. */
    object Answer : NullsKept<MemberResponse>(generatedSerializer(), "email")
}

/**
 * A list of members.
 *
 * @property items The members.
 * @property next The `after` of the next page, absent when no member follows.
 */
@Serializable data class MembersResponse(val items: List<MemberResponse>, val next: String? = null)

/**
 * A live invitation.
 *
 * @property id The id.
 * @property email The address for an e-mail invitation, null for a link.
 * @property role The role it gives.
 * @property createdAt When it was made.
 * @property expiresAt When it stops working.
 * @property maxUses How many people it can bring in.
 * @property uses How many it brought in.
 */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = InvitationResponse.Answer::class)
data class InvitationResponse(
    val id: String,
    val email: String?,
    val role: String,
    val createdAt: String,
    val expiresAt: String,
    val maxUses: Int?,
    val uses: Int,
) {
    /** Writes the fields the contract declares required and nullable as `null` when null. */
    object Answer : NullsKept<InvitationResponse>(generatedSerializer(), "email", "maxUses")
}

/**
 * A list of invitations.
 *
 * @property items The invitations.
 */
@Serializable data class InvitationsResponse(val items: List<InvitationResponse>)

/**
 * A new invitation link: the only time its URL is shown.
 *
 * @property invitation The invitation.
 * @property url The link to share.
 */
@Serializable
data class InvitationLinkResponse(val invitation: InvitationResponse, val url: String)

/**
 * What an invitation leads to.
 *
 * @property orgName The organization's name.
 * @property orgSlug Its short name.
 * @property role The role it gives.
 * @property email The address it is for, null for a link.
 * @property expiresAt When it stops working.
 */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = InvitationPreviewResponse.Answer::class)
data class InvitationPreviewResponse(
    val orgName: String,
    val orgSlug: String,
    val role: String,
    val email: String?,
    val expiresAt: String,
) {
    /** Writes the fields the contract declares required and nullable as `null` when null. */
    object Answer : NullsKept<InvitationPreviewResponse>(generatedSerializer(), "email")
}

/**
 * One of the caller's organizations, to choose from.
 *
 * @property id The id, the value of the `Ostrom-Org` header.
 * @property slug The short name.
 * @property name The name.
 * @property avatarUrl The picture of the linked GitHub organization, empty when none.
 * @property role The caller's role there.
 */
@Serializable
data class MyOrganizationResponse(
    val id: String,
    val slug: String,
    val name: String,
    val avatarUrl: String,
    val role: String,
)

/**
 * The caller's organizations.
 *
 * @property items The organizations, by name.
 */
@Serializable data class MyOrganizationsResponse(val items: List<MyOrganizationResponse>)
