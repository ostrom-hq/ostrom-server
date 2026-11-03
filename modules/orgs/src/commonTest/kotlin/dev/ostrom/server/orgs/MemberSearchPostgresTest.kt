// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs

import dev.ostrom.server.identity.IdentityModule
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.User
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.database.inOrg
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.Org
import dev.ostrom.server.orgs.infrastructure.MembersRepositoryImpl
import dev.ostrom.server.orgs.infrastructure.OrgsRepositoryImpl
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun search(org: Uuid) =
        Statement.create("SELECT member_id FROM orgs_search_members(:org, 'a', NULL, 10)").bind("org", org.toString())

    @Test
    fun theSearchKeepsToTheSessionsOrganizationAndReadsThePrefixAsAValue() =
        serverTest(listOf(IdentityModule, OrgsModule)) { server ->
            val orgs = OrgsRepositoryImpl(server.driver)
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
            val members = MembersRepositoryImpl(server.driver)
            suspend fun found(org: Org, prefix: String) =
                members.search(org.id, prefix, null, names.size + 1).size

            assertEquals(1, found(acme, "a_"), "`_` matches only itself")
            assertEquals(1, found(acme, "50%"), "`%` matches only itself")
            assertEquals(3, found(acme, "A"), "a_b, axb and ada, whatever the case")
            assertEquals(0, found(globex, "a"), "Another organization's members are never found")

            // Asked for another organization than the session's, or without one: refused.
            val other = server.driver.inOrg(globex.id) { fetchAll(search(acme.id)) }
            assertTrue(other.isFailure, "Another organization than the session's")
            val none = server.driver.transaction {
                execute("SET LOCAL ROLE ostrom_tenant").getOrThrow()
                fetchAll(search(acme.id))
            }
            assertTrue(none.isFailure, "A session without an organization")

            // Owned by its own role, its search_path fixed, run by the tenant role only.
            val function = server.database.superuser.fetchAll(
                "SELECT pg_get_userbyid(p.proowner) AS owner, p.prosecdef AS definer, " +
                    "array_to_string(p.proconfig, ' ') AS config, " +
                    "has_function_privilege('ostrom_tenant', p.oid, 'EXECUTE') AS tenant, " +
                    "has_function_privilege('ostrom_router', p.oid, 'EXECUTE') AS router " +
                    "FROM pg_proc p WHERE p.proname = 'orgs_search_members'",
            ).getOrThrow().single()
            assertEquals("ostrom_member_search", function.get("owner").asString())
            assertTrue(function.get("definer").asBoolean())
            assertTrue("search_path=pg_catalog, public, pg_temp" in function.get("config").asString())
            assertTrue(function.get("tenant").asBoolean())
            assertFalse(function.get("router").asBoolean(), "No other role: EXECUTE is revoked from PUBLIC")

            // Its role reads three columns of identity_users, never an e-mail or anything else of a user.
            val columns = server.database.superuser.fetchAll(
                "SELECT has_column_privilege('ostrom_member_search', 'identity_users', 'primary_email', 'SELECT') " +
                    "OR has_table_privilege('ostrom_member_search', 'identity_user_emails', 'SELECT') AS email, " +
                    "has_table_privilege('ostrom_member_search', 'identity_users', 'SELECT') AS whole, " +
                    "has_column_privilege('ostrom_member_search', 'identity_users', 'login', 'SELECT') AS login",
            ).getOrThrow().single()
            assertFalse(columns.get("email").asBoolean(), "The search's role reads no e-mail")
            assertFalse(columns.get("whole").asBoolean(), "The search's role reads no whole row of identity_users")
            assertTrue(columns.get("login").asBoolean())
        }
}
