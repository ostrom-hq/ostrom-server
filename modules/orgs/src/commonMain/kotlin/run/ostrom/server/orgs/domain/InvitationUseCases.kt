// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.orgs.domain

import run.ostrom.server.kernel.auth.OrgRole
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/** How long an invitation works. */
val INVITATION_LIFETIME = 7.days

/** The most people one invitation link can bring in. */
const val MAX_LINK_USES = 100

/** The most addresses one request invites. */
const val MAX_INVITES_PER_REQUEST = 50

private const val INVITES_PER_HOUR = 100
private const val HOUR = 60
private const val MAX_EMAIL = 254
private val EMAIL_PATTERN = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

/**
 * Normalizes an address an admin typed.
 *
 * @param email The address.
 * @return It trimmed and in lower case.
 * @throws InvalidOrgRequestException When it is not an address.
 */
fun validInviteEmail(email: String): String {
    val clean = email.trim().lowercase()
    ensure(clean.length <= MAX_EMAIL && EMAIL_PATTERN.matches(clean)) {
        InvalidOrgRequestException("Invalid e-mail address.")
    }
    return clean
}

/**
 * One person to invite by e-mail.
 *
 * @property email The address.
 * @property role The role they get (never owner).
 */
data class InviteRequest(val email: String, val role: OrgRole)

private fun requireManager(caller: Membership) =
    ensure(caller.member.role in MANAGER_ROLES) {
        NotAllowedException("Only an owner or an admin can invite people.")
    }

private fun requireGivable(caller: Membership, role: OrgRole) =
    ensure(role != OrgRole.OWNER && canManage(caller.member.role, role)) {
        NotAllowedException(
            "Your role does not allow inviting someone as ${role.name.lowercase()}."
        )
    }

/**
 * Checks that [caller] may send [invites], without sending anything.
 *
 * @param caller The caller's membership.
 * @param invites Who, with which role.
 * @return The invites with their addresses cleaned, each address once.
 * @throws InvalidOrgRequestException For no invite, more than 50, or an invalid address.
 * @throws NotAllowedException For a caller who is not an owner or an admin, or a role beyond what they give.
 */
internal fun requireInvites(caller: Membership, invites: List<InviteRequest>): List<InviteRequest> {
    ensure(invites.size in 1..MAX_INVITES_PER_REQUEST) {
        InvalidOrgRequestException("Invite 1 to 50 people at once.")
    }
    val clean = invites.map { it.copy(email = validInviteEmail(it.email)) }.distinctBy { it.email }
    requireManager(caller)
    clean.forEach { requireGivable(caller, it.role) }
    return clean
}

/**
 * Checks that [caller] may create a link giving [role] to [maxUses] people, without creating it.
 *
 * @param caller The caller's membership.
 * @param role The role it gives.
 * @param maxUses How many people it brings in.
 * @throws InvalidOrgRequestException For fewer than 1 or more than 100 uses.
 * @throws NotAllowedException For a caller who is not an owner or an admin, or a role beyond what they give.
 */
internal fun requireLink(caller: Membership, role: OrgRole, maxUses: Int) {
    ensure(maxUses in 1..MAX_LINK_USES) {
        InvalidOrgRequestException("A link works for 1 to 100 people.")
    }
    requireManager(caller)
    requireGivable(caller, role)
}

/**
 * Invites people by e-mail: each gets a link that only works for an account holding that verified
 * address.
 */
interface InviteByEmailUseCase {
    /**
     * Invites them.
     *
     * @param orgId The organization.
     * @param userId The caller, owner or admin.
     * @param invites The people.
     * @return The addresses invited; members and addresses already invited are skipped.
     * @throws SeatLimitException When the plan has no room for them.
     */
    suspend operator fun invoke(
        orgId: Uuid,
        userId: Uuid,
        invites: List<InviteRequest>,
    ): List<String>
}

/**
 * [InviteByEmailUseCase].
 *
 * @param orgs The organizations.
 * @param members The members.
 * @param invitations The invitations.
 * @param people The people.
 * @param services The tokens, mail, seats, limiter and audit log.
 * @param clock The clock.
 */
class InviteByEmailUseCaseImpl(
    private val orgs: OrgsRepository,
    private val members: MembersRepository,
    private val invitations: InvitationsRepository,
    private val people: People,
    private val services: InvitationServices,
    private val clock: Clock,
) : InviteByEmailUseCase {
    override suspend fun invoke(
        orgId: Uuid,
        userId: Uuid,
        invites: List<InviteRequest>,
    ): List<String> {
        val caller = requireMembership(orgs, members, orgId, userId)
        val clean = requireInvites(caller, invites)
        val now = clock.nowMillis()
        val current = members.list(orgId)
        val memberEmails =
            people.getMany(current.map { it.userId }).mapNotNull { it.email?.lowercase() }.toSet()
        val pending = invitations.pendingEmails(orgId, now)
        val fresh = clean.filter { it.email !in memberEmails && it.email !in pending }
        ensure(services.seats.allows(orgId, current.size + pending.size + fresh.size)) {
            SeatLimitException()
        }
        ensure(services.limiter.tryAcquire("org-invite:$orgId", INVITES_PER_HOUR, HOUR)) {
            OrgRateLimitedException()
        }
        val inviter = checkNotNull(people.get(userId)) { "The caller has no account." }
        fresh.forEach { invite ->
            val token = services.tokens.generate()
            val invitation =
                Invitation(
                    Uuid.random(),
                    orgId,
                    invite.email,
                    invite.role,
                    caller.member.id,
                    now,
                    now + INVITATION_LIFETIME,
                    1,
                    0,
                    null,
                )
            invitations.create(invitation, services.tokens.hash(token))
            services.mailer.send(invite.email, caller.org, inviter, services.linkUrl(token))
            val details = mapOf("email" to invite.email, "role" to invite.role.name.lowercase())
            services.audit.record(
                OrgEvent(
                    orgId,
                    userId,
                    "org.invitation.created",
                    "invitation",
                    invitation.id.toString(),
                    details,
                )
            )
        }
        return fresh.map { it.email }
    }
}

/**
 * What the invitation use cases share.
 *
 * @property tokens Generates and hashes the tokens.
 * @property mailer Sends the e-mails.
 * @property seats The plan's limit of people.
 * @property limiter Limits invitations per organization.
 * @property audit The audit log.
 * @property linkUrl The console link that accepts an invitation, from its token.
 */
data class InvitationServices(
    val tokens: InvitationTokens,
    val mailer: InvitationMailer,
    val seats: SeatPolicy,
    val limiter: OrgRateLimiter,
    val audit: OrgAudit,
    val linkUrl: (String) -> String,
)

/** Creates an invitation link that anyone signed in can use, a limited number of times. */
interface CreateInviteLinkUseCase {
    /**
     * Creates it.
     *
     * @param orgId The organization.
     * @param userId The caller, owner or admin.
     * @param role The role given on joining.
     * @param maxUses How many people it can bring in (1 to 100).
     * @return The invitation and its link.
     */
    suspend operator fun invoke(
        orgId: Uuid,
        userId: Uuid,
        role: OrgRole,
        maxUses: Int,
    ): IssuedInvitation
}

/**
 * [CreateInviteLinkUseCase].
 *
 * @param orgs The organizations.
 * @param members The members.
 * @param invitations The invitations.
 * @param services The shared services.
 * @param clock The clock.
 */
class CreateInviteLinkUseCaseImpl(
    private val orgs: OrgsRepository,
    private val members: MembersRepository,
    private val invitations: InvitationsRepository,
    private val services: InvitationServices,
    private val clock: Clock,
) : CreateInviteLinkUseCase {
    override suspend fun invoke(
        orgId: Uuid,
        userId: Uuid,
        role: OrgRole,
        maxUses: Int,
    ): IssuedInvitation {
        val caller = requireMembership(orgs, members, orgId, userId)
        requireLink(caller, role, maxUses)
        ensure(services.limiter.tryAcquire("org-invite:$orgId", INVITES_PER_HOUR, HOUR)) {
            OrgRateLimitedException()
        }
        val now = clock.nowMillis()
        val token = services.tokens.generate()
        val invitation =
            Invitation(
                Uuid.random(),
                orgId,
                null,
                role,
                caller.member.id,
                now,
                now + INVITATION_LIFETIME,
                maxUses,
                0,
                null,
            )
        invitations.create(invitation, services.tokens.hash(token))
        val details = mapOf("role" to role.name.lowercase(), "max_uses" to maxUses.toString())
        services.audit.record(
            OrgEvent(
                orgId,
                userId,
                "org.invitation.created",
                "invitation",
                invitation.id.toString(),
                details,
            )
        )
        return IssuedInvitation(invitation, services.linkUrl(token))
    }
}

/** The live invitations of an organization. */
interface ListInvitationsUseCase {
    /**
     * Lists them.
     *
     * @param orgId The organization.
     * @param userId The caller, owner or admin.
     * @return The invitations, newest first.
     */
    suspend operator fun invoke(orgId: Uuid, userId: Uuid): List<Invitation>
}

/**
 * [ListInvitationsUseCase].
 *
 * @param orgs The organizations.
 * @param members The members.
 * @param invitations The invitations.
 * @param clock The clock.
 */
class ListInvitationsUseCaseImpl(
    private val orgs: OrgsRepository,
    private val members: MembersRepository,
    private val invitations: InvitationsRepository,
    private val clock: Clock,
) : ListInvitationsUseCase {
    override suspend fun invoke(orgId: Uuid, userId: Uuid): List<Invitation> {
        requireManager(requireMembership(orgs, members, orgId, userId))
        return invitations.listLive(orgId, clock.nowMillis())
    }
}

/** Revokes an invitation. */
interface RevokeInvitationUseCase {
    /**
     * Revokes it.
     *
     * @param orgId The organization.
     * @param userId The caller, owner or admin.
     * @param id The invitation.
     * @throws InvitationNotFoundException When no live invitation of the organization has that id.
     */
    suspend operator fun invoke(orgId: Uuid, userId: Uuid, id: Uuid)
}

/**
 * [RevokeInvitationUseCase].
 *
 * @param orgs The organizations.
 * @param members The members.
 * @param invitations The invitations.
 * @param audit The audit log.
 * @param clock The clock.
 */
class RevokeInvitationUseCaseImpl(
    private val orgs: OrgsRepository,
    private val members: MembersRepository,
    private val invitations: InvitationsRepository,
    private val audit: OrgAudit,
    private val clock: Clock,
) : RevokeInvitationUseCase {
    override suspend fun invoke(orgId: Uuid, userId: Uuid, id: Uuid) {
        requireManager(requireMembership(orgs, members, orgId, userId))
        ensure(invitations.revoke(orgId, id, clock.nowMillis())) { InvitationNotFoundException() }
        audit.record(OrgEvent(orgId, userId, "org.invitation.revoked", "invitation", id.toString()))
    }
}

/** What an invitation link leads to, shown before accepting. */
interface PreviewInvitationUseCase {
    /**
     * Reads it.
     *
     * @param token The link's token.
     * @return The organization and the invitation.
     * @throws InvitationNotFoundException When the link is unknown, expired, revoked or used up.
     */
    suspend operator fun invoke(token: String): InvitationPreview
}

/**
 * [PreviewInvitationUseCase].
 *
 * @param orgs The organizations.
 * @param invitations The invitations.
 * @param tokens Hashes the token.
 * @param clock The clock.
 */
class PreviewInvitationUseCaseImpl(
    private val orgs: OrgsRepository,
    private val invitations: InvitationsRepository,
    private val tokens: InvitationTokens,
    private val clock: Clock,
) : PreviewInvitationUseCase {
    override suspend fun invoke(token: String): InvitationPreview =
        liveInvitation(orgs, invitations, tokens, token, clock)
}

private const val MAX_TOKEN = 128

private suspend fun liveInvitation(
    orgs: OrgsRepository,
    invitations: InvitationsRepository,
    tokens: InvitationTokens,
    token: String,
    clock: Clock,
): InvitationPreview {
    ensure(token.length in 1..MAX_TOKEN) { InvitationNotFoundException() }
    val invitation =
        invitations.findLiveByTokenHash(tokens.hash(token), clock.nowMillis())
            ?: throw InvitationNotFoundException()
    val org = orgs.get(invitation.orgId) ?: throw InvitationNotFoundException()
    return InvitationPreview(org, invitation)
}

/** Accepts an invitation: the caller joins the organization. */
interface AcceptInvitationUseCase {
    /**
     * Accepts it.
     *
     * @param userId The caller.
     * @param token The link's token.
     * @return The organization and the new membership.
     * @throws InvitationNotFoundException When the link no longer works, or is for an address the
     *   caller has not verified (answered alike, so a link does not tell whose it is).
     * @throws AlreadyMemberException When the caller is already a member.
     * @throws SeatLimitException When the plan has no room left.
     */
    suspend operator fun invoke(userId: Uuid, token: String): Membership
}

/**
 * [AcceptInvitationUseCase].
 *
 * @param orgs The organizations.
 * @param members The members.
 * @param invitations The invitations.
 * @param people The people.
 * @param services The shared services.
 * @param clock The clock.
 */
class AcceptInvitationUseCaseImpl(
    private val orgs: OrgsRepository,
    private val members: MembersRepository,
    private val invitations: InvitationsRepository,
    private val people: People,
    private val services: InvitationServices,
    private val clock: Clock,
) : AcceptInvitationUseCase {
    override suspend fun invoke(userId: Uuid, token: String): Membership {
        val (org, invitation) = liveInvitation(orgs, invitations, services.tokens, token, clock)
        ensure(invitation.email == null || invitation.email in people.verifiedEmails(userId)) {
            InvitationNotFoundException()
        }
        ensure(members.findByUser(org.id, userId) == null) { AlreadyMemberException() }
        // The seat is taken now: pending invitations reserved nothing that other joins could not
        // use first.
        ensure(services.seats.allows(org.id, members.count(org.id) + 1)) { SeatLimitException() }
        val now = clock.nowMillis()
        val member = Member(Uuid.random(), org.id, userId, invitation.role, now)
        ensure(invitations.accept(invitation, member, now)) { InvitationNotFoundException() }
        val details =
            mapOf(
                "invitation" to invitation.id.toString(),
                "role" to invitation.role.name.lowercase(),
            )
        services.audit.record(
            OrgEvent(
                org.id,
                userId,
                "org.invitation.accepted",
                "member",
                member.id.toString(),
                details,
            )
        )
        return Membership(org, member)
    }
}
