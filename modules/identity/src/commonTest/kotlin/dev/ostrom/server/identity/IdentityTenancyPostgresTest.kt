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
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun theTablesOwnerIsBoundByThePoliciesToo() = serverTest(listOf(IdentityModule)) { s ->
        s.seed()
        // CI connects as a superuser, which no policy binds: the check runs as a plain role that owns the tables, the
        // way the server's own role does in production.
        val owner = "s2_owner_" + Random.nextLong().toULong().toString(16)
        s.driver.execute("CREATE ROLE $owner NOLOGIN").getOrThrow()
        try {
            s.driver.execute("GRANT $owner TO CURRENT_USER").getOrThrow()
            s.driver.execute("ALTER TABLE identity_api_tokens OWNER TO $owner").getOrThrow()
            s.driver.execute("ALTER TABLE identity_audit_events OWNER TO $owner").getOrThrow()

            // Account-level work (no organization set): every token, to authenticate one by its hash; account events
            // (the seeded user's own creation among them).
            assertEquals(listOf("a", "b"), s.asRole(owner, null) { tokenNames() })
            assertEquals(listOf("account", "user.created"), s.asRole(owner, null) { actions() })
            // Confined to B, the owner is held to B like the tenant role: it reads, changes and deletes B's rows only.
            assertEquals(listOf("b"), s.asRole(owner, orgB) { tokenNames() })
            assertEquals(listOf("b"), s.asRole(owner, orgB) { actions() })
            s.asRole(owner, orgB) { execute("UPDATE identity_api_tokens SET name = 'renamed'").getOrThrow() }
            s.asRole(owner, orgB) { execute("DELETE FROM identity_audit_events").getOrThrow() }
            assertEquals(listOf("a", "renamed"), s.asRole(owner, null) { tokenNames() })
            assertEquals(listOf("a"), s.driver.inOrg(orgA) { actions() })
            assertEquals(emptyList(), s.driver.inOrg(orgB) { actions() })
            assertFailsWith<Exception> { s.asRole(owner, orgB) { insertAuditRowOf(orgA) } }
            assertFailsWith<Exception> { s.asRole(owner, orgB) { insertTokenRowOf(orgA) } }
        } finally {
            s.driver.execute("ALTER TABLE identity_api_tokens OWNER TO CURRENT_USER").getOrThrow()
            s.driver.execute("ALTER TABLE identity_audit_events OWNER TO CURRENT_USER").getOrThrow()
            // Roles outlive the test database: the one made here leaves nothing behind.
            s.driver.execute("DROP OWNED BY $owner").getOrThrow()
            s.driver.execute("DROP ROLE $owner").getOrThrow()
        }
    }
}
