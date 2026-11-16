// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.orgs

import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.identity.domain.AuditEntry
import run.ostrom.server.identity.domain.AuditEvent
import run.ostrom.server.identity.domain.AuditLog
import run.ostrom.server.identity.domain.AuditRepository
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.identity.infrastructure.TransactionalAuditLog
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgProfile
import run.ostrom.server.kernel.auth.OrgRequiredException
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.orgs.domain.AlreadyMemberException
import run.ostrom.server.orgs.domain.Invitation
import run.ostrom.server.orgs.domain.InvitationTokens
import run.ostrom.server.orgs.domain.InvitationsRepository
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.MembersRepository
import run.ostrom.server.orgs.domain.Membership
import run.ostrom.server.orgs.domain.Org
import run.ostrom.server.orgs.domain.OrgAudit
import run.ostrom.server.orgs.domain.OrgDeletionsRepository
import run.ostrom.server.orgs.domain.OrgEvent
import run.ostrom.server.orgs.domain.OrgRateLimiter
import run.ostrom.server.orgs.domain.OrgsRepository
import run.ostrom.server.orgs.domain.People
import run.ostrom.server.orgs.domain.SlugTakenException
import run.ostrom.server.orgs.fakes.orgDeleted
import run.ostrom.server.orgs.infrastructure.OrgInvitations
import run.ostrom.server.orgs.infrastructure.OrgMembers
import run.ostrom.server.orgs.infrastructure.OrgOrganizations
import run.ostrom.server.orgs.infrastructure.OrgsRepositoryImpl
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The repositories on a real PostgreSQL, the row-level security of each table, and the identity
 * adapters.
 */
class OrgsPostgresTest {
    private val modules = listOf(IdentityModule, OrgsModule)
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    private suspend fun TestServer.user(name: String, email: String = "$name@example.com"): User =
        koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, name, email, true, name, login = name)
        )

    private fun org(slug: String, claim: String? = null) =
        Org(Uuid.random(), slug, slug.uppercase(), null, claim, now)

    private fun member(org: Org, user: User, role: OrgRole, at: Instant = now) =
        Member(Uuid.random(), org.id, user.id, role, at)

    private fun link(
        org: Org,
        by: Member,
        maxUses: Int? = 2,
        expiresAt: Instant = now + 7.days,
        email: String? = null,
    ) =
        Invitation(
            Uuid.random(),
            org.id,
            email,
            OrgRole.MEMBER,
            by.id,
            now,
            expiresAt,
            maxUses,
            0,
            null,
        )

    private suspend fun TestServer.nameAndZone(id: Uuid): Pair<String, String> =
        database.superuserJdbc.query {
            val o = OrgOrganizations
            o.select(o.name, o.timeZone).where { o.id eq id }.single().let { it[o.name] to it[o.timeZone] }
        }

    private suspend fun TestServer.auditLines(orgId: Uuid, action: String): Long =
        checkNotNull(
            database.superuserTexts(
                "SELECT count(*) FROM identity_audit_events WHERE org_id = '$orgId' AND action = '$action'",
            ).single(),
        ).toLong()

    private suspend fun TestServer.purgeAfter(id: Uuid): Instant = koin.get<JdbcDatabase>().inOrg(id) {
        val o = OrgOrganizations
        checkNotNull(o.select(o.purgeAfter).where { o.id eq id }.single()[o.purgeAfter])
    }

    @Test
    @Suppress("LongMethod") // One organization's life through the repositories, read top to bottom.
    fun organizationsAreCreatedReadRenamedAndDeleted() =
        serverTest(modules) { server ->
            val orgs = server.koin.get<OrgsRepository>()
            val members = server.koin.get<MembersRepository>()
            val invitations = server.koin.get<InvitationsRepository>()
            val deletions = server.koin.get<OrgDeletionsRepository>()
            val ada = server.user("ada")
            val acme = org("acme", "domain:acme.com")
            val owner = member(acme, ada, OrgRole.OWNER)

            assertNull(orgs.create(acme, owner) { false })

            assertEquals(acme, orgs.get(acme.id))
            assertEquals(acme, orgs.findBySlug("acme"))
            assertTrue(orgs.slugTaken("acme"))
            assertFalse(orgs.slugTaken("other"))
            assertEquals(listOf(Membership(acme, owner)), orgs.listOfUser(ada.id))
            assertFailsWith<SlugTakenException> {
                orgs.create(org("acme"), member(acme, ada, OrgRole.OWNER)) { false }
            }
            orgs.update(acme.id, null, "Asia/Tokyo")
            assertEquals(acme.copy(timeZone = "Asia/Tokyo"), orgs.get(acme.id), "Only the time zone")
            orgs.update(acme.id, "Acme Labs", null)
            assertEquals(acme.copy(name = "Acme Labs", timeZone = "Asia/Tokyo"), orgs.get(acme.id), "Only the name")
            orgs.update(acme.id, null, null)
            orgs.update(acme.id, "Acme Labs", "Europe/Paris")
            assertEquals(acme.copy(name = "Acme Labs", timeZone = "Europe/Paris"), orgs.get(acme.id))
            assertEquals("Europe/Paris", orgs.listOfUser(ada.id).single().org.timeZone)
            assertEquals("Europe/Paris", orgs.findBySlug("acme")?.timeZone)
            assertEquals(15, orgs.get(acme.id)?.hooksDeadlineSeconds, "15 seconds until set")
            orgs.update(acme.id, null, null, 45)
            assertEquals(45, orgs.get(acme.id)?.hooksDeadlineSeconds, "Only the hooks deadline")
            assertEquals("Acme Labs" to "Europe/Paris", orgs.get(acme.id)?.let { it.name to it.timeZone })
            assertEquals(45, orgs.listOfUser(ada.id).single().org.hooksDeadlineSeconds)
            assertEquals(45, orgs.findBySlug("acme")?.hooksDeadlineSeconds)
            orgs.update(acme.id, "Acme Labs", "Europe/Paris", 300)
            assertEquals(300, orgs.get(acme.id)?.hooksDeadlineSeconds, "With the others")
            assertTrue(acme.id !in deletions.deletionsToSettle(now, 10), "A live organization is not being deleted")

            invitations.create(link(acme, owner), "hash-1")
            assertTrue(orgs.delete(acme.id, now, orgDeleted(acme.id, ada.id), now + 30.days))
            // A second deletion, replayed or run at the same time, changes nothing: its 30 days do not start again.
            assertFalse(orgs.delete(acme.id, now + 1.days, orgDeleted(acme.id, ada.id), now + 31.days))
            assertEquals(now + 30.days, server.purgeAfter(acme.id))
            orgs.update(acme.id, "Late rename", "Asia/Tokyo")
            assertEquals(
                "Acme Labs" to "Europe/Paris",
                server.nameAndZone(acme.id),
                "A change that raced the deletion leaves the deleted row as it is",
            )
            assertNull(orgs.get(acme.id))
            assertNull(orgs.findBySlug("acme"))
            assertTrue(orgs.slugTaken("acme"), "A deleted organization keeps its short name during its days of grace")
            assertEquals(emptyList(), orgs.listOfUser(ada.id))
            assertNull(members.findByUser(acme.id, ada.id))
            assertNull(invitations.findLiveByTokenHash("hash-1", now))
            // Its free claim is released.
            val acme2 = org("acme-2", "domain:acme.com")
            assertNull(orgs.create(acme2, member(acme2, ada, OrgRole.OWNER)) { false })

            // The worker's steps, across organizations: told, then erased after 30 days.
            assertTrue(acme.id in deletions.deletionsToSettle(now, 10))
            deletions.postpone(acme.id, now + 10.minutes)
            assertTrue(acme.id !in deletions.deletionsToSettle(now, 10), "Put off")
            assertTrue(acme.id in deletions.deletionsToSettle(now + 10.minutes, 10))
            assertTrue(acme.id !in deletions.duePurges(now + 31.days, 10), "Never erased before every module was told")
            deletions.markSettled(acme.id, now)
            assertTrue(acme.id !in deletions.deletionsToSettle(now, 10))
            assertTrue(acme.id !in deletions.duePurges(now + 30.days - 1.minutes, 10), "Kept for 30 days")
            assertTrue(acme.id in deletions.duePurges(now + 30.days, 10))
            deletions.erase(acme.id, now + 30.days)
            assertTrue(acme.id !in deletions.duePurges(now + 31.days, 10))
            deletions.erase(acme2.id, now)
            assertEquals(acme2, orgs.get(acme2.id), "A live organization is never erased")
            val o = OrgOrganizations
            val tombstone = server.koin.get<JdbcDatabase>().inOrg(acme.id) {
                o.select(o.name, o.githubOrgId, o.timeZone, o.purgedAt).where { o.id eq acme.id }.single()
            }
            assertEquals("", tombstone[o.name])
            assertNull(tombstone[o.githubOrgId])
            assertEquals("UTC", tombstone[o.timeZone])
            assertEquals(now + 30.days, tombstone[o.purgedAt])
            assertFalse(orgs.slugTaken("acme"), "An erased organization gives its short name back")
        }

    @Test
    fun aDatabaseFailureOtherThanATakenSlugIsNotHidden() =
        serverTest(modules) { server ->
            val orgs = server.koin.get<OrgsRepository>()
            val invitations = server.koin.get<InvitationsRepository>()
            val ada = server.user("ada")
            val acme = org("acme").copy(githubOrgId = 42)
            val owner = member(acme, ada, OrgRole.OWNER)
            val ghost = member(acme, ada, OrgRole.OWNER).copy(userId = Uuid.random())

            assertFailsWith<ExposedSQLException> { orgs.create(acme, ghost) { false } }
            assertNull(orgs.get(acme.id), "Nothing of the failed creation is left")
            assertNull(orgs.create(acme, owner) { false })
            assertEquals(42L, orgs.get(acme.id)?.githubOrgId)
            val invitation = link(acme, owner)
            invitations.create(invitation, "h")
            assertFailsWith<ExposedSQLException> {
                invitations.accept(
                    invitation,
                    member(acme, ada, OrgRole.MEMBER).copy(userId = Uuid.random()),
                    now,
                )
            }
            assertEquals(0, invitations.findLiveByTokenHash("h", now)?.uses)
        }

    @Test
    fun twoDeletionsAtOnceDeleteOnceAndRecordOneEvent() =
        serverTest(modules) { server ->
            val orgs = server.koin.get<OrgsRepository>()
            val ada = server.user("ada")
            val acme = org("acme")
            orgs.create(acme, member(acme, ada, OrgRole.OWNER)) { false }

            val deleted = coroutineScope {
                (1..4).map { async { orgs.delete(acme.id, now, orgDeleted(acme.id, ada.id)) } }.awaitAll()
            }

            assertEquals(1, deleted.count { it }, "Exactly one deletion wins")
            assertEquals(1L, server.auditLines(acme.id, "org.deleted"), "One event, chained once")
        }

    // The deletion's audit line is signed between two transactions, on the chain's head: when another writer appends
    // meanwhile, the deletion is rolled back and its line signed again on the new head, so it is written once.
    @Test
    fun aDeletionWhoseAuditHeadMovedWhileItWasSignedIsSignedAgainAndWrittenOnce() =
        serverTest(modules) { server ->
            val log = server.koin.get<AuditLog>()
            var races = 0
            val racing = TransactionalAuditLog(
                object : AuditLog by log {
                    override fun sign(entry: AuditEntry): suspend (String?, Long) -> AuditEvent {
                        val build = log.sign(entry)
                        return { previousHash, sequence ->
                            if (races > 0) log.record(AuditEntry(entry.orgId, null, "meanwhile", "t", "${races--}"))
                            build(previousHash, sequence)
                        }
                    }
                },
            )
            val orgs = OrgsRepositoryImpl(server.koin.get(), racing)
            val members = server.koin.get<MembersRepository>()
            val ada = server.user("ada")
            val acme = org("acme")
            val globex = org("globex")
            orgs.create(acme, member(acme, ada, OrgRole.OWNER)) { false }
            orgs.create(globex, member(globex, ada, OrgRole.OWNER)) { false }

            races = 1
            assertTrue(orgs.delete(acme.id, now, orgDeleted(acme.id, ada.id)))
            assertEquals(0, races)
            assertNull(orgs.get(acme.id))
            assertEquals(1L, server.auditLines(acme.id, "org.deleted"), "The line is written once")
            assertEquals(1L, server.auditLines(acme.id, "meanwhile"))

            // A chain that moves at every signature: the deletion fails loudly, nothing of it written.
            races = 100
            assertFailsWith<IllegalStateException> { orgs.delete(globex.id, now, orgDeleted(globex.id, ada.id)) }
            assertEquals(92, races)
            assertEquals(globex, orgs.get(globex.id))
            assertEquals(1, members.count(globex.id))
            races = 0
            assertFalse(orgs.delete(acme.id, now, orgDeleted(acme.id, ada.id)), "Already deleted: nothing to append")
        }

    @Test
    fun aFreeClaimIsHeldByOneFreeOrganizationEvenUnderConcurrentCreations() =
        serverTest(modules) { server ->
            val orgs = server.koin.get<OrgsRepository>()
            val ada = server.user("ada")
            val bob = server.user("bob")
            val candidates = (1..4).map { org("team-$it", "domain:acme.com") }

            val holders = coroutineScope {
                candidates
                    .map { candidate ->
                        async {
                            orgs.create(
                                candidate,
                                member(
                                    candidate,
                                    if (candidate.slug == "team-1") ada else bob,
                                    OrgRole.OWNER,
                                ),
                            ) {
                                false
                            }
                        }
                    }
                    .awaitAll()
            }

            assertEquals(1, holders.count { it == null }, "Exactly one creation wins")
            val winner = candidates.single { orgs.get(it.id) != null }
            assertTrue(holders.filterNotNull().all { it.id == winner.id })
            val paid = org("team-5", "domain:acme.com")
            assertNull(orgs.create(paid, member(paid, bob, OrgRole.OWNER)) { it == winner.id })
        }

    // The claim's holders are read, asked about outside any transaction, and read again under the claim's lock: a
    // holder another creation added meanwhile makes the answer stale, so the question is asked again on the new ones.
    @Test
    fun aClaimWhoseHoldersChangedWhileTheyWereAskedAboutIsSettledAgain() =
        serverTest(modules) { server ->
            val orgs = server.koin.get<OrgsRepository>()
            val ada = server.user("ada")
            val bob = server.user("bob")
            val claim = "domain:acme.com"
            val first = org("first", claim)
            orgs.create(first, member(first, ada, OrgRole.OWNER)) { false }
            val rival = org("rival", claim)
            val mine = org("mine", claim)
            val asked = mutableListOf<Uuid>()

            val blocker = orgs.create(mine, member(mine, bob, OrgRole.OWNER)) { id ->
                asked += id
                // The first answer is "not paying", given while a rival that pays (the holders all pay) takes the
                // claim too.
                if (asked.size == 1) {
                    assertNull(orgs.create(rival, member(rival, bob, OrgRole.OWNER)) { true })
                    false
                } else {
                    true
                }
            }

            // The first answer was about one holder, stale once the rival was created: both are asked about again.
            assertNull(blocker, "Created once every holder pays")
            assertEquals(mine, orgs.get(mine.id))
            assertEquals(3, asked.size)
            assertEquals(setOf(first.id, rival.id), asked.toSet())
        }

    @Test
    fun aClaimWhoseHoldersKeepChangingIsGivenUpAfterEightAttempts() =
        serverTest(modules) { server ->
            val orgs = server.koin.get<OrgsRepository>()
            val ada = server.user("ada")
            val claim = "domain:acme.com"
            val first = org("first", claim)
            orgs.create(first, member(first, ada, OrgRole.OWNER)) { false }
            val mine = org("mine", claim)
            var rivals = 0

            // Every question is answered while another holder is created (paying: nothing blocks its creation).
            assertFailsWith<IllegalStateException> {
                orgs.create(mine, member(mine, ada, OrgRole.OWNER)) {
                    val rival = org("rival-${rivals++}", claim)
                    orgs.create(rival, member(rival, ada, OrgRole.OWNER)) { true }
                    false
                }
            }

            assertEquals(8, rivals)
            assertNull(orgs.get(mine.id), "Nothing of the abandoned creation is left")
        }

    @Test
    fun membersHaveOneOwnerAndChangeRoles() =
        serverTest(modules) { server ->
            val orgs = server.koin.get<OrgsRepository>()
            val members = server.koin.get<MembersRepository>()
            val ada = server.user("ada")
            val bob = server.user("bob")
            val acme = org("acme")
            val owner = member(acme, ada, OrgRole.OWNER)
            orgs.create(acme, owner) { false }
            val invitations = server.koin.get<InvitationsRepository>()
            val bobMember = member(acme, bob, OrgRole.MEMBER, now + 1.minutes)
            val invitation = link(acme, owner)
            invitations.create(invitation, "h")
            assertTrue(invitations.accept(invitation, bobMember, now))

            assertEquals(listOf(owner, bobMember), members.list(acme.id))
            assertEquals(2, members.count(acme.id))
            members.changeRole(acme.id, bobMember.id, OrgRole.BILLING)
            assertEquals(OrgRole.BILLING, members.findByUser(acme.id, bob.id)?.role)
            assertFailsWith<ExposedSQLException> { members.changeRole(acme.id, bobMember.id, OrgRole.OWNER) }
            members.transferOwnership(acme.id, owner.id, bobMember.id)
            assertEquals(OrgRole.OWNER, members.findByUser(acme.id, bob.id)?.role)
            assertEquals(OrgRole.ADMIN, members.findByUser(acme.id, ada.id)?.role)
            members.remove(acme.id, owner.id)
            assertEquals(listOf(bob.id), members.list(acme.id).map { it.userId })
        }

    @Test
    fun invitationsWorkWhileLiveAndTheLastUseGoesToOnePerson() =
        serverTest(modules) { server ->
            val orgs = server.koin.get<OrgsRepository>()
            val invitations = server.koin.get<InvitationsRepository>()
            val ada = server.user("ada")
            val acme = org("acme")
            val owner = member(acme, ada, OrgRole.OWNER)
            orgs.create(acme, owner) { false }
            val single = link(acme, owner, maxUses = 1)
            val email =
                link(acme, owner, maxUses = 1, email = "dana@example.com")
                    .copy(createdAt = now + 1.minutes)
            val expired = link(acme, owner, expiresAt = now)
            val revoked = link(acme, owner)
            val unlimited = link(acme, owner, maxUses = null)
            listOf(single, email, expired, revoked, unlimited).forEachIndexed { index, invitation ->
                invitations.create(invitation, "hash-$index")
            }

            assertTrue(invitations.revoke(acme.id, revoked.id, now))
            assertFalse(invitations.revoke(acme.id, revoked.id, now))
            assertFalse(invitations.revoke(Uuid.random(), single.id, now))
            assertEquals(
                setOf(single.id, email.id, unlimited.id),
                invitations.listLive(acme.id, now).map { it.id }.toSet(),
            )
            assertEquals(email.id, invitations.listLive(acme.id, now).first().id)
            assertEquals(setOf("dana@example.com"), invitations.pendingEmails(acme.id, now))
            assertEquals(single, invitations.findLiveByTokenHash("hash-0", now))
            assertEquals(unlimited, invitations.findLiveByTokenHash("hash-4", now))
            assertNull(invitations.findLiveByTokenHash("hash-2", now))
            assertNull(invitations.findLiveByTokenHash("hash-3", now))

            val people = (1..3).map { server.user("p$it") }
            val results = coroutineScope {
                people
                    .map { person ->
                        async {
                            invitations.accept(single, member(acme, person, OrgRole.MEMBER), now)
                        }
                    }
                    .awaitAll()
            }
            assertEquals(1, results.count { it }, "A one-use link brings in one person")
            assertNull(invitations.findLiveByTokenHash("hash-0", now))
            val joined = people[results.indexOf(true)]
            assertFailsWith<AlreadyMemberException> {
                invitations.accept(unlimited, member(acme, joined, OrgRole.MEMBER), now)
            }
            assertEquals(
                0,
                invitations.findLiveByTokenHash("hash-4", now)?.uses,
                "The refused acceptance did not count",
            )
        }

    @Test
    fun rowLevelSecurityConfinesEveryTableToItsOrganization() =
        serverTest(modules) { server ->
            val orgs = server.koin.get<OrgsRepository>()
            val invitations = server.koin.get<InvitationsRepository>()
            val ada = server.user("ada")
            val bob = server.user("bob")
            val acme = org("acme")
            val globex = org("globex")
            val acmeOwner = member(acme, ada, OrgRole.OWNER)
            val globexOwner = member(globex, bob, OrgRole.OWNER)
            orgs.create(acme, acmeOwner) { false }
            orgs.create(globex, globexOwner) { false }
            invitations.create(link(acme, acmeOwner), "a")
            invitations.create(link(globex, globexOwner), "g")

            // Queries without any WHERE on the organization still only see the confined one.
            val jdbc = server.koin.get<JdbcDatabase>()
            val seen = jdbc.inOrg(globex.id) {
                val o = OrgOrganizations
                val m = OrgMembers
                val i = OrgInvitations
                mapOf(
                    "orgs_organizations" to o.select(o.id).map { it[o.id] },
                    "orgs_members" to m.select(m.orgId).map { it[m.orgId] },
                    "orgs_invitations" to i.select(i.orgId).map { it[i.orgId] },
                )
            }
            seen.forEach { (table, ids) -> assertEquals(listOf(globex.id), ids.distinct(), table) }
            // Writing a row of another organization from inside one fails the policy's check.
            assertFailsWith<ExposedSQLException> {
                jdbc.inOrg(globex.id) {
                    OrgMembers.insert {
                        it[id] = Uuid.random()
                        it[orgId] = acme.id
                        it[userId] = bob.id
                        it[role] = "member"
                        it[joinedAt] = now
                    }
                }
            }
            val updated = jdbc.inOrg(globex.id) {
                OrgOrganizations.update({ OrgOrganizations.id eq acme.id }) { it[name] = "taken" }
            }
            assertEquals(0, updated)
            assertEquals("ACME", orgs.get(acme.id)?.name)
            // The repositories confine themselves too: Globex's id never reads Acme's members.
            assertNull(server.koin.get<MembersRepository>().findByUser(globex.id, ada.id))
            assertFalse(
                invitations.revoke(globex.id, invitations.listLive(acme.id, now).single().id, now)
            )
        }

    @Test
    fun withoutAnOrganizationInThePathTheHeaderOrTheOnlyMembershipDecides() =
        serverTest(modules) { server ->
            val orgs = server.koin.get<OrgsRepository>()
            val resolver = server.koin.get<PrincipalOrgResolver>()
            val ada = server.user("ada")
            val mallory = server.user("mallory")
            val loner = server.user("loner")
            val acme = org("acme")
            val acmeOwner = member(acme, ada, OrgRole.OWNER)
            orgs.create(acme, acmeOwner) { false }
            val evil = org("evil")
            orgs.create(evil, member(evil, mallory, OrgRole.OWNER)) { false }
            val person = AuthenticatedPrincipal(ada.id.toString(), PrincipalKind.USER, setOf("user:*"))
            fun named(org: Org) = person.copy(namedOrg = org.id.toString())

            assertEquals(OrgProfile(acme.id, "acme", "ACME", "", linkedToGitHub = false), resolver.profile(acme.id))
            assertNull(resolver.profile(Uuid.random()))
            // One membership: nothing to name.
            assertEquals(OrgPrincipal(acme.id, acmeOwner.id, OrgRole.OWNER), resolver.resolve(person, null))
            // No membership: nobody.
            val alone = AuthenticatedPrincipal(loner.id.toString(), PrincipalKind.USER, emptySet())
            assertNull(resolver.resolve(alone, null))

            val globex = org("globex")
            val globexMember = member(globex, ada, OrgRole.MEMBER)
            orgs.create(globex, globexMember) { false }
            // Two memberships and nothing named: the request must name one (409 org_required).
            val unnamed = assertFailsWith<OrgRequiredException> { resolver.resolve(person, null) }
            assertEquals("org_required", unnamed.kind.type)
            // Two memberships and the header: the one it names, checked against the membership.
            assertEquals(
                OrgPrincipal(globex.id, globexMember.id, OrgRole.MEMBER),
                resolver.resolve(named(globex), null),
            )
            assertEquals(acmeOwner.id, resolver.resolve(named(acme), null)?.memberId)
            // The path wins over the header.
            assertEquals(acmeOwner.id, resolver.resolve(named(globex), acme.id)?.memberId)
            // A header naming an organization the caller is not in, or no id at all: nobody (404, no leak).
            assertNull(resolver.resolve(named(evil), null))
            assertNull(resolver.resolve(person.copy(namedOrg = "acme"), null))
            // A bound token keeps to its organization, whatever the header says.
            val bound = person.copy(orgId = acme.id.toString())
            assertEquals(acmeOwner.id, resolver.resolve(bound, null)?.memberId)
            assertEquals(acmeOwner.id, resolver.resolve(bound.copy(namedOrg = acme.id.toString()), null)?.memberId)
            assertNull(resolver.resolve(bound.copy(namedOrg = globex.id.toString()), null))
        }

    @Test
    fun theResolverOnlyResolvesAPersonWhoIsAMember() =
        serverTest(modules) { server ->
            val orgs = server.koin.get<OrgsRepository>()
            val resolver = server.koin.get<PrincipalOrgResolver>()
            val ada = server.user("ada")
            val acme = org("acme")
            val owner = member(acme, ada, OrgRole.OWNER)
            orgs.create(acme, owner) { false }
            val person =
                AuthenticatedPrincipal(ada.id.toString(), PrincipalKind.USER, setOf("user:*"))

            assertEquals(
                OrgPrincipal(acme.id, owner.id, OrgRole.OWNER),
                resolver.resolve(person, acme.id),
            )
            assertNull(resolver.resolve(person, Uuid.random()))
            assertEquals(owner.id, resolver.resolve(person, null)?.memberId, "Their only organization")
            val bound = person.copy(orgId = acme.id.toString())
            assertEquals(owner.id, resolver.resolve(bound, null)?.memberId)
            assertEquals(owner.id, resolver.resolve(bound, acme.id)?.memberId)
            assertNull(resolver.resolve(bound.copy(orgId = Uuid.random().toString()), acme.id))
            assertNull(resolver.resolve(person.copy(kind = PrincipalKind.AGENT), acme.id))
            assertNull(resolver.resolve(person.copy(subjectId = "not-a-uuid"), acme.id))
            assertNull(
                resolver.resolve(
                    AuthenticatedPrincipal(
                        Uuid.random().toString(),
                        PrincipalKind.USER,
                        emptySet(),
                    ),
                    acme.id,
                )
            )
        }

    @Test
    fun theDirectoryKnowsTheMembersOfAnOrganizationAndNoOneElse() =
        serverTest(modules) { server ->
            val orgs = server.koin.get<OrgsRepository>()
            val members = server.koin.get<MembersRepository>()
            val directory = server.koin.get<MemberDirectory>()
            val ada = server.user("ada")
            val bob = server.user("bob")
            val acme = org("acme")
            val owner = member(acme, ada, OrgRole.OWNER)
            orgs.create(acme, owner) { false }
            val other = org("other")
            val bobsOwner = member(other, bob, OrgRole.OWNER)
            orgs.create(other, bobsOwner) { false }

            assertEquals(owner, members.findById(acme.id, owner.id))
            assertTrue(directory.isActiveMember(acme.id, owner.id.toString()))
            // Another organization's member is not one, even by its exact id (row-level security).
            assertNull(members.findById(acme.id, bobsOwner.id))
            assertFalse(directory.isActiveMember(acme.id, bobsOwner.id.toString()))
            assertFalse(directory.isActiveMember(acme.id, Uuid.random().toString()))
            assertFalse(directory.isActiveMember(acme.id, "not-a-member-id"))
            // The person behind a member, for what an owner or an admin reads of them (their devices).
            assertEquals(ada.id, directory.userIdOf(acme.id, owner.id.toString()))
            assertNull(directory.userIdOf(acme.id, bobsOwner.id.toString()))
            assertNull(directory.userIdOf(acme.id, "not-a-member-id"))
            assertTrue(directory.hasUser(acme.id, ada.id))
            assertFalse(directory.hasUser(acme.id, bob.id))
            // The members of one organization only, or null past the limit (the MCP relay's retain sweep).
            assertEquals(listOf(owner.id.toString()), directory.memberIds(acme.id, 10))
            assertNull(directory.memberIds(acme.id, 0))
            // Each member's role, for the picker of a session transfer, or null past the limit.
            assertEquals(mapOf(owner.id.toString() to OrgRole.OWNER), directory.roles(acme.id, 10))
            assertNull(directory.roles(acme.id, 0))
            // One member's role, for the owner of a cloud session created on their behalf.
            assertEquals(OrgRole.OWNER, directory.roleOf(acme.id, owner.id.toString()))
            assertNull(directory.roleOf(acme.id, bobsOwner.id.toString()))
            assertNull(directory.roleOf(acme.id, "not-a-member-id"))
            // Deleting the organization removes its members: none stays active.
            orgs.delete(acme.id, now, orgDeleted(acme.id))
            assertFalse(directory.isActiveMember(acme.id, owner.id.toString()))
        }

    @Test
    fun theIdentityAdaptersReadPeopleAndWriteTheSignedAuditChain() =
        serverTest(modules) { server ->
            val people = server.koin.get<People>()
            val ada = server.user("ada", "Ada@Acme.com")
            val bob = server.user("bob")

            assertEquals("ada", people.get(ada.id)?.login)
            assertNull(people.get(Uuid.random()))
            assertEquals(
                setOf("ada", "bob"),
                people.getMany(listOf(ada.id, bob.id)).map { it.login }.toSet(),
            )
            assertEquals(ada.id, people.findByLogin("ADA")?.id)
            assertNull(people.findByLogin("nobody"))
            assertEquals(setOf("ada@acme.com"), people.verifiedEmails(ada.id))

            val orgId = Uuid.random()
            server.koin
                .get<OrgAudit>()
                .record(OrgEvent(orgId, ada.id, "org.member.removed", "member", "m-1", mapOf("login" to "bob")))
            val event = server.koin.get<AuditRepository>().list(orgId, null, null, 10).single()
            assertEquals("org.member.removed", event.entry.action)
            assertEquals(mapOf("login" to "bob"), event.entry.details)

            val tokens = server.koin.get<InvitationTokens>()
            val token = tokens.generate()
            assertNotEquals(token, tokens.generate())
            assertEquals(tokens.hash(token), tokens.hash(token))
            assertNotEquals(token, tokens.hash(token))
            val limiter = server.koin.get<OrgRateLimiter>()
            assertTrue(limiter.tryAcquire("k", 1, 1))
            assertFalse(limiter.tryAcquire("k", 1, 1))
        }
}
