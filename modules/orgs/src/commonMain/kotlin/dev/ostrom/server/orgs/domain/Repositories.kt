// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs.domain

import dev.ostrom.server.kernel.auth.OrgRole
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Organizations. */
interface OrgsRepository {
    /**
     * Creates an organization with its first member, the owner, in one transaction. When [org]
     * holds a free claim, the transaction first locks that claim and refuses if another live
     * organization holds it and [isPaying] says that one is free.
     *
     * @param org The organization.
     * @param owner Its owner.
     * @param isPaying Says whether the organization holding the same claim pays (then the claim is
     *   not exclusive).
     * @return The organization already holding the claim, if it blocks the creation; null when
     *   created.
     * @throws SlugTakenException When the slug is taken.
     */
    suspend fun create(org: Org, owner: Member, isPaying: suspend (Uuid) -> Boolean): Org?

    /**
     * Reads a live (not deleted) organization.
     *
     * @param id The id.
     * @return The organization, or null.
     */
    suspend fun get(id: Uuid): Org?

    /**
     * Reads a live organization by its short name.
     *
     * @param slug The short name.
     * @return The organization, or null.
     */
    suspend fun findBySlug(slug: String): Org?

    /**
     * Whether a short name is used, by a live organization or a deleted one during its days of grace
     * (kept so that it can be restored; one space for everybody, as on GitHub). An erased organization
     * gave its short name back ([OrgDeletionsRepository.erase]).
     *
     * @param slug The short name.
     * @return True when taken.
     */
    suspend fun slugTaken(slug: String): Boolean

    /**
     * Changes an organization's settings, only the columns given: two people changing two
     * different settings at once never undo each other. A deleted organization is left as it is,
     * even by a change that raced its deletion.
     *
     * @param id The organization.
     * @param name The new name, null to keep it.
     * @param timeZone The new time zone, null to keep it.
     */
    suspend fun update(id: Uuid, name: String?, timeZone: String?)

    /**
     * Deletes a live organization logically: marks it deleted, releases its free claim, removes its
     * members and invitations (nobody reaches it any more), keeps the rest until [purgeAfter], and
     * records [event] in the audit log, all in one transaction. An organization already deleted, by
     * a call that ran first or at the same time, is left as it is: nothing is written, its
     * [purgeAfter] does not move, and no second event is recorded.
     *
     * @param id The organization.
     * @param at When.
     * @param event The deletion, for the audit log.
     * @param purgeAfter When it may be erased for good: [DELETION_GRACE] after [at] unless said otherwise.
     * @return Whether this call deleted it.
     */
    suspend fun delete(id: Uuid, at: Instant, event: OrgEvent, purgeAfter: Instant = at + DELETION_GRACE): Boolean

    /**
     * The live organizations a user belongs to, with their membership, by name.
     *
     * @param userId The user.
     * @return The memberships.
     */
    suspend fun listOfUser(userId: Uuid): List<Membership>
}

/** Members, always read and written inside one organization. */
interface MembersRepository {
    /**
     * The membership of a user.
     *
     * @param orgId The organization.
     * @param userId The user.
     * @return The member, or null.
     */
    suspend fun findByUser(orgId: Uuid, userId: Uuid): Member?

    /**
     * A member by its id, in its organization only.
     *
     * @param orgId The organization.
     * @param memberId The member.
     * @return The member, or null when the organization has none with this id.
     */
    suspend fun findById(orgId: Uuid, memberId: Uuid): Member?

    /**
     * The members, oldest first.
     *
     * @param orgId The organization.
     * @return The members.
     */
    suspend fun list(orgId: Uuid): List<Member>

    /**
     * A page of the members, oldest first.
     *
     * @param orgId The organization.
     * @param after The member the page starts after; an unknown one gives an empty page.
     * @param limit The most members.
     * @return The members.
     */
    suspend fun page(orgId: Uuid, after: Uuid?, limit: Int): List<Member>

    /**
     * How many members the organization has.
     *
     * @param orgId The organization.
     * @return The count.
     */
    suspend fun count(orgId: Uuid): Int

    /**
     * Changes a member's role.
     *
     * @param orgId The organization.
     * @param memberId The member.
     * @param role The new role (never owner: see [transferOwnership]).
     */
    suspend fun changeRole(orgId: Uuid, memberId: Uuid, role: OrgRole)

    /**
     * Removes a member.
     *
     * @param orgId The organization.
     * @param memberId The member.
     */
    suspend fun remove(orgId: Uuid, memberId: Uuid)

    /**
     * Makes [newOwnerId] the owner and [oldOwnerId] an admin, in one transaction.
     *
     * @param orgId The organization.
     * @param oldOwnerId The current owner.
     * @param newOwnerId The member who becomes owner.
     */
    suspend fun transferOwnership(orgId: Uuid, oldOwnerId: Uuid, newOwnerId: Uuid)
}

/** Invitations. */
interface InvitationsRepository {
    /**
     * Stores an invitation with the hash of its link's secret.
     *
     * @param invitation The invitation.
     * @param tokenHash The hash of its token.
     */
    suspend fun create(invitation: Invitation, tokenHash: String)

    /**
     * The live invitations of an organization (not revoked, not expired, not used up), newest
     * first.
     *
     * @param orgId The organization.
     * @param now The time.
     * @return The invitations.
     */
    suspend fun listLive(orgId: Uuid, now: Instant): List<Invitation>

    /**
     * The live e-mail invitations of an organization, as addresses.
     *
     * @param orgId The organization.
     * @param now The time.
     * @return The invited addresses.
     */
    suspend fun pendingEmails(orgId: Uuid, now: Instant): Set<String>

    /**
     * Finds a live invitation by its token's hash, across organizations (the token is the only way
     * in).
     *
     * @param tokenHash The hash.
     * @param now The time.
     * @return The invitation, or null.
     */
    suspend fun findLiveByTokenHash(tokenHash: String, now: Instant): Invitation?

    /**
     * Revokes an invitation.
     *
     * @param orgId The organization.
     * @param id The invitation.
     * @param at When.
     * @return True when a live invitation was revoked.
     */
    suspend fun revoke(orgId: Uuid, id: Uuid, at: Instant): Boolean

    /**
     * Uses an invitation to add a member, in one transaction: counts the use only while the
     * invitation is still live, then inserts the member.
     *
     * @param invitation The invitation.
     * @param member The new member.
     * @param now The time.
     * @return True when the invitation was still usable and the member was added.
     * @throws AlreadyMemberException When the user is already a member.
     */
    suspend fun accept(invitation: Invitation, member: Member, now: Instant): Boolean
}
