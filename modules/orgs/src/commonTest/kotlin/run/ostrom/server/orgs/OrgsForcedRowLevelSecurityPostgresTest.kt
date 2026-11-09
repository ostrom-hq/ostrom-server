// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.orgs

import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.orgs.domain.Invitation
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Membership
import run.ostrom.server.orgs.domain.Org
import run.ostrom.server.orgs.infrastructure.InvitationsRepositoryImpl
import run.ostrom.server.orgs.infrastructure.OrgsRepositoryImpl
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The organizations, their members and their invitations under forced row-level security (RLS audit R5), like every
 * other tenant table: a request sees and changes only the rows of the organization its transaction names, and what
 * crosses organizations (a user's organizations, an invitation found by its link before its holder is a member) goes
 * through the `ostrom_router` functions. The tables' owner is bound the same way: the router's policies hold only
 * inside the router's functions (RLS audit R9).
 */
class OrgsForcedRowLevelSecurityPostgresTest {
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    private suspend fun TestServer.user(name: String): User =
        koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, name, "$name@example.com", true, name, login = name),
        )

    private fun org(slug: String) = Org(Uuid.random(), slug, slug.uppercase(), null, null, now)

    private suspend fun Driver.counts(orgId: Uuid?): List<Long> = transaction {
        orgId?.let {
            fetchAll(Statement.create("SELECT set_config('ostrom.org_id', :org, true)").bind("org", it.toString()))
                .getOrThrow()
        }
        TABLES.map { table -> fetchAll("SELECT COUNT(*) AS n FROM $table").getOrThrow().single().get("n").asLong() }
    }

    @Test
    fun aRequestSeesAndChangesOnlyTheOrganizationItsTransactionNames() =
        serverTest(listOf(IdentityModule, OrgsModule)) { server ->
            val ada = server.user("ada")
            val bob = server.user("bob")
            val orgs = OrgsRepositoryImpl(server.driver, server.koin.get())
            val acme = org("acme")
            val adaOwner = Member(Uuid.random(), acme.id, ada.id, OrgRole.OWNER, now)
            assertNull(orgs.create(acme, adaOwner) { false })
            val globex = org("globex")
            assertNull(orgs.create(globex, Member(Uuid.random(), globex.id, bob.id, OrgRole.OWNER, now)) { false })
            val invitation =
                Invitation(Uuid.random(), acme.id, null, OrgRole.MEMBER, adaOwner.id, now, now + 7.days, 1, 0, null)
            InvitationsRepositoryImpl(server.driver).create(invitation, "hash-acme")

            // Row-level security is forced: it binds the tables' owner too (docs/tenancy.md).
            val forced = server.database.superuser.fetchAll(
                "SELECT relname FROM pg_class WHERE relname IN ('orgs_organizations', 'orgs_members', " +
                    "'orgs_invitations') AND relrowsecurity AND relforcerowsecurity ORDER BY relname",
            ).getOrThrow().map { it.get("relname").asString() }
            assertEquals(TABLES.sorted(), forced)

            // The serving role, the path of every request.
            val serving = server.driver
            assertEquals(listOf(0L, 0L, 0L), serving.counts(null), "Nothing without an organization")
            assertEquals(listOf(1L, 1L, 1L), serving.counts(acme.id), "Acme's row, member and invitation")
            assertEquals(listOf(1L, 1L, 0L), serving.counts(globex.id), "Globex's own, nothing of Acme's")
            val renamed = serving.transaction {
                val confine = Statement.create("SELECT set_config('ostrom.org_id', :org, true)")
                fetchAll(confine.bind("org", globex.id.toString())).getOrThrow()
                val rename = Statement.create("UPDATE orgs_organizations SET name = 'Stolen' WHERE id = :id")
                execute(rename.bind("id", acme.id)).getOrThrow()
            }
            assertEquals(0L, renamed, "Globex cannot rename Acme")
            assertEquals(acme, orgs.get(acme.id))

            // The owner, a member of ostrom_router to hand it the functions, is bound like a request (RLS audit R9):
            // the router's policies hold only inside its functions (`current_user = 'ostrom_router'`).
            val owner = server.database.owner
            assertEquals(listOf(0L, 0L, 0L), owner.counts(null), "The owner sees nothing without an organization")
            assertEquals(listOf(1L, 1L, 1L), owner.counts(acme.id), "The owner sees Acme's rows only in Acme")

            // The paths across organizations still work, through the ostrom_router functions.
            assertEquals(listOf(Membership(acme, adaOwner)), orgs.listOfUser(ada.id))
            assertEquals(invitation, InvitationsRepositoryImpl(server.driver).findLiveByTokenHash("hash-acme", now))
        }

    private companion object {
        val TABLES = listOf("orgs_organizations", "orgs_members", "orgs_invitations")
    }
}
