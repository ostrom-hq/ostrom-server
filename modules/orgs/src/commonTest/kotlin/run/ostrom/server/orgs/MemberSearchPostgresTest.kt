// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.orgs

import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.switchToTenantRole
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Org
import run.ostrom.server.orgs.infrastructure.MemberSearch
import run.ostrom.server.orgs.infrastructure.MembersRepositoryImpl
import run.ostrom.server.orgs.infrastructure.OrgsRepositoryImpl
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * `orgs_search_members` (OrgsMigrations version 7), the `SECURITY DEFINER` function of the member search: bound to the
 * session's organization, a prefix that is a value, and run by the tenant role alone.
 */
class MemberSearchPostgresTest {
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    private suspend fun TestServer.user(login: String, name: String): User =
        koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, name, login = login),
        )

    private fun org(slug: String) = Org(Uuid.random(), slug, slug.uppercase(), null, null, now)

    private fun JdbcTransaction.search(org: Uuid) =
        MemberSearch(org, "a", null, 10).let { it.select(it.memberId).toList() }

    @Test
    @Suppress("LongMethod") // The function's guarantees, one after the other, read top to bottom.
    fun theSearchKeepsToTheSessionsOrganizationAndReadsThePrefixAsAValue() =
        serverTest(listOf(IdentityModule, OrgsModule)) { server ->
            val orgs = OrgsRepositoryImpl(server.koin.get(), server.koin.get())
            val acme = org("acme")
            val globex = org("globex")
            val owners = listOf(acme, globex).map { org ->
                val owner = server.user("owner-${org.slug}", "Owner")
                orgs.create(org, Member(Uuid.random(), org.id, owner.id, OrgRole.OWNER, now)) { false }
            }
            assertEquals(listOf(null, null), owners)
            // Names with the pattern's own wildcards, and their look-alikes.
            val names = listOf("a_b", "axb", "50% off", "50x off", "ada")
            names.forEachIndexed { index, name ->
                val user = server.user("member$index", name)
                server.database.superuser.execute(
                    "INSERT INTO orgs_members (id, org_id, user_id, role, joined_at) VALUES (gen_random_uuid(), " +
                        "CAST('${acme.id}' AS uuid), CAST('${user.id}' AS uuid), 'member', now() + " +
                        "INTERVAL '${index + 1} minutes')",
                ).getOrThrow()
            }
            val members = MembersRepositoryImpl(server.koin.get())
            suspend fun found(org: Org, prefix: String) =
                members.search(org.id, prefix, null, names.size + 1).size

            assertEquals(1, found(acme, "a_"), "`_` matches only itself")
            assertEquals(1, found(acme, "50%"), "`%` matches only itself")
            assertEquals(3, found(acme, "A"), "a_b, axb and ada, whatever the case")
            assertEquals(0, found(globex, "a"), "Another organization's members are never found")

            // Asked for another organization than the session's, or without one: refused.
            // The function raises, which aborts the transaction: the whole call fails.
            val jdbc = server.koin.get<JdbcDatabase>()
            val other = assertFails { jdbc.inOrg(globex.id) { search(acme.id) } }
            assertTrue("not the session's organization" in other.message.orEmpty(), "Another organization")
            val none = assertFails {
                jdbc.query {
                    switchToTenantRole()
                    search(acme.id)
                }
            }
            assertTrue("not the session's organization" in none.message.orEmpty(), "A session without one")

            // Owned by its own role, its search_path fixed, run by the tenant role only.
            val function = server.database.superuser.fetchAll(
                "SELECT pg_get_userbyid(p.proowner) AS owner, CAST(p.prosecdef AS TEXT) AS definer, " +
                    "array_to_string(p.proconfig, ' ') AS config, " +
                    "CAST(has_function_privilege('ostrom_tenant', p.oid, 'EXECUTE') AS TEXT) AS tenant, " +
                    "CAST(has_function_privilege('ostrom_router', p.oid, 'EXECUTE') AS TEXT) AS router " +
                    "FROM pg_proc p WHERE p.proname = 'orgs_search_members'",
            ).getOrThrow().single()
            assertEquals("ostrom_member_search", function.get("owner").asString())
            assertTrue(function.get("definer").asString() == "true")
            assertTrue("search_path=pg_catalog, public, pg_temp" in function.get("config").asString())
            assertTrue(function.get("tenant").asString() == "true")
            assertFalse(function.get("router").asString() == "true", "No other role: EXECUTE is revoked from PUBLIC")

            // Its role reads three columns of identity_users, never an e-mail or anything else of a user.
            val columns = server.database.superuser.fetchAll(
                "SELECT CAST(has_column_privilege('ostrom_member_search', 'identity_users', 'primary_email', " +
                    "'SELECT') OR has_table_privilege('ostrom_member_search', 'identity_user_emails', 'SELECT') " +
                    "AS TEXT) AS email, " +
                    "CAST(has_table_privilege('ostrom_member_search', 'identity_users', 'SELECT') AS TEXT) AS whole, " +
                    "CAST(has_column_privilege('ostrom_member_search', 'identity_users', 'login', 'SELECT') AS TEXT) " +
                    "AS login",
            ).getOrThrow().single()
            assertFalse(columns.get("email").asString() == "true", "The search's role reads no e-mail")
            assertFalse(
                columns.get("whole").asString() == "true",
                "The search's role reads no whole row of identity_users",
            )
            assertTrue(columns.get("login").asString() == "true")
        }
}
