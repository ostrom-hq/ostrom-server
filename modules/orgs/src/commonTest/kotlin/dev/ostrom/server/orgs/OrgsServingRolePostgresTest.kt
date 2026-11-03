// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs

import dev.ostrom.server.identity.IdentityModule
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.User
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.database.inOrg
import dev.ostrom.server.orgs.domain.Invitation
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.Membership
import dev.ostrom.server.orgs.domain.Org
import dev.ostrom.server.orgs.domain.SlugTakenException
import dev.ostrom.server.orgs.fakes.orgDeleted
import dev.ostrom.server.orgs.infrastructure.InvitationsRepositoryImpl
import dev.ostrom.server.orgs.infrastructure.MembersRepositoryImpl
import dev.ostrom.server.orgs.infrastructure.OrgDeletionsRepositoryImpl
import dev.ostrom.server.orgs.infrastructure.OrgsRepositoryImpl
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The organizations under a deployment's serving role (`ostrom_app` of `ci/database-roles.sql`): not a superuser,
 * owning nothing, subject to row-level security, here a fresh role granted exactly what `ostrom_app` is granted. Before
 * the test server itself ran as the serving role, the tests ran as a superuser, which bypasses it, and could not see
 * that creating an organization needs a cross-organization function.
 */
class OrgsServingRolePostgresTest {
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    private suspend fun TestServer.user(name: String): User =
        koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, name, "$name@example.com", true, name, login = name)
        )

    private fun org(slug: String, claim: String? = null) = Org(Uuid.random(), slug, slug.uppercase(), null, claim, now)

    private fun owner(org: Org, user: User) = Member(Uuid.random(), org.id, user.id, OrgRole.OWNER, now)

    @Test
    fun theServingRoleCreatesAndReadsAnOrganizationThatNoOtherOrganizationSees() =
        serverTest(listOf(IdentityModule, OrgsModule)) { server ->
            val ada = server.user("ada")
            val bob = server.user("bob")
            server.database.withRole { role, serving ->
                // What ci/database-roles.sql and the Helm chart grant `ostrom_app`.
                server.database.superuser
                    .execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO $role")
                    .getOrThrow()
                server.database.superuser.execute("GRANT ostrom_tenant TO $role").getOrThrow()
                val orgs = OrgsRepositoryImpl(serving, server.koin.get())
                // Without its own EXECUTE grant, a role cannot run them: PUBLIC has none (S2-60).
                assertFails { orgs.slugTaken("acme") }
                server.database.superuser
                    .execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO $role")
                    .getOrThrow()
                val members = MembersRepositoryImpl(serving)
                val invitations = InvitationsRepositoryImpl(serving)

                val acme = org("acme", "domain:acme.test")
                val adaOwner = owner(acme, ada)
                assertNull(orgs.create(acme, adaOwner) { false }, "Created")
                assertEquals(acme, orgs.get(acme.id))
                assertEquals(acme, orgs.findBySlug("acme"))
                assertTrue(orgs.slugTaken("acme"))
                assertEquals(listOf(Membership(acme, adaOwner)), orgs.listOfUser(ada.id))
                assertEquals(adaOwner, members.findByUser(acme.id, ada.id))
                // The free-plan claim still sees the other organizations' holder.
                val second = org("acme-2", "domain:acme.test")
                assertEquals(acme, orgs.create(second, owner(second, bob)) { false })
                assertFailsWith<SlugTakenException> { orgs.create(org("acme"), owner(org("acme"), bob)) { false } }

                val globex = org("globex")
                assertNull(orgs.create(globex, owner(globex, bob)) { false })
                // Confined to Globex, Acme's row and members are invisible; with no organization, nothing is.
                assertNull(members.findByUser(globex.id, ada.id))
                val seen = serving.inOrg(globex.id) {
                    fetchAll("SELECT count(*) AS n FROM orgs_organizations").getOrThrow().single().get("n").asLong()
                }
                assertEquals(1L, seen)
                assertTrue(serving.fetchAll("SELECT id FROM orgs_organizations").getOrThrow().toList().isEmpty())

                // An invitation link is found by its token from outside the organization.
                val invitation =
                    Invitation(Uuid.random(), acme.id, null, OrgRole.MEMBER, adaOwner.id, now, now + 7.days, 3, 0, null)
                invitations.create(invitation, "hash-1")
                assertEquals(invitation, invitations.findLiveByTokenHash("hash-1", now))
                assertNull(invitations.findLiveByTokenHash("hash-2", now))
            }
        }

    @Test
    fun theServingRoleChangesSettingsAndFinishesADeletionAcrossOrganizations() =
        serverTest(listOf(IdentityModule, OrgsModule)) { server ->
            val ada = server.user("ada")
            server.database.withRole { role, serving ->
                val superuser = server.database.superuser
                superuser
                    .execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO $role")
                    .getOrThrow()
                superuser.execute("GRANT ostrom_tenant TO $role").getOrThrow()
                superuser.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO $role").getOrThrow()
                val orgs = OrgsRepositoryImpl(serving, server.koin.get())
                val deletions = OrgDeletionsRepositoryImpl(serving)
                val acme = org("acme")
                assertNull(orgs.create(acme, owner(acme, ada)) { false })

                orgs.update(acme.id, "Acme Labs", "Europe/Paris")
                val zoned = acme.copy(name = "Acme Labs", timeZone = "Europe/Paris")
                assertEquals(zoned, orgs.get(acme.id))
                assertEquals(zoned, orgs.findBySlug("acme"))
                assertEquals(listOf(zoned), orgs.listOfUser(ada.id).map { it.org })

                orgs.delete(acme.id, now, orgDeleted(acme.id, ada.id), now + 30.days)
                // Found with no organization set: only through the functions, which list ids and nothing else.
                assertEquals(listOf(acme.id), deletions.deletionsToSettle(now, 10))
                deletions.markSettled(acme.id, now)
                assertEquals(emptyList(), deletions.deletionsToSettle(now, 10))
                assertEquals(emptyList(), deletions.duePurges(now, 10))
                deletions.postpone(acme.id, now + 31.days)
                assertEquals(emptyList(), deletions.duePurges(now + 30.days, 10), "Put off")
                assertEquals(listOf(acme.id), deletions.duePurges(now + 31.days, 10))
                deletions.erase(acme.id, now + 31.days)
                assertEquals(emptyList(), deletions.duePurges(now + 32.days, 10))
                assertFalse(orgs.slugTaken("acme"), "Erased, it gives its short name back")
            }
        }

    @Test
    fun theCrossOrganizationFunctionsPutTheTemporarySchemaLast() =
        serverTest(listOf(IdentityModule, OrgsModule)) { server ->
            val hardened =
                server.driver
                    .fetchAll(
                        "SELECT count(*) AS n FROM pg_proc WHERE proname LIKE 'orgs\\_%' " +
                            "AND proconfig = ARRAY['search_path=pg_catalog, public, pg_temp']"
                    ).getOrThrow()
                    .single()
                    .get("n")
                    .asLong()
            assertEquals(8L, hardened)
        }
}
