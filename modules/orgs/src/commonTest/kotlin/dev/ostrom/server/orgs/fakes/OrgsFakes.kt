// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs.fakes

import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.orgs.domain.AlreadyMemberException
import dev.ostrom.server.orgs.domain.Invitation
import dev.ostrom.server.orgs.domain.InvitationMailer
import dev.ostrom.server.orgs.domain.InvitationServices
import dev.ostrom.server.orgs.domain.InvitationTokens
import dev.ostrom.server.orgs.domain.InvitationsRepository
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.MembersRepository
import dev.ostrom.server.orgs.domain.Membership
import dev.ostrom.server.orgs.domain.Org
import dev.ostrom.server.orgs.domain.OrgAudit
import dev.ostrom.server.orgs.domain.OrgEvent
import dev.ostrom.server.orgs.domain.OrgRateLimiter
import dev.ostrom.server.orgs.domain.OrgsRepository
import dev.ostrom.server.orgs.domain.People
import dev.ostrom.server.orgs.domain.Person
import dev.ostrom.server.orgs.domain.SeatPolicy
import dev.ostrom.server.orgs.domain.SlugTakenException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A clock fixed at 2026-10-30T10:00:00Z, moved by hand. */
class SettableClock(var now: Instant = Instant.parse("2026-10-30T10:00:00Z")) : Clock {
    override fun now(): Instant = now
}

/** Organizations, members and invitations in memory, sharing one store as the database does. */
class OrgsStore {
    val orgs = mutableMapOf<Uuid, Org>()
    val deleted = mutableSetOf<Uuid>()

    /** When each deleted organization may be erased. */
    val purgeAfter = mutableMapOf<Uuid, Instant>()
    val settled = mutableSetOf<Uuid>()
    val erased = mutableSetOf<Uuid>()
    val members = mutableMapOf<Uuid, Member>()
    val invitations = mutableMapOf<Uuid, Pair<Invitation, String>>()
}

class FakeOrgs(private val store: OrgsStore) : OrgsRepository {
    override suspend fun create(
        org: Org,
        owner: Member,
        isPaying: suspend (Uuid) -> Boolean,
    ): Org? {
        val holder =
            store.orgs.values.firstOrNull {
                it.id !in store.deleted && it.freeClaim == org.freeClaim && !isPaying(it.id)
            }
        if (holder != null) return holder
        if (store.orgs.values.any { it.slug == org.slug }) throw SlugTakenException()
        store.orgs[org.id] = org
        store.members[owner.id] = owner
        return null
    }

    override suspend fun get(id: Uuid): Org? = store.orgs[id]?.takeIf { id !in store.deleted }

    override suspend fun findBySlug(slug: String): Org? =
        store.orgs.values.firstOrNull { it.slug == slug && it.id !in store.deleted }

    override suspend fun slugTaken(slug: String): Boolean =
        store.orgs.values.any { it.slug == slug }

    /** How many times [update] wrote. */
    var updates = 0

    override suspend fun update(id: Uuid, name: String, timeZone: String) {
        updates++
        store.orgs[id] = store.orgs.getValue(id).copy(name = name, timeZone = timeZone)
    }

    override suspend fun delete(id: Uuid, at: Instant, purgeAfter: Instant) {
        store.deleted += id
        store.purgeAfter[id] = purgeAfter
        store.orgs[id] = store.orgs.getValue(id).copy(freeClaim = null)
        store.members.values.removeAll { it.orgId == id }
        store.invitations.values.removeAll { it.first.orgId == id }
    }

    override suspend fun deletionsToSettle(limit: Int): List<Uuid> =
        store.deleted.filter { it !in store.settled }.take(limit)

    override suspend fun markSettled(id: Uuid, at: Instant) {
        store.settled += id
    }

    override suspend fun duePurges(now: Instant, limit: Int): List<Uuid> =
        store.purgeAfter
            .filter { (id, after) -> id in store.settled && id !in store.erased && after <= now }
            .keys
            .take(limit)

    override suspend fun erase(id: Uuid, at: Instant) {
        store.erased += id
        store.orgs[id] = store.orgs.getValue(id).copy(name = "", githubOrgId = null, timeZone = "UTC")
    }

    override suspend fun listOfUser(userId: Uuid): List<Membership> =
        store.members.values
            .filter { it.userId == userId }
            .mapNotNull { member -> get(member.orgId)?.let { Membership(it, member) } }
            .sortedBy { it.org.name }
}

class FakeMembers(private val store: OrgsStore) : MembersRepository {
    override suspend fun findByUser(orgId: Uuid, userId: Uuid): Member? =
        store.members.values.firstOrNull { it.orgId == orgId && it.userId == userId }

    override suspend fun list(orgId: Uuid): List<Member> =
        store.members.values.filter { it.orgId == orgId }.sortedBy { it.joinedAt }

    override suspend fun count(orgId: Uuid): Int = list(orgId).size

    override suspend fun changeRole(orgId: Uuid, memberId: Uuid, role: OrgRole) {
        store.members[memberId] = store.members.getValue(memberId).copy(role = role)
    }

    override suspend fun remove(orgId: Uuid, memberId: Uuid) {
        store.members.remove(memberId)
    }

    override suspend fun transferOwnership(orgId: Uuid, oldOwnerId: Uuid, newOwnerId: Uuid) {
        changeRole(orgId, oldOwnerId, OrgRole.ADMIN)
        changeRole(orgId, newOwnerId, OrgRole.OWNER)
    }
}

class FakeInvitations(private val store: OrgsStore) : InvitationsRepository {
    /**
     * When set, the next [accept] finds the invitation already used up (a concurrent acceptance
     * won).
     */
    var loseNextRace = false

    private fun Invitation.live(now: Instant) =
        revokedAt == null &&
            expiresAt > now &&
            (maxUses == null || uses < maxUses) &&
            orgId !in store.deleted

    override suspend fun create(invitation: Invitation, tokenHash: String) {
        store.invitations[invitation.id] = invitation to tokenHash
    }

    override suspend fun listLive(orgId: Uuid, now: Instant): List<Invitation> =
        store.invitations.values
            .map { it.first }
            .filter { it.orgId == orgId && it.live(now) }
            .sortedByDescending { it.createdAt }

    override suspend fun pendingEmails(orgId: Uuid, now: Instant): Set<String> =
        listLive(orgId, now).mapNotNull { it.email }.toSet()

    override suspend fun findLiveByTokenHash(tokenHash: String, now: Instant): Invitation? =
        store.invitations.values.firstOrNull { it.second == tokenHash && it.first.live(now) }?.first

    override suspend fun revoke(orgId: Uuid, id: Uuid, at: Instant): Boolean {
        val (invitation, hash) =
            store.invitations[id]?.takeIf { it.first.orgId == orgId && it.first.live(at) }
                ?: return false
        store.invitations[id] = invitation.copy(revokedAt = at) to hash
        return true
    }

    override suspend fun accept(invitation: Invitation, member: Member, now: Instant): Boolean {
        if (loseNextRace) {
            loseNextRace = false
            return false
        }
        if (store.members.values.any { it.orgId == member.orgId && it.userId == member.userId })
            throw AlreadyMemberException()
        val (stored, hash) = store.invitations.getValue(invitation.id)
        store.invitations[invitation.id] = stored.copy(uses = stored.uses + 1) to hash
        store.members[member.id] = member
        return true
    }
}

class FakePeople : People {
    val people = mutableMapOf<Uuid, Person>()
    val emails = mutableMapOf<Uuid, Set<String>>()

    fun add(
        login: String,
        email: String? = "$login@example.com",
        verified: Set<String> = setOfNotNull(email),
    ): Person {
        val person =
            Person(Uuid.random(), login, login.replaceFirstChar { it.uppercase() }, null, email)
        people[person.id] = person
        emails[person.id] = verified
        return person
    }

    override suspend fun get(id: Uuid): Person? = people[id]

    override suspend fun getMany(ids: Collection<Uuid>): List<Person> =
        ids.mapNotNull { people[it] }

    override suspend fun findByLogin(login: String): Person? =
        people.values.firstOrNull { it.login == login.lowercase() }

    override suspend fun verifiedEmails(id: Uuid): Set<String> = emails[id].orEmpty()
}

class FakeSeats(var limit: Int = 5, val paying: MutableSet<Uuid> = mutableSetOf()) : SeatPolicy {
    override suspend fun allows(orgId: Uuid, people: Int): Boolean = people <= limit

    override suspend fun isPaying(orgId: Uuid): Boolean = orgId in paying
}

class RecordingAudit : OrgAudit {
    val events = mutableListOf<Pair<String, Map<String, String>>>()

    override suspend fun record(event: OrgEvent) {
        events += event.action to event.details
    }
}

class FakeTokens : InvitationTokens {
    private var next = 0

    override fun generate(): String = "token-${++next}"

    override suspend fun hash(token: String): String = "hash:$token"
}

class FakeLimiter(var allow: Boolean = true) : OrgRateLimiter {
    override suspend fun tryAcquire(key: String, limit: Int, windowMinutes: Int): Boolean = allow
}

class RecordingMailer : InvitationMailer {
    val sent = mutableListOf<Pair<String, String>>()

    override suspend fun send(to: String, org: Org, inviter: Person, link: String) {
        sent += to to link
    }
}

/** Every fake wired together, as the module wires the real ones. */
class OrgsWorld {
    val clock = SettableClock()
    val store = OrgsStore()
    val orgs = FakeOrgs(store)
    val members = FakeMembers(store)
    val invitations = FakeInvitations(store)
    val people = FakePeople()
    val seats = FakeSeats()
    val audit = RecordingAudit()
    val tokens = FakeTokens()
    val limiter = FakeLimiter()
    val mailer = RecordingMailer()
    val services =
        InvitationServices(tokens, mailer, seats, limiter, audit) {
            "https://app.test/invite#token=$it"
        }
}
