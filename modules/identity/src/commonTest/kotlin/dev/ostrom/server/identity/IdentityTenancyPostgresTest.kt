// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.AGENT_SCOPE
import dev.ostrom.server.identity.domain.ApiToken
import dev.ostrom.server.identity.domain.ApiTokensRepository
import dev.ostrom.server.identity.domain.AuditEntry
import dev.ostrom.server.identity.domain.AuditLog
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.TokenKind
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.kernel.database.TENANT_ROLE
import dev.ostrom.server.kernel.database.inOrg
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Audit S2-01 for the identity module: every identity table that carries an `org_id` (API tokens, audit events)
 * has row-level security enabled and FORCEd, so organization B neither reads nor writes a row of organization A,
 * whether it runs as the tenant role or as the tables' owner.
 */
class IdentityTenancyPostgresTest {
    private val orgA = Uuid.random()
    private val orgB = Uuid.random()

    private suspend fun TestServer.seed() {
        val user = koin.get<ResolveUserUseCase>()(VerifiedIdentity(IdentityProvider.GITHUB, "1", null, false, "Ada"))
        val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
        val tokens = koin.get<ApiTokensRepository>()
        fun agent(name: String, org: Uuid) = ApiToken(
            Uuid.random(), user.id, org, name, TokenKind.AGENT, setOf(AGENT_SCOPE), "osa_x", "run-$name", now,
            now + 1.days, null, null,
        )
        tokens.create(agent("a", orgA), "hash-a")
        tokens.create(agent("b", orgB), "hash-b")
        val personal = ApiToken(
            Uuid.random(), user.id, null, "personal", TokenKind.PERSONAL, setOf("receipts:read"), "osp_x", null, now,
            null, null, null,
        )
        tokens.create(personal, "hash-personal")
        val audit = koin.get<AuditLog>()
        audit.record(AuditEntry(orgA, null, "a", "t", "1"))
        audit.record(AuditEntry(orgB, null, "b", "t", "2"))
        audit.record(AuditEntry(null, user.id, "account", "t", "3"))
    }

    private suspend fun Transaction.names(sql: String) =
        fetchAll(sql).getOrThrow().map { it.get("v").asString() }.sorted()

    private suspend fun Transaction.tokenNames() = names("SELECT name AS v FROM identity_api_tokens")

    private suspend fun Transaction.actions() = names("SELECT action AS v FROM identity_audit_events")

    /** Runs [block] as [role], confined to [org] when there is one, as [inOrg] does for the tenant role. */
    private suspend fun <T> TestServer.asRole(role: String, org: Uuid?, block: suspend Transaction.() -> T): T =
        driver.transaction {
            execute("SET LOCAL ROLE $role").getOrThrow()
            if (org != null) {
                execute(Statement.create("SELECT set_config('ostrom.org_id', :org, true)").bind("org", org.toString()))
                    .getOrThrow()
            }
            block()
        }

    private suspend fun Transaction.insertAuditRowOf(org: Uuid) = execute(
        Statement.create(
            "INSERT INTO identity_audit_events (id, chain, sequence, org_id, action, target_type, target_id, " +
                "details, occurred_at, hash, signature, key_id) VALUES (:id, :chain, 99, :org, 'x', 't', '1', " +
                "'{}', now(), 'h', 's', 'k')",
        ).bind("id", Uuid.random()).bind("chain", org.toString()).bind("org", org),
    ).getOrThrow()

    private suspend fun Transaction.insertTokenRowOf(org: Uuid) = execute(
        Statement.create(
            "INSERT INTO identity_api_tokens (id, user_id, org_id, name, kind, scopes, hint, agent_session_id, " +
                "secret_hash, created_at) SELECT :id, user_id, :org, 'forged', 'AGENT', 'agent:session', 'h', 'run', " +
                "'hash-forged', now() FROM identity_api_tokens LIMIT 1",
        ).bind("id", Uuid.random()).bind("org", org),
    ).getOrThrow()

    @Test
    fun everyIdentityTableWithAnOrganizationIsForcedUnderRowLevelSecurity() = serverTest(listOf(IdentityModule)) { s ->
        val flags = s.driver.fetchAll(
            "SELECT relname, relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname IN " +
                "(SELECT table_name FROM information_schema.columns WHERE column_name = 'org_id' " +
                "AND table_name LIKE 'identity\\_%')",
        ).getOrThrow().associate {
            val enabled = it.get("relrowsecurity").asBoolean()
            it.get("relname").asString() to (enabled to it.get("relforcerowsecurity").asBoolean())
        }

        assertEquals(setOf("identity_api_tokens", "identity_audit_events"), flags.keys)
        assertTrue(flags.values.all { (enabled, forced) -> enabled && forced }, flags.toString())
    }

    @Test
    fun organizationBNeitherReadsNorWritesTheRowsOfOrganizationA() = serverTest(listOf(IdentityModule)) { s ->
        s.seed()

        // No WHERE clause at all: the policies alone keep A's rows out of B's queries.
        assertEquals(listOf("b"), s.driver.inOrg(orgB) { tokenNames() })
        assertEquals(listOf("b"), s.driver.inOrg(orgB) { actions() })
        assertFailsWith<Exception>("B cannot write an audit event of A") {
            s.driver.inOrg(orgB) { insertAuditRowOf(orgA) }
        }
        assertFailsWith<Exception>("B cannot write a token of A") { s.driver.inOrg(orgB) { insertTokenRowOf(orgA) } }
        assertEquals(listOf("a"), s.driver.inOrg(orgA) { tokenNames() })
        assertEquals(listOf("a"), s.driver.inOrg(orgA) { actions() })
        // The tenant role without an organization sees nothing at all.
        assertEquals(emptyList(), s.asRole(TENANT_ROLE, null) { tokenNames() })
        assertEquals(emptyList(), s.asRole(TENANT_ROLE, null) { actions() })
    }

    /** Runs [block] as the tables' owner (the migrations' role), confined to [org] when there is one. */
    private suspend fun <T> TestServer.asOwner(org: Uuid?, block: suspend Transaction.() -> T): T =
        database.owner.transaction {
            if (org != null) {
                execute(Statement.create("SELECT set_config('ostrom.org_id', :org, true)").bind("org", org.toString()))
                    .getOrThrow()
            }
            block()
        }

    @Test
    fun theTablesOwnerIsBoundByThePoliciesToo() = serverTest(listOf(IdentityModule)) { s ->
        s.seed()
        // Account-level work (no organization set): the account's own tokens and events only (the seeded user's own
        // creation among them), never an organization's agent token (RLS audit R1). The serving role is held the same.
        assertEquals(listOf("personal"), s.asOwner(null) { tokenNames() })
        assertEquals(listOf("account", "user.created"), s.asOwner(null) { actions() })
        assertEquals(listOf("personal"), s.driver.transaction { tokenNames() })
        // Confined to B, the owner is held to B like the tenant role: it reads, changes and deletes B's rows only.
        assertEquals(listOf("b"), s.asOwner(orgB) { tokenNames() })
        assertEquals(listOf("b"), s.asOwner(orgB) { actions() })
        s.asOwner(orgB) { execute("UPDATE identity_api_tokens SET name = 'renamed'").getOrThrow() }
        s.asOwner(orgB) { execute("DELETE FROM identity_audit_events").getOrThrow() }
        assertEquals(listOf("renamed"), s.asOwner(orgB) { tokenNames() })
        assertEquals(listOf("a"), s.asOwner(orgA) { tokenNames() })
        assertEquals(listOf("a"), s.driver.inOrg(orgA) { actions() })
        assertEquals(emptyList(), s.driver.inOrg(orgB) { actions() })
        assertFailsWith<Exception> { s.asOwner(orgB) { insertAuditRowOf(orgA) } }
        assertFailsWith<Exception> { s.asOwner(orgB) { insertTokenRowOf(orgA) } }
    }

    @Test
    fun withoutAnOrganizationTheServingRoleReachesAnOrganizationsTokensOnlyThroughTheRouter() =
        serverTest(listOf(IdentityModule)) { s ->
            s.seed()
            val tokens = s.koin.get<ApiTokensRepository>()
            val at = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

            // No organization set (RLS audit R1): an agent token is neither read, changed nor written by a plain query.
            assertEquals(listOf("personal"), s.driver.transaction { tokenNames() })
            assertEquals(0L, s.driver.execute("UPDATE identity_api_tokens SET name = 'stolen' WHERE kind = 'AGENT'")
                .getOrThrow())
            assertFailsWith<Exception>("No organization writes no organization's token") {
                s.driver.transaction { insertTokenRowOf(orgA) }
            }
            // The three account-level paths go through ostrom_router's functions, and only to the token they name.
            val agentA = assertNotNull(tokens.findBySecretHash("hash-a"))
            assertEquals(orgA to "run-a", agentA.orgId to agentA.agentSessionId)
            assertNull(tokens.findBySecretHash("hash-unknown"))
            tokens.touch(agentA.id, at)
            assertEquals(1L, tokens.revokeOfAgentSession("run-a", at))
            assertEquals(0L, tokens.revokeOfAgentSession("run-a", at), "Revoked once")
            val seenInA = s.driver.inOrg(orgA) {
                fetchAll("SELECT name AS v, last_used_at IS NOT NULL AS used, revoked_at IS NOT NULL AS revoked " +
                    "FROM identity_api_tokens").getOrThrow().single()
            }
            assertTrue(seenInA.get("used").asBoolean() && seenInA.get("revoked").asBoolean())
            val seenInB = s.driver.inOrg(orgB) {
                fetchAll("SELECT last_used_at IS NULL AND revoked_at IS NULL AS untouched FROM identity_api_tokens")
                    .getOrThrow().single()
            }
            assertTrue(seenInB.get("untouched").asBoolean(), "B's token is left alone")
        }

    @Test
    fun theTokenFunctionsBelongToTheRouterAndPutTheTemporarySchemaLast() = serverTest(listOf(IdentityModule)) { s ->
        val functions = s.driver.fetchAll(
            "SELECT p.proname AS v FROM pg_proc p JOIN pg_roles r ON r.oid = p.proowner WHERE p.proname LIKE " +
                "'identity\\_%' AND p.prosecdef AND r.rolname = 'ostrom_router' " +
                "AND p.proconfig = ARRAY['search_path=pg_catalog, public, pg_temp'] " +
                "AND NOT has_function_privilege('public', p.oid, 'EXECUTE')",
        ).getOrThrow().map { it.get("v").asString() }.sorted()

        assertEquals(
            listOf("identity_revoke_agent_session_tokens", "identity_token_by_hash", "identity_token_touch"),
            functions,
        )
    }
}
