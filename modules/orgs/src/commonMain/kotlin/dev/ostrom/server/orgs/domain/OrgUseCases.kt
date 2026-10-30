// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs.domain

import dev.ostrom.server.kernel.auth.OrgRole
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The roles that manage an organization's people. */
val MANAGER_ROLES = setOf(OrgRole.OWNER, OrgRole.ADMIN)

private const val CREATIONS_PER_HOUR = 10
private const val HOUR_MINUTES = 60

/**
 * The membership of the caller, or a 404 that does not tell a missing organization from one they
 * are not in.
 *
 * @param orgs The organizations.
 * @param members The members.
 * @param orgId The organization.
 * @param userId The caller.
 * @return The organization and the caller's membership.
 * @throws OrgNotFoundException When the organization does not exist or the caller is not a member.
 */
internal suspend fun requireMembership(
    orgs: OrgsRepository,
    members: MembersRepository,
    orgId: Uuid,
    userId: Uuid,
): Membership {
    val org = orgs.get(orgId) ?: throw OrgNotFoundException()
    val member = members.findByUser(orgId, userId) ?: throw OrgNotFoundException()
    return Membership(org, member)
}

/**
 * The current time to the millisecond, the precision PostgreSQL keeps (microseconds) never rounds
 * it.
 *
 * @return The time.
 */
internal fun Clock.nowMillis(): Instant = Instant.fromEpochMilliseconds(now().toEpochMilliseconds())

/** Creates an organization, its creator as owner. */
interface CreateOrgUseCase {
    /**
     * Creates it.
     *
     * @param userId The creator.
     * @param name The name.
     * @param slug The short name the creator chose, or null to derive it from the name.
     * @return The organization and the owner's membership.
     * @throws FreeTeamTakenException When the creator's domain (or the creator) already has a free
     *   team.
     * @throws SlugTakenException When the chosen short name is taken.
     */
    suspend operator fun invoke(userId: Uuid, name: String, slug: String?): Membership
}

/**
 * [CreateOrgUseCase].
 *
 * @param orgs The organizations.
 * @param people The people.
 * @param seats The plan lookup.
 * @param limiter Limits creations per person.
 * @param audit The audit log.
 * @param clock The clock.
 */
class CreateOrgUseCaseImpl(
    private val orgs: OrgsRepository,
    private val people: People,
    private val seats: SeatPolicy,
    private val limiter: OrgRateLimiter,
    private val audit: OrgAudit,
    private val clock: Clock,
) : CreateOrgUseCase {
    override suspend fun invoke(userId: Uuid, name: String, slug: String?): Membership {
        val cleanName = validOrgName(name)
        val chosen = slug?.let(::validSlug)
        ensure(limiter.tryAcquire("org-create:$userId", CREATIONS_PER_HOUR, HOUR_MINUTES)) {
            OrgRateLimitedException()
        }
        ensure(chosen == null || !orgs.slugTaken(chosen)) { SlugTakenException() }
        val now = clock.nowMillis()
        val org =
            Org(
                id = Uuid.random(),
                slug = chosen ?: freeSlug(slugOf(cleanName)) { orgs.slugTaken(it) },
                name = cleanName,
                githubOrgId = null,
                freeClaim = freeClaimOf(userId, people.verifiedEmails(userId)),
                createdAt = now,
            )
        val owner = Member(Uuid.random(), org.id, userId, OrgRole.OWNER, now)
        val holder = orgs.create(org, owner) { seats.isPaying(it) }
        ensure(holder == null) {
            FreeTeamTakenException(describeClaim(checkNotNull(org.freeClaim)))
        }
        audit.record(
            OrgEvent(
                org.id,
                userId,
                "org.created",
                "org",
                org.id.toString(),
                mapOf("slug" to org.slug),
            )
        )
        return Membership(org, owner)
    }
}

/** The organizations of the caller. */
interface ListMyOrgsUseCase {
    /**
     * Lists them.
     *
     * @param userId The caller.
     * @return Their memberships, by organization name.
     */
    suspend operator fun invoke(userId: Uuid): List<Membership>
}

/**
 * [ListMyOrgsUseCase].
 *
 * @param orgs The organizations.
 */
class ListMyOrgsUseCaseImpl(private val orgs: OrgsRepository) : ListMyOrgsUseCase {
    override suspend fun invoke(userId: Uuid): List<Membership> = orgs.listOfUser(userId)
}

/** Turns what a request names (an id or a short name) into an organization id. */
interface FindOrgUseCase {
    /**
     * Finds it.
     *
     * @param ref The id or the short name.
     * @return The id.
     * @throws OrgNotFoundException When no live organization matches.
     */
    suspend operator fun invoke(ref: String): Uuid
}

/**
 * [FindOrgUseCase].
 *
 * @param orgs The organizations.
 */
class FindOrgUseCaseImpl(private val orgs: OrgsRepository) : FindOrgUseCase {
    override suspend fun invoke(ref: String): Uuid {
        val id = Uuid.parseOrNull(ref)
        val org = if (id != null) orgs.get(id) else orgs.findBySlug(ref.lowercase())
        return org?.id ?: throw OrgNotFoundException()
    }
}

/** An organization, as a member sees it. */
interface GetOrgUseCase {
    /**
     * Reads it.
     *
     * @param orgId The organization.
     * @param userId The caller.
     * @return The organization and the caller's membership.
     * @throws OrgNotFoundException When the caller is not a member.
     */
    suspend operator fun invoke(orgId: Uuid, userId: Uuid): Membership
}

/**
 * [GetOrgUseCase].
 *
 * @param orgs The organizations.
 * @param members The members.
 */
class GetOrgUseCaseImpl(private val orgs: OrgsRepository, private val members: MembersRepository) :
    GetOrgUseCase {
    override suspend fun invoke(orgId: Uuid, userId: Uuid): Membership =
        requireMembership(orgs, members, orgId, userId)
}

/** Renames an organization (owner or admin). */
interface RenameOrgUseCase {
    /**
     * Renames it.
     *
     * @param orgId The organization.
     * @param userId The caller.
     * @param name The new name.
     * @return The organization and the caller's membership.
     * @throws NotAllowedException When the caller is neither owner nor admin.
     */
    suspend operator fun invoke(orgId: Uuid, userId: Uuid, name: String): Membership
}

/**
 * [RenameOrgUseCase].
 *
 * @param orgs The organizations.
 * @param members The members.
 * @param audit The audit log.
 */
class RenameOrgUseCaseImpl(
    private val orgs: OrgsRepository,
    private val members: MembersRepository,
    private val audit: OrgAudit,
) : RenameOrgUseCase {
    override suspend fun invoke(orgId: Uuid, userId: Uuid, name: String): Membership {
        val cleanName = validOrgName(name)
        val caller = requireMembership(orgs, members, orgId, userId)
        ensure(caller.member.role in MANAGER_ROLES) {
            NotAllowedException("Only an owner or an admin can rename it.")
        }
        orgs.rename(orgId, cleanName)
        val details = mapOf("from" to caller.org.name, "to" to cleanName)
        audit.record(OrgEvent(orgId, userId, "org.renamed", "org", orgId.toString(), details))
        return caller.copy(org = caller.org.copy(name = cleanName))
    }
}

/** Deletes an organization (owner only, who confirms with its short name). */
interface DeleteOrgUseCase {
    /**
     * Deletes it.
     *
     * @param orgId The organization.
     * @param userId The caller.
     * @param confirmSlug The short name, typed again by the owner.
     * @throws NotAllowedException When the caller is not the owner.
     * @throws InvalidOrgRequestException When the confirmation does not match.
     */
    suspend operator fun invoke(orgId: Uuid, userId: Uuid, confirmSlug: String)
}

/**
 * [DeleteOrgUseCase].
 *
 * @param orgs The organizations.
 * @param members The members.
 * @param audit The audit log.
 * @param clock The clock.
 */
class DeleteOrgUseCaseImpl(
    private val orgs: OrgsRepository,
    private val members: MembersRepository,
    private val audit: OrgAudit,
    private val clock: Clock,
) : DeleteOrgUseCase {
    override suspend fun invoke(orgId: Uuid, userId: Uuid, confirmSlug: String) {
        val caller = requireMembership(orgs, members, orgId, userId)
        ensure(caller.member.role == OrgRole.OWNER) {
            NotAllowedException("Only the owner can delete the organization.")
        }
        ensure(confirmSlug == caller.org.slug) {
            InvalidOrgRequestException("Type the organization's short name to confirm.")
        }
        // Recorded first: the deletion removes the members, and the event must exist even if the
        // deletion fails.
        audit.record(
            OrgEvent(
                orgId,
                userId,
                "org.deleted",
                "org",
                orgId.toString(),
                mapOf("slug" to caller.org.slug),
            )
        )
        orgs.delete(orgId, clock.nowMillis())
    }
}

/** Gives the ownership to another member (owner only); the former owner stays as an admin. */
interface TransferOwnershipUseCase {
    /**
     * Transfers it.
     *
     * @param orgId The organization.
     * @param userId The caller, the owner.
     * @param toLogin The member who becomes owner.
     * @throws NotAllowedException When the caller is not the owner.
     * @throws MemberNotFoundException When no other member has that login.
     */
    suspend operator fun invoke(orgId: Uuid, userId: Uuid, toLogin: String)
}

/**
 * [TransferOwnershipUseCase].
 *
 * @param orgs The organizations.
 * @param members The members.
 * @param people The people.
 * @param audit The audit log.
 */
class TransferOwnershipUseCaseImpl(
    private val orgs: OrgsRepository,
    private val members: MembersRepository,
    private val people: People,
    private val audit: OrgAudit,
) : TransferOwnershipUseCase {
    override suspend fun invoke(orgId: Uuid, userId: Uuid, toLogin: String) {
        val caller = requireMembership(orgs, members, orgId, userId)
        ensure(caller.member.role == OrgRole.OWNER) {
            NotAllowedException("Only the owner can transfer the ownership.")
        }
        val target = findMember(members, people, orgId, toLogin)
        ensure(target.member.id != caller.member.id) {
            InvalidOrgRequestException("You already own it.")
        }
        members.transferOwnership(orgId, caller.member.id, target.member.id)
        val details = mapOf("to" to target.login)
        audit.record(
            OrgEvent(
                orgId,
                userId,
                "org.ownership.transferred",
                "member",
                target.member.id.toString(),
                details,
            )
        )
    }
}
