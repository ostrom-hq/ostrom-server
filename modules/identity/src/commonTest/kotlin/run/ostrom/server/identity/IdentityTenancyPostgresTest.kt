// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity

import run.ostrom.server.identity.domain.AGENT_SCOPE
import run.ostrom.server.identity.domain.ApiToken
import run.ostrom.server.identity.domain.ApiTokensRepository
import run.ostrom.server.identity.domain.AuditEntry
import run.ostrom.server.identity.domain.AuditEvent
import run.ostrom.server.identity.domain.AuditIntegrity
import run.ostrom.server.identity.domain.AuditLog
import run.ostrom.server.identity.domain.AuditRepository
import run.ostrom.server.identity.domain.AuditVerifier
import run.ostrom.server.identity.domain.OrgAuditQuery
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.TokenKind
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.identity.infrastructure.IdentityApiTokens
import run.ostrom.server.identity.infrastructure.IdentityAuditEvents
import run.ostrom.server.identity.infrastructure.IdentityTransactionalOrgAuditTrail
import run.ostrom.server.identity.infrastructure.TransactionalAuditLog
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.TransactionalOrgAuditTrail
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.PurgeDateRefusedException
import run.ostrom.server.kernel.database.TENANT_ROLE
import run.ostrom.server.kernel.database.confineToOrganization
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.sql.ResultSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
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

    private fun JdbcTransaction.names(table: Table, column: Column<String>) =
        table.select(column).map { it[column] }.sorted()

    private fun JdbcTransaction.tokenNames() = names(IdentityApiTokens, IdentityApiTokens.name)

    private fun JdbcTransaction.actions() = names(IdentityAuditEvents, IdentityAuditEvents.action)

    /** Runs [block] on [pool] as [role] when there is one, confined to [org] when there is one. */
    private suspend fun <T> on(pool: JdbcDatabase, role: String?, org: Uuid?, block: JdbcTransaction.() -> T): T =
        pool.query {
            if (role != null) exec("SET LOCAL ROLE $role")
            if (org != null) confineToOrganization(org)
            block()
        }

    /** Runs [block] as [role], confined to [org] when there is one, as [JdbcDatabase.inOrg] does for the tenant. */
    private suspend fun <T> TestServer.asRole(role: String, org: Uuid?, block: JdbcTransaction.() -> T): T =
        on(koin.get(), role, org, block)

    /** Runs [block] confined to [org], as [JdbcDatabase.inOrg] does. */
    private suspend fun <T> TestServer.inOrg(org: Uuid, block: JdbcTransaction.() -> T): T =
        koin.get<JdbcDatabase>().inOrg(org, block)

    private fun JdbcTransaction.insertAuditRowOf(org: Uuid) = exec(
        "INSERT INTO identity_audit_events (id, chain, sequence, org_id, action, target_type, target_id, " +
            "details, occurred_at, hash, signature, key_id) VALUES (CAST(? AS uuid), ?, 99, CAST(? AS uuid), 'x', " +
            "'t', '1', '{}', now(), 'h', 's', 'k')",
        listOf(TextColumnType() to "${Uuid.random()}", TextColumnType() to "$org", TextColumnType() to "$org"),
    )

    private fun JdbcTransaction.insertTokenRowOf(org: Uuid) = exec(
        "INSERT INTO identity_api_tokens (id, user_id, org_id, name, kind, scopes, hint, agent_session_id, " +
            "secret_hash, created_at) SELECT CAST(? AS uuid), user_id, CAST(? AS uuid), 'forged', 'AGENT', " +
            "'agent:session', 'h', 'run', 'hash-forged', now() FROM identity_api_tokens LIMIT 1",
        listOf(TextColumnType() to "${Uuid.random()}", TextColumnType() to "$org"),
    )

    /** The rows of [sql], each read by [read]. */
    private fun <T> JdbcTransaction.rows(sql: String, read: (ResultSet) -> T): List<T> =
        exec(sql) { rs -> buildList { while (rs.next()) add(read(rs)) } }.orEmpty()

    @Test
    fun everyIdentityTableWithAnOrganizationIsForcedUnderRowLevelSecurity() = serverTest(listOf(IdentityModule)) { s ->
        val flags = s.database.superuserJdbc.query {
            rows(
                "SELECT relname, relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname IN " +
                    "(SELECT table_name FROM information_schema.columns WHERE column_name = 'org_id' " +
                    "AND table_name LIKE 'identity\\_%')",
            ) { it.getString("relname") to (it.getBoolean("relrowsecurity") to it.getBoolean("relforcerowsecurity")) }
        }.toMap()

        assertEquals(setOf("identity_api_tokens", "identity_audit_events"), flags.keys)
        assertTrue(flags.values.all { (enabled, forced) -> enabled && forced }, flags.toString())
    }

    @Test
    fun organizationBNeitherReadsNorWritesTheRowsOfOrganizationA() = serverTest(listOf(IdentityModule)) { s ->
        s.seed()

        // No WHERE clause at all: the policies alone keep A's rows out of B's queries.
        assertEquals(listOf("b"), s.inOrg(orgB) { tokenNames() })
        assertEquals(listOf("b"), s.inOrg(orgB) { actions() })
        assertFailsWith<Exception>("B cannot write an audit event of A") {
            s.inOrg(orgB) { insertAuditRowOf(orgA) }
        }
        assertFailsWith<Exception>("B cannot write a token of A") { s.inOrg(orgB) { insertTokenRowOf(orgA) } }
        assertEquals(listOf("a"), s.inOrg(orgA) { tokenNames() })
        assertEquals(listOf("a"), s.inOrg(orgA) { actions() })
        // The tenant role without an organization sees nothing at all.
        assertEquals(emptyList(), s.asRole(TENANT_ROLE, null) { tokenNames() })
        assertEquals(emptyList(), s.asRole(TENANT_ROLE, null) { actions() })
    }

    /** Runs [block] as the tables' owner (the migrations' role), confined to [org] when there is one. */
    private suspend fun <T> TestServer.asOwner(org: Uuid?, block: JdbcTransaction.() -> T): T =
        on(database.ownerJdbc, null, org, block)

    @Test
    fun theTablesOwnerIsBoundByThePoliciesToo() = serverTest(listOf(IdentityModule)) { s ->
        s.seed()
        // Account-level work (no organization set): the account's own tokens and events only (the seeded user's own
        // creation among them), never an organization's agent token (RLS audit R1). The serving role is held the same.
        assertEquals(listOf("personal"), s.asOwner(null) { tokenNames() })
        assertEquals(listOf("account", "user.created"), s.asOwner(null) { actions() })
        assertEquals(listOf("personal"), s.asServingRole(null) { tokenNames() })
        // Confined to B, the owner is held to B like the tenant role: it reads and changes B's rows only (the audit
        // log, append-only, is changed by nobody: theAuditLogIsAppendOnlyForEveryRole).
        assertEquals(listOf("b"), s.asOwner(orgB) { tokenNames() })
        assertEquals(listOf("b"), s.asOwner(orgB) { actions() })
        s.asOwner(orgB) { exec("UPDATE identity_api_tokens SET name = 'renamed'") }
        assertEquals(listOf("renamed"), s.asOwner(orgB) { tokenNames() })
        assertEquals(listOf("a"), s.asOwner(orgA) { tokenNames() })
        assertFailsWith<Exception> { s.asOwner(orgB) { insertAuditRowOf(orgA) } }
        assertFailsWith<Exception> { s.asOwner(orgB) { insertTokenRowOf(orgA) } }
    }

    /** Runs [block] as the serving role itself (no `SET ROLE`), with `ostrom.org_id` set to [org] when there is one. */
    private suspend fun <T> TestServer.asServingRole(org: Uuid?, block: JdbcTransaction.() -> T): T =
        on(koin.get(), null, org, block)

    /** Runs [block] and checks the append-only trigger refused it, not some other failure. */
    private suspend fun refusedAsAppendOnly(what: String, block: suspend () -> Unit) {
        val error = assertFailsWith<ExposedSQLException>(what) { block() }
        assertTrue("append-only" in error.message.orEmpty(), "$what: ${error.message}")
    }

    /**
     * Appends [entry] through [audit] inside one transaction of the organization's, signed on the head read before it,
     * and runs [then] in that transaction after the append; signed again while another writer moved the head.
     */
    private suspend fun TestServer.appendInTransaction(
        audit: TransactionalAuditLog,
        entry: AuditEntry,
        then: () -> Unit = {},
    ): AuditEvent {
        val orgId = requireNotNull(entry.orgId)
        while (true) {
            val head = inOrg(orgId) { audit.head(this, orgId) }
            val event = audit.sign(head, listOf(entry)).single()
            if (inOrg(orgId) { audit.append(this, orgId, head, listOf(event)).also { if (it) then() } }) return event
        }
    }

    @Test
    fun anOrganizationsActionIsRecordedInTheTransactionThatDoesIt() = serverTest(listOf(IdentityModule)) { s ->
        val first = s.koin.get<AuditLog>().record(AuditEntry(orgA, null, "before", "t", "0"))
        val audit = s.koin.get<TransactionalAuditLog>()

        // The action fails after its event was written: the event goes with it.
        assertFails {
            s.appendInTransaction(audit, AuditEntry(orgA, null, "rolled.back", "t", "1")) { error("the action fails") }
        }
        val kept = s.appendInTransaction(audit, AuditEntry(orgA, null, "kept", "t", "2"))

        assertEquals(2L to first.hash, kept.sequence to kept.previousHash, "Chained after the last kept event")
        assertEquals(listOf("before", "kept"), s.inOrg(orgA) { actions() })
        assertFailsWith<IllegalArgumentException>("Only an organization's action") {
            audit.sign(null, listOf(AuditEntry(null, null, "account", "t", "3")))
        }
    }

    // Another module's change (a cloud session write) journals through the kernel's port, in its own transaction, the
    // same entry as the after-commit trail: the person as the actor, the credential in the details.
    @Test
    fun aModulesChangeIsJournaledInItsTransactionThroughTheKernelPort() = serverTest(listOf(IdentityModule)) { s ->
        val trail = s.koin.get<TransactionalOrgAuditTrail>()
        val ada = Uuid.random()
        val person = AuthenticatedPrincipal("$ada", PrincipalKind.USER, setOf("user:*"))
        fun change(event: String) = OrgAuditRecord(orgA, person, event, "cloud_session", "s", mapOf("owner" to "b"))

        val database = s.koin.get<JdbcDatabase>()
        assertFails {
            trail.recording(database, change("cloud.session.paused")) { append ->
                check(append())
                error("the change fails")
            }
        }
        assertEquals("kept", trail.recording(database, change("cloud.session.created")) { append ->
            check(append())
            "kept"
        })

        val lines = s.koin.get<AuditRepository>().listOrg(orgA, OrgAuditQuery(limit = 10))
        assertEquals(listOf("cloud.session.created"), lines.map { it.event.entry.action })
        val entry = lines.single().event.entry
        assertEquals(ada, entry.actorId)
        assertEquals(mapOf("owner" to "b", "credential" to "api_token"), entry.details)
    }

    // Another writer appends between the head's read and the change's append: the change is rolled back and runs again
    // on the new head, so the chain keeps no gap and no fork, and the change's own writes are not kept twice.
    @Test
    fun aChangeWhoseHeadMovedIsRolledBackAndRunAgainOnTheNewHead() = serverTest(listOf(IdentityModule)) { s ->
        val real = s.koin.get<AuditLog>()
        val database = s.koin.get<JdbcDatabase>()
        var signed = 0
        val moving = object : AuditLog by real {
            override fun sign(entry: AuditEntry): suspend (String?, Long) -> AuditEvent {
                val signature = real.sign(entry)
                return { previousHash, sequence ->
                    if (++signed == 1) real.record(AuditEntry(orgA, null, "meanwhile", "t", "1"))
                    signature(previousHash, sequence)
                }
            }
        }
        val trail = IdentityTransactionalOrgAuditTrail(TransactionalAuditLog(moving))
        val person = AuthenticatedPrincipal("${Uuid.random()}", PrincipalKind.USER, setOf("user:*"))
        var runs = 0
        val record = OrgAuditRecord(orgA, person, "cloud.session.created", "cloud_session", "s")
        val result = trail.recording(database, record) { append ->
            runs += 1
            check(append() == (runs == 2)) { "The first run's head moved: refused; the second chains." }
            runs
        }

        assertEquals(2, result)
        assertEquals(2, signed)
        val lines = s.koin.get<AuditRepository>().listOrg(orgA, OrgAuditQuery(limit = 10))
        assertEquals(listOf("cloud.session.created", "meanwhile"), lines.map { it.event.entry.action })
        val verifier = s.koin.get<AuditVerifier>()
        assertEquals(List(2) { AuditIntegrity.VERIFIED }, lines.map { verifier.integrity(it) })
    }

    // A head that moves every time is given up on after a bounded number of attempts, nothing kept.
    @Test
    fun aChangeWhoseHeadKeepsMovingFailsAfterItsAttempts() = serverTest(listOf(IdentityModule)) { s ->
        val real = s.koin.get<AuditLog>()
        val database = s.koin.get<JdbcDatabase>()
        var signed = 0
        val moving = object : AuditLog by real {
            override fun sign(entry: AuditEntry): suspend (String?, Long) -> AuditEvent {
                val signature = real.sign(entry)
                return { previousHash, sequence ->
                    real.record(AuditEntry(orgA, null, "meanwhile", "t", "${++signed}"))
                    signature(previousHash, sequence)
                }
            }
        }
        val trail = IdentityTransactionalOrgAuditTrail(TransactionalAuditLog(moving))
        val person = AuthenticatedPrincipal("${Uuid.random()}", PrincipalKind.USER, setOf("user:*"))
        assertFailsWith<IllegalStateException> {
            trail.recording(database, OrgAuditRecord(orgA, person, "never", "cloud_session", "s")) { append ->
                append()
            }
        }
        assertEquals(8, signed)
        val lines = s.koin.get<AuditRepository>().listOrg(orgA, OrgAuditQuery(limit = 20))
        val actions = lines.map { it.event.entry.action }
        assertEquals(List(8) { "meanwhile" }, actions)
    }

    // Identity appends in its own transaction (AuditLog.record), the other modules in theirs (TransactionalAuditLog):
    // both take the chain's advisory lock and read its head alike, so one chain written by both, one after the other
    // and at once, has no gap, no fork, and every line verifies.
    @Test
    fun aChainWrittenByBothWritersStaysOneVerifiedChain() = serverTest(listOf(IdentityModule)) { s ->
        val log = s.koin.get<AuditLog>()
        val inTransaction = s.koin.get<TransactionalAuditLog>()
        suspend fun byModule(n: Int) = s.appendInTransaction(inTransaction, AuditEntry(orgA, null, "m$n", "t", "$n"))
        suspend fun byIdentity(n: Int) = log.record(AuditEntry(orgA, null, "i$n", "t", "$n"))

        val alternated = listOf(byModule(1), byIdentity(2), byModule(3), byIdentity(4))
        assertEquals(listOf(1L, 2L, 3L, 4L), alternated.map { it.sequence })
        assertEquals(alternated.dropLast(1).map { it.hash }, alternated.drop(1).map { it.previousHash })
        coroutineScope { (5..24).map { n -> async { if (n % 2 == 0) byIdentity(n) else byModule(n) } }.awaitAll() }

        val lines = s.koin.get<AuditRepository>().listOrg(orgA, OrgAuditQuery(limit = 100))
        assertEquals((24L downTo 1L).toList(), lines.map { it.event.sequence }, "No gap, no sequence twice")
        val verifier = s.koin.get<AuditVerifier>()
        assertEquals(List(24) { AuditIntegrity.VERIFIED }, lines.map { verifier.integrity(it) })
    }

    // The Exposed writer builds (signs) an event between two transactions, never inside one: when another writer
    // appends in between, the event is built again on the new head, and the chain keeps no gap and no fork.
    @Test
    fun anEventWhoseHeadMovedWhileItWasBuiltIsBuiltAgainOnTheNewHead() = serverTest(listOf(IdentityModule)) { s ->
        val log = s.koin.get<AuditLog>()
        val events = s.koin.get<AuditRepository>()
        log.record(AuditEntry(orgA, null, "first", "t", "1"))
        val heads = mutableListOf<Long>()
        val late = events.append(orgA) { previousHash, sequence ->
            heads += sequence
            if (heads.size == 1) log.record(AuditEntry(orgA, null, "meanwhile", "t", "2"))
            log.sign(AuditEntry(orgA, null, "late", "t", "3"))(previousHash, sequence)
        }
        assertEquals(listOf(2L, 3L), heads, "built on the head read first, then on the one another writer appended")
        assertEquals(3L, late.sequence)

        val lines = events.listOrg(orgA, OrgAuditQuery(limit = 10))
        assertEquals(listOf("late", "meanwhile", "first"), lines.map { it.event.entry.action })
        val verifier = s.koin.get<AuditVerifier>()
        assertEquals(List(3) { AuditIntegrity.VERIFIED }, lines.map { verifier.integrity(it) })
    }

    // A module on Exposed signs between two transactions, then appends the signed lines in its own, on the head it
    // read: another writer's append in between makes the append refuse, for the caller to sign again.
    @Test
    fun linesSignedBeforeATransactionAreAppendedOnlyOnTheirHead() = serverTest(listOf(IdentityModule)) { s ->
        val log = s.koin.get<AuditLog>()
        val chain = s.koin.get<TransactionalAuditLog>()
        val database = s.koin.get<JdbcDatabase>()
        fun entry(n: Int) = AuditEntry(orgA, null, "j$n", "t", "$n")
        assertNull(database.inOrg(orgA) { chain.head(this, orgA) }, "An empty chain has no head")

        val first = chain.sign(null, listOf(entry(1), entry(2)))
        assertEquals(listOf(1L, 2L), first.map { it.sequence })
        assertEquals(first[0].hash, first[1].previousHash, "Chained one after the other")
        assertTrue(database.inOrg(orgA) { chain.append(this, orgA, null, first) })
        val head = assertNotNull(database.inOrg(orgA) { chain.head(this, orgA) })
        assertEquals(2L to first[1].hash, head.sequence to head.hash)

        val stale = chain.sign(head, listOf(entry(3)))
        log.record(entry(4))
        assertFalse(database.inOrg(orgA) { chain.append(this, orgA, head, stale) }, "The head moved")
        assertFalse(database.inOrg(orgA) { chain.append(this, orgA, null, first) }, "A chain that is no longer empty")
        val moved = assertNotNull(database.inOrg(orgA) { chain.head(this, orgA) })
        val again = chain.sign(moved, listOf(entry(5)))
        assertTrue(database.inOrg(orgA) { chain.append(this, orgA, moved, again) })
        assertFailsWith<IllegalArgumentException>("Only an organization's action") {
            chain.sign(null, listOf(AuditEntry(null, null, "account", "t", "6")))
        }

        val lines = s.koin.get<AuditRepository>().listOrg(orgA, OrgAuditQuery(limit = 10))
        assertEquals(listOf("j5", "j4", "j2", "j1"), lines.map { it.event.entry.action })
        val verifier = s.koin.get<AuditVerifier>()
        assertEquals(List(4) { AuditIntegrity.VERIFIED }, lines.map { verifier.integrity(it) })
    }

    @Test
    fun theAuditLogIsAppendOnlyForEveryRole() = serverTest(listOf(IdentityModule)) { s ->
        s.seed()
        val rewrite = "UPDATE identity_audit_events SET action = 'forged'"
        val erase = "DELETE FROM identity_audit_events"
        val truncate = "TRUNCATE identity_audit_events"
        // The serving role holds UPDATE and DELETE (default privileges) and sets the organization itself: before the
        // append-only trigger it rewrote and erased A's log, and the account chain outside any organization.
        refusedAsAppendOnly("serving, A, rewrite") { s.asServingRole(orgA) { exec(rewrite) } }
        refusedAsAppendOnly("serving, A, erase") { s.asServingRole(orgA) { exec(erase) } }
        refusedAsAppendOnly("serving, account, rewrite") { s.asServingRole(null) { exec(rewrite) } }
        refusedAsAppendOnly("serving, account, erase") { s.asServingRole(null) { exec(erase) } }
        // A statement that reaches no row is refused all the same: never zero rows in silence.
        val empty = Uuid.random()
        refusedAsAppendOnly("serving, empty org, erase") { s.asServingRole(empty) { exec(erase) } }
        // The owner and a superuser, who own the table or bypass the policies, are refused the same, TRUNCATE too.
        refusedAsAppendOnly("owner, B, erase") { s.asOwner(orgB) { exec(erase) } }
        refusedAsAppendOnly("owner, truncate") { s.asOwner(null) { exec(truncate) } }
        refusedAsAppendOnly("superuser, rewrite") { s.database.superuserJdbc.query { exec(rewrite) } }
        refusedAsAppendOnly("superuser, truncate") { s.database.superuserJdbc.query { exec(truncate) } }
        // Replica mode skips ordinary triggers; this one is enabled ALWAYS.
        refusedAsAppendOnly("superuser, replica, erase") {
            s.database.superuserJdbc.query {
                exec("SET LOCAL session_replication_role = replica")
                exec(erase)
            }
        }

        assertEquals(listOf("a"), s.inOrg(orgA) { actions() })
        assertEquals(listOf("b"), s.inOrg(orgB) { actions() })
        assertEquals(listOf("account", "user.created"), s.asOwner(null) { actions() })
        // Appending is untouched.
        s.inOrg(orgA) { insertAuditRowOf(orgA) }
        assertEquals(listOf("a", "x"), s.inOrg(orgA) { actions() })
        // The catalog keeps the guard: present, and enabled ALWAYS.
        val always = s.database.superuserJdbc.query {
            val trigger = "identity_audit_events_append_only"
            rows("SELECT tgenabled = 'A' AS always FROM pg_trigger WHERE tgname = '$trigger'") {
                it.getBoolean("always")
            }
        }
        assertEquals(listOf(true), always)
    }

    @Test
    fun withoutAnOrganizationTheServingRoleReachesAnOrganizationsTokensOnlyThroughTheRouter() =
        serverTest(listOf(IdentityModule)) { s ->
            s.seed()
            val tokens = s.koin.get<ApiTokensRepository>()
            val at = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

            // No organization set (RLS audit R1): an agent token is neither read, changed nor written by a plain query.
            assertEquals(listOf("personal"), s.asServingRole(null) { tokenNames() })
            val t = IdentityApiTokens
            assertEquals(0, s.asServingRole(null) { t.update({ t.kind eq "AGENT" }) { it[t.name] = "stolen" } })
            assertFailsWith<Exception>("No organization writes no organization's token") {
                s.asServingRole(null) { insertTokenRowOf(orgA) }
            }
            // The three account-level paths go through ostrom_router's functions, and only to the token they name.
            val agentA = assertNotNull(tokens.findBySecretHash("hash-a"))
            assertEquals(orgA to "run-a", agentA.orgId to agentA.agentSessionId)
            assertNull(tokens.findBySecretHash("hash-unknown"))
            tokens.touch(agentA.id, at)
            assertEquals(1L, tokens.revokeOfAgentSession("run-a", at))
            assertEquals(0L, tokens.revokeOfAgentSession("run-a", at), "Revoked once")
            val seenInA = s.inOrg(orgA) { t.select(t.lastUsedAt, t.revokedAt).single() }
            assertTrue(seenInA[t.lastUsedAt] != null && seenInA[t.revokedAt] != null)
            val seenInB = s.inOrg(orgB) { t.select(t.lastUsedAt, t.revokedAt).single() }
            assertTrue(seenInB[t.lastUsedAt] == null && seenInB[t.revokedAt] == null, "B's token is left alone")
        }

    @Test
    fun thePurgeDeletesOnlyTheAgentTokensExpiredBeforeItsDateOfEveryOrganization() =
        serverTest(listOf(IdentityModule)) { s ->
            val ada = VerifiedIdentity(IdentityProvider.GITHUB, "1", null, false, "Ada")
            val user = s.koin.get<ResolveUserUseCase>()(ada)
            val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
            val tokens = s.koin.get<ApiTokensRepository>()
            fun token(name: String, org: Uuid?, kind: TokenKind, expiresAt: Instant) = ApiToken(
                Uuid.random(), user.id, org, name, kind, setOf(AGENT_SCOPE), "osa_x",
                "run-$name".takeIf { org != null }, expiresAt - 1.days, expiresAt, null, null,
            )
            tokens.create(token("old-a", orgA, TokenKind.AGENT, now - 8.days), "hash-old-a")
            tokens.create(token("old-b", orgB, TokenKind.AGENT, now - 9.days), "hash-old-b")
            tokens.create(token("recent", orgA, TokenKind.AGENT, now - 6.days), "hash-recent")
            tokens.create(token("live", orgB, TokenKind.AGENT, now + 1.days), "hash-live")
            tokens.create(token("cli", null, TokenKind.CLI, now - 30.days), "hash-cli")

            assertEquals(2L, tokens.purgeAgentTokens(now - 7.days), "The two agent tokens expired before the date")
            assertEquals(0L, tokens.purgeAgentTokens(now - 7.days), "Nothing left to purge")
            val left = s.database.superuserJdbc.query { tokenNames() }
            assertEquals(listOf("cli", "live", "recent"), left, "Other kinds and agent tokens still recent stay")
        }

    // Audit SERVEUR-3 B3: the purge crosses row-level security for every organization, so the date is bounded twice:
    // the repository refuses one it cannot be given (a 400), and the function itself never goes past the retention.
    @Test
    fun aPurgeNeverDeletesAnAgentTokenStillInItsRetentionAndRefusesADateItCannotBound() =
        serverTest(listOf(IdentityModule)) { s ->
            val ada = VerifiedIdentity(IdentityProvider.GITHUB, "1", null, false, "Ada")
            val user = s.koin.get<ResolveUserUseCase>()(ada)
            val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
            val tokens = s.koin.get<ApiTokensRepository>()
            fun agent(name: String, expiresAt: Instant) = ApiToken(
                Uuid.random(), user.id, orgA, name, TokenKind.AGENT, setOf(AGENT_SCOPE), "osa_x", "run-$name",
                expiresAt - 1.days, expiresAt, null, null,
            )
            tokens.create(agent("old", now - 8.days), "hash-old")
            tokens.create(agent("kept", now - 1.days), "hash-kept")
            suspend fun names() = s.database.superuserJdbc.query { tokenNames() }

            // Straight to the function, as the serving role: today deletes only what is past the 7 days.
            fun JdbcTransaction.purge(date: String) =
                rows("SELECT identity_purge_agent_tokens($date) AS n") { it.getLong("n") }.single()
            assertEquals(1L, s.asServingRole(null) { purge("now()") }, "Only the token expired 8 days ago")
            assertEquals(listOf("kept"), names())
            for (date in listOf("now() + INTERVAL '1 day'", "'infinity'", "NULL")) {
                assertFailsWith<ExposedSQLException>(date) { s.asServingRole(null) { purge(date) } }
            }
            for (date in listOf(now + 1.days, Instant.DISTANT_FUTURE, Instant.DISTANT_PAST)) {
                val refused = assertFailsWith<PurgeDateRefusedException>("$date") { tokens.purgeAgentTokens(date) }
                assertEquals(ErrorKind.INVALID_REQUEST, refused.kind)
            }
            assertEquals(0L, tokens.purgeAgentTokens(now), "Through the repository too, the retention holds")
            assertEquals(listOf("kept"), names())
        }

    @Test
    fun theTokenFunctionsBelongToTheRouterAndPutTheTemporarySchemaLast() = serverTest(listOf(IdentityModule)) { s ->
        val functions = s.asServingRole(null) {
            rows(
                "SELECT p.proname AS v FROM pg_proc p JOIN pg_roles r ON r.oid = p.proowner WHERE p.proname LIKE " +
                    "'identity\\_%' AND p.prosecdef AND r.rolname = 'ostrom_router' " +
                    "AND p.proconfig = ARRAY['search_path=pg_catalog, public, pg_temp'] " +
                    "AND NOT has_function_privilege('public', p.oid, 'EXECUTE')",
            ) { it.getString("v") }
        }.sorted()

        assertEquals(
            listOf(
                "identity_purge_agent_tokens",
                "identity_revoke_agent_session_tokens",
                "identity_token_by_hash",
                "identity_token_touch",
            ),
            functions,
        )
    }
}
