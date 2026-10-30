// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs.domain

import dev.ostrom.server.kernel.auth.OrgRole
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * An organization: the tenant every org-scoped row belongs to.
 *
 * @property id The id, the `org_id` of every tenant row.
 * @property slug The unique short name (`acme`), lower case letters, digits and dashes.
 * @property name The name shown in the console.
 * @property githubOrgId The GitHub organization it is linked to, if any.
 * @property freeClaim What its free plan is held against (`github:<id>`, `domain:<domain>`,
 *   `user:<id>`), so that one GitHub organization, one company domain or one person has a single
 *   free team; null once it pays (BILLING releases it) or is deleted.
 * @property createdAt When it was created.
 * @property timeZone Its IANA time zone (`Europe/Paris`), [DEFAULT_TIME_ZONE] until an owner or admin sets it.
 */
data class Org(
    val id: Uuid,
    val slug: String,
    val name: String,
    val githubOrgId: Long?,
    val freeClaim: String?,
    val createdAt: Instant,
    val timeZone: String = DEFAULT_TIME_ZONE,
)

/**
 * What an owner or admin changes in an organization's settings; a null field stays as it is.
 *
 * @property name The new name.
 * @property timeZone The new IANA time zone.
 * @property slug The short name, accepted only unchanged: it never changes.
 */
data class OrgChange(val name: String? = null, val timeZone: String? = null, val slug: String? = null)

/**
 * What one pass over the deleted organizations did.
 *
 * @property settled The deletions every module was told of.
 * @property purged The organizations erased for good.
 * @property failed The organizations a module failed on, tried again at the next pass.
 */
data class DeletionPass(val settled: Int, val purged: Int, val failed: Int)

/**
 * A person's membership of an organization.
 *
 * @property id The member id, stable across the person's tokens and sessions
 *   (`OrgPrincipal.memberId`).
 * @property orgId The organization.
 * @property userId The user.
 * @property role The role.
 * @property joinedAt When they joined.
 */
data class Member(
    val id: Uuid,
    val orgId: Uuid,
    val userId: Uuid,
    val role: OrgRole,
    val joinedAt: Instant,
)

/**
 * A member with what the console shows of the person.
 *
 * @property member The membership.
 * @property login The user's login, the member's name in the API.
 * @property displayName The user's name.
 * @property avatarUrl The user's picture, if any.
 * @property email The user's e-mail, if any.
 */
data class MemberProfile(
    val member: Member,
    val login: String,
    val displayName: String,
    val avatarUrl: String?,
    val email: String?,
)

/**
 * An organization a user belongs to, with their role in it.
 *
 * @property org The organization.
 * @property member The user's membership.
 */
data class Membership(val org: Org, val member: Member)

/**
 * An invitation to join an organization: to one e-mail address, or a link anyone signed in can use.
 *
 * @property id The id.
 * @property orgId The organization.
 * @property email For an e-mail invitation, the address (only a user who verified it can accept);
 *   null for a link.
 * @property role The role given on joining (never owner).
 * @property invitedBy The member who invited.
 * @property createdAt When it was made.
 * @property expiresAt When it stops working.
 * @property maxUses How many people a link can bring in; null for no limit; 1 for an e-mail
 *   invitation.
 * @property uses How many people it brought in.
 * @property revokedAt When an admin revoked it, if so.
 */
data class Invitation(
    val id: Uuid,
    val orgId: Uuid,
    val email: String?,
    val role: OrgRole,
    val invitedBy: Uuid,
    val createdAt: Instant,
    val expiresAt: Instant,
    val maxUses: Int?,
    val uses: Int,
    val revokedAt: Instant?,
)

/**
 * An invitation link just made: the only time its URL (which carries the secret) is shown.
 *
 * @property invitation The invitation.
 * @property url The link to share.
 */
data class IssuedInvitation(val invitation: Invitation, val url: String)

/**
 * What a person sees before accepting an invitation.
 *
 * @property org The organization.
 * @property invitation The invitation.
 */
data class InvitationPreview(val org: Org, val invitation: Invitation)
