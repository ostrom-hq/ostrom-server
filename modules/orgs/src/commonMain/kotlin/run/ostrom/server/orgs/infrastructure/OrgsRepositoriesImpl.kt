// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.orgs.infrastructure

import run.ostrom.server.identity.infrastructure.TransactionalAuditLog
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.lockUntilCommit
import run.ostrom.server.orgs.domain.AlreadyMemberException
import run.ostrom.server.orgs.domain.Invitation
import run.ostrom.server.orgs.domain.InvitationsRepository
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.MembersRepository
import run.ostrom.server.orgs.domain.Membership
import run.ostrom.server.orgs.domain.Org
import run.ostrom.server.orgs.domain.OrgEvent
import run.ostrom.server.orgs.domain.OrgsRepository
import run.ostrom.server.orgs.domain.SlugTakenException
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.GreaterOp
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.appendTo
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.core.wrapAsExpression
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid

// A live invitation: neither revoked nor expired nor used up.
private fun InvitationRows.live(now: Instant): Op<Boolean> =
    revokedAt.isNull() and (expiresAt greater now) and (maxUses.isNull() or (uses less maxUses))

// The signatures of one claim's holders, or of one deletion's audit line, on a state another write moved meanwhile,
// before the call fails loudly.
private const val CLAIM_ATTEMPTS = 8
private const val AUDIT_ATTEMPTS = 8

/** PostgreSQL's SQLSTATE of a unique violation. */
private const val UNIQUE_VIOLATION = "23505"

// A unique violation of [block]'s statements becomes [conflict]; any other failure goes on. Not inline: one copy.
private suspend fun <T> uniqueAs(conflict: () -> Exception, block: suspend () -> T): T = try {
    block()
} catch (error: ExposedSQLException) {
    throw if (error.sqlState == UNIQUE_VIOLATION) conflict() else error
}

/** The holders of a claim the creation of an organization settled on: [blocker] is the one that blocks it, if any. */
private class ClaimSettled(val blocker: Org?)

/**
 * [OrgsRepository] on PostgreSQL through Exposed, every statement of an organization in its tenant
 * ([JdbcDatabase.inOrg], row-level security); the cross-organization reads go through the functions of
 * `ostrom_router` (OrgsFunctions.kt).
 *
 * @param database The serving role's pool.
 * @param audit The identity module's audit log, which records a deletion in its transaction.
 */
class OrgsRepositoryImpl(
    private val database: JdbcDatabase,
    private val audit: TransactionalAuditLog,
) : OrgsRepository {
    /**
     * Creates the organization and its owner, unless a free-plan claim is blocked by a holder that is not paying.
     *
     * A transaction block is not `suspend` and [isPaying] is, so the claim is settled in two transactions around it,
     * as an audit line is signed between two ([delete]): the first locks the claim and reads its holders, [isPaying]
     * runs outside any transaction for each of them, and the second takes the claim's lock again and creates the
     * organization only if the holders are still the ones [isPaying] was asked about. Another creation that
     * committed in between changed them: the question is asked again, at most [CLAIM_ATTEMPTS] times.
     */
    override suspend fun create(
        org: Org,
        owner: Member,
        isPaying: suspend (Uuid) -> Boolean,
    ): Org? {
        val claim = org.freeClaim ?: return uniqueAs(::SlugTakenException) {
            database.query {
                createOrganization(org, owner, owner.role.key())
                null
            }
        }
        repeat(CLAIM_ATTEMPTS) {
            val seen = database.query {
                lockUntilCommit(claim)
                holdersOf(claim)
            }
            val blocker = seen.firstOrNull { !isPaying(it.id) }
            val settled = uniqueAs(::SlugTakenException) {
                database.query {
                    // Serializes the creations of one claim until the transaction ends, so two at once cannot both
                    // take it.
                    lockUntilCommit(claim)
                    if (holdersOf(claim).map { it.id }.toSet() != seen.map { it.id }.toSet()) {
                        null
                    } else {
                        // Before the organization exists no query can be confined to it: a cross-organization
                        // function writes it and its owner (docs/tenancy.md).
                        if (blocker == null) createOrganization(org, owner, owner.role.key())
                        ClaimSettled(blocker)
                    }
                }
            }
            if (settled != null) return settled.blocker
        }
        error("The holders of the claim kept changing while their plans were read.")
    }

    private fun JdbcTransaction.holdersOf(claim: String): List<Org> =
        OrgsClaimHolders.of(claim).select(OrgsClaimHolders.read).map { it.toOrg(OrgsClaimHolders) }

    override suspend fun get(id: Uuid): Org? = database.inOrg(id) {
        val o = OrgOrganizations
        o.select(o.read).where { (o.id eq id) and o.deletedAt.isNull() }.firstOrNull()?.toOrg(o)
    }

    override suspend fun findBySlug(slug: String): Org? = database.query {
        OrgsBySlug.of(slug).select(OrgsBySlug.read).firstOrNull()?.toOrg(OrgsBySlug)
    }

    override suspend fun slugTaken(slug: String): Boolean = database.query { slugIsTaken(slug) }

    override suspend fun update(id: Uuid, name: String?, timeZone: String?, hooksDeadlineSeconds: Int?) {
        val o = OrgOrganizations
        val changes = listOfNotNull<(UpdateStatement) -> Unit>(
            name?.let { value -> { it[o.name] = value } },
            timeZone?.let { value -> { it[o.timeZone] = value } },
            hooksDeadlineSeconds?.let { value -> { it[o.hooksDeadlineSeconds] = value } },
        )
        if (changes.isEmpty()) return
        // A live organization only: a change that races its deletion never rewrites the deleted or erased row.
        database.inOrg(id) {
            o.update({ (o.id eq id) and o.deletedAt.isNull() }) { statement ->
                changes.forEach { change -> change(statement) }
            }
        }
    }

    /**
     * Deletes the organization and its members and invitations, and appends the deletion's audit line to the chain in
     * the same transaction. A transaction block is not `suspend` and signing is, so the line is signed outside, on
     * the chain's head read in a first transaction, and appended in the second under the chain's lock, only if the
     * head is still the one it was signed on. Another writer's append in between rolls the second back (nothing is
     * deleted) and the line is signed again on the new head, at most [AUDIT_ATTEMPTS] times. The tenant role does
     * both: its policy on the audit table lets it read and add the organization's own lines.
     */
    override suspend fun delete(id: Uuid, at: Instant, event: OrgEvent, purgeAfter: Instant): Boolean {
        repeat(AUDIT_ATTEMPTS) {
            val head = database.inOrg(id) { audit.head(this, id) }
            val events = audit.sign(head, listOf(event.toAuditEntry()))
            var chained = true
            val deleted = database.inOrg(id) {
                val o = OrgOrganizations
                // Only a live organization: a second deletion, run at the same time, waits for the first
                // one's row, then finds it deleted and writes nothing.
                val removed = o.update({ (o.id eq id) and o.deletedAt.isNull() }) {
                    it[deletedAt] = at
                    it[o.purgeAfter] = purgeAfter
                    it[freeClaim] = null
                } == 1
                if (removed) {
                    OrgInvitations.deleteWhere { orgId eq id }
                    OrgMembers.deleteWhere { orgId eq id }
                    if (!audit.append(this, id, head, events)) {
                        chained = false
                        rollback()
                    }
                }
                removed
            }
            if (chained) return deleted
        }
        error("The organization's audit chain kept moving while the deletion was signed.")
    }

    override suspend fun listOfUser(userId: Uuid): List<Membership> = database.query {
        val m = OrgsMembershipsOf
        m.of(userId).select(m.read + listOf(m.memberId, m.orgId, m.userId, m.role, m.joinedAt))
            .orderBy(m.name to SortOrder.ASC, m.slug to SortOrder.ASC)
            .map { Membership(it.toOrg(m), it.toMember(m.member)) }
    }
}

/**
 * [MembersRepository] on PostgreSQL through Exposed: every query runs confined to its organization (row-level
 * security).
 *
 * @param database The serving role's pool.
 */
class MembersRepositoryImpl(private val database: JdbcDatabase) : MembersRepository {
    override suspend fun findByUser(orgId: Uuid, userId: Uuid): Member? = database.inOrg(orgId) {
        val m = OrgMembers
        m.selectAll().where { (m.orgId eq orgId) and (m.userId eq userId) }.firstOrNull()?.toMember(m.member)
    }

    override suspend fun findById(orgId: Uuid, memberId: Uuid): Member? = database.inOrg(orgId) {
        val m = OrgMembers
        m.selectAll().where { (m.orgId eq orgId) and (m.id eq memberId) }.firstOrNull()?.toMember(m.member)
    }

    override suspend fun list(orgId: Uuid): List<Member> = database.inOrg(orgId) {
        val m = OrgMembers
        m.selectAll().where { m.orgId eq orgId }
            .orderBy(m.joinedAt to SortOrder.ASC, m.id to SortOrder.ASC)
            .map { it.toMember(m.member) }
    }

    // The page starts after a member by their place, (joined_at, id): a row comparison with no row gives no member.
    override suspend fun page(orgId: Uuid, after: Uuid?, limit: Int): List<Member> = database.inOrg(orgId) {
        val m = OrgMembers
        val start = after?.let { cursor ->
            rowAfter(
                listOf(m.joinedAt, m.id),
                m.select(m.joinedAt, m.id).where { (m.orgId eq orgId) and (m.id eq cursor) },
            )
        }
        m.selectAll().where { listOfNotNull(m.orgId eq orgId, start).reduce { all, next -> all and next } }
            .orderBy(m.joinedAt to SortOrder.ASC, m.id to SortOrder.ASC)
            .limit(limit)
            .map { it.toMember(m.member) }
    }

    // The function returns the page's member ids in order (OrgsMigrations version 7); `after` goes as text, "" for
    // none. Each id is read by its primary key (LATERAL, kept from being flattened by its LIMIT): a join plans the
    // function's 1 000 estimated rows against the whole team, 5 000 members read for a page (CI run 41690554164).
    override suspend fun search(orgId: Uuid, prefix: String, after: Uuid?, limit: Int): List<Member> =
        database.inOrg(orgId) {
            val s = MemberSearch(orgId, prefix, after, limit)
            val x = OrgMembers.alias("x")
            val m = x.select(x[OrgMembers.id], x[OrgMembers.orgId], x[OrgMembers.userId], x[OrgMembers.role],
                x[OrgMembers.joinedAt])
                .where { (x[OrgMembers.id] eq s.memberId) and (x[OrgMembers.orgId] eq orgId) }
                .limit(1)
                .alias("m")
            val columns = MemberColumns(
                m[x[OrgMembers.id]], m[x[OrgMembers.orgId]], m[x[OrgMembers.userId]], m[x[OrgMembers.role]],
                m[x[OrgMembers.joinedAt]],
            )
            s.join(m, JoinType.INNER, lateral = true, additionalConstraint = { Op.TRUE })
                .select(columns.all)
                .orderBy(s.n)
                .map { it.toMember(columns) }
        }

    override suspend fun count(orgId: Uuid): Int = database.inOrg(orgId) {
        val m = OrgMembers
        val members = m.id.count()
        m.select(members).where { m.orgId eq orgId }.single()[members].toInt()
    }

    override suspend fun changeRole(orgId: Uuid, memberId: Uuid, role: OrgRole) {
        database.inOrg(orgId) { setRole(orgId, memberId, role) }
    }

    override suspend fun remove(orgId: Uuid, memberId: Uuid) {
        database.inOrg(orgId) { OrgMembers.deleteWhere { (this.orgId eq orgId) and (id eq memberId) } }
    }

    override suspend fun transferOwnership(orgId: Uuid, oldOwnerId: Uuid, newOwnerId: Uuid) {
        database.inOrg(orgId) {
            // The former owner steps down first: an organization has one owner at a time
            // (orgs_members_one_owner).
            setRole(orgId, oldOwnerId, OrgRole.ADMIN)
            setRole(orgId, newOwnerId, OrgRole.OWNER)
        }
    }

    private fun JdbcTransaction.setRole(orgId: Uuid, memberId: Uuid, role: OrgRole) {
        val m = OrgMembers
        m.update({ (m.orgId eq orgId) and (m.id eq memberId) }) { it[m.role] = role.key() }
    }
}

/**
 * [InvitationsRepository] on PostgreSQL through Exposed.
 *
 * @param database The serving role's pool.
 */
class InvitationsRepositoryImpl(private val database: JdbcDatabase) : InvitationsRepository {
    override suspend fun create(invitation: Invitation, tokenHash: String) {
        database.inOrg(invitation.orgId) {
            OrgInvitations.insert {
                it[id] = invitation.id
                it[orgId] = invitation.orgId
                it[this.tokenHash] = tokenHash
                it[email] = invitation.email
                it[role] = invitation.role.key()
                it[invitedBy] = invitation.invitedBy
                it[createdAt] = invitation.createdAt
                it[expiresAt] = invitation.expiresAt
                it[maxUses] = invitation.maxUses
                it[uses] = invitation.uses
            }
        }
    }

    override suspend fun listLive(orgId: Uuid, now: Instant): List<Invitation> = database.inOrg(orgId) {
        val i = OrgInvitations
        i.select(i.read).where { (i.orgId eq orgId) and i.live(now) }
            .orderBy(i.createdAt to SortOrder.DESC, i.id to SortOrder.ASC)
            .map { it.toInvitation(i) }
    }

    override suspend fun pendingEmails(orgId: Uuid, now: Instant): Set<String> =
        listLive(orgId, now).mapNotNull { it.email }.toSet()

    override suspend fun findLiveByTokenHash(tokenHash: String, now: Instant): Invitation? = database.query {
        val i = OrgsLiveInvitation
        i.of(tokenHash, now).select(i.read).firstOrNull()?.toInvitation(i)
    }

    override suspend fun revoke(orgId: Uuid, id: Uuid, at: Instant): Boolean = database.inOrg(orgId) {
        val i = OrgInvitations
        i.update({ (i.orgId eq orgId) and (i.id eq id) and i.live(at) }) { it[revokedAt] = at } == 1
    }

    override suspend fun accept(invitation: Invitation, member: Member, now: Instant): Boolean =
        uniqueAs(::AlreadyMemberException) {
            database.inOrg(invitation.orgId) {
                val i = OrgInvitations
                // Counted only while still live: two people racing for the last use of a link cannot
                // both get in.
                val used = i.update({ (i.orgId eq invitation.orgId) and (i.id eq invitation.id) and i.live(now) }) {
                    it[uses] = uses + 1
                } == 1
                if (used) {
                    OrgMembers.insert {
                        it[id] = member.id
                        it[orgId] = member.orgId
                        it[userId] = member.userId
                        it[role] = member.role.key()
                        it[joinedAt] = member.joinedAt
                    }
                }
                used
            }
        }
}

/**
 * The stored name of a role.
 *
 * @return `owner`, `admin`…
 */
internal fun OrgRole.key(): String = name.lowercase()

/**
 * Reads a stored role.
 *
 * @param key The stored name.
 * @return The role.
 */
internal fun roleOf(key: String): OrgRole = OrgRole.valueOf(key.uppercase())

/** The columns of a member, by the names of the table or the function that holds them. */
internal class MemberColumns(
    val id: Expression<Uuid>,
    val orgId: Expression<Uuid>,
    val userId: Expression<Uuid>,
    val role: Expression<String>,
    val joinedAt: Expression<Instant>,
) {
    /** Every column, to select. */
    val all: List<Expression<*>> get() = listOf(id, orgId, userId, role, joinedAt)
}

private val OrgMembers.member get() = MemberColumns(id, orgId, userId, role, joinedAt)

private fun ResultRow.toOrg(t: OrgRows) = Org(
    id = this[t.id],
    slug = this[t.slug],
    name = this[t.name],
    githubOrgId = this[t.githubOrgId],
    freeClaim = this[t.freeClaim],
    createdAt = this[t.createdAt],
    timeZone = this[t.timeZone],
    hooksDeadlineSeconds = this[t.hooksDeadlineSeconds],
)

private fun ResultRow.toMember(c: MemberColumns) = Member(
    id = this[c.id],
    orgId = this[c.orgId],
    userId = this[c.userId],
    role = roleOf(this[c.role]),
    joinedAt = this[c.joinedAt],
)

private fun ResultRow.toInvitation(t: InvitationRows) = Invitation(
    id = this[t.id],
    orgId = this[t.orgId],
    email = this[t.email],
    role = roleOf(this[t.role]),
    invitedBy = this[t.invitedBy],
    createdAt = this[t.createdAt],
    expiresAt = this[t.expiresAt],
    maxUses = this[t.maxUses],
    uses = this[t.uses],
    revokedAt = this[t.revokedAt],
)

// `(parts…) > (subquery)`: the rows after the one row [cursor] reads, one index condition; false when it reads no row.
private class RowOf(private val parts: List<Expression<*>>) : Expression<Any>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        parts.appendTo(prefix = "(", postfix = ")") { append(it) }
    }
}

private fun rowAfter(parts: List<Expression<*>>, cursor: Query): Op<Boolean> =
    GreaterOp(RowOf(parts), wrapAsExpression<Any>(cursor))
