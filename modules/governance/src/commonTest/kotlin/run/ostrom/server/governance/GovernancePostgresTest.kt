// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.governance

import run.ostrom.server.governance.domain.ConfirmationOutcome
import run.ostrom.server.governance.domain.ConfirmationPeople
import run.ostrom.server.governance.domain.ConfirmationRules
import run.ostrom.server.governance.domain.ConfirmationStatus
import run.ostrom.server.governance.domain.ConfirmationsRepository
import run.ostrom.server.governance.domain.ExpireConfirmationsUseCase
import run.ostrom.server.governance.domain.PersonName
import run.ostrom.server.governance.fakes.FakeChange
import run.ostrom.server.governance.infrastructure.PostgresConfirmationsRepository
import run.ostrom.server.governance.presentation.AnswerConfirmationRequest
import run.ostrom.server.governance.presentation.ConfirmationDto
import run.ostrom.server.governance.presentation.ConfirmationTokenDto
import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.identity.domain.AuditRepository
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.GovernanceChange
import run.ostrom.server.kernel.auth.GovernanceConfirmationPending
import run.ostrom.server.kernel.auth.GovernanceScopes
import run.ostrom.server.kernel.auth.GovernanceWrite
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.confirmsByConsole
import run.ostrom.server.kernel.auth.holdForConfirmation
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The governance confirmations end to end on PostgreSQL, as the serving role: a token's governance write is held and
 * writes nothing, an owner confirms it in the console with a fresh sign-in and the write is made once, and nobody
 * outside the organization sees it. A test module stands for the rules, compiler and gateway modules.
 */
class GovernancePostgresTest {
    private val orgA = Uuid.random()
    private val orgB = Uuid.random()
    private val change = FakeChange()

    /** The real time, moved forward by the test. */
    private object Time : Clock {
        var offset: Duration = Duration.ZERO

        override fun now(): Instant = Clock.System.now() + offset
    }

    // token -> (subject, organization, role, channel, sign-in age); subjects are filled with real users' ids.
    private data class Person(
        val name: String,
        val org: Uuid,
        val role: OrgRole,
        val browser: Boolean,
        val age: Int = 0,
        val tokenNamed: Boolean = true,
        val personalToken: String? = null,
    )

    private val people = mapOf(
        "ada-token" to Person("ada", orgA, OrgRole.ADMIN, browser = false),
        // A personal token made in the console, which holds the governance scopes too.
        "ada-personal" to Person("ada", orgA, OrgRole.ADMIN, browser = false, personalToken = "deploy bot"),
        "olga-browser" to Person("olga", orgA, OrgRole.OWNER, browser = true),
        "olga-stale" to Person("olga", orgA, OrgRole.OWNER, browser = true, age = 10),
        "mia-browser" to Person("mia", orgA, OrgRole.MEMBER, browser = true),
        "mia-token" to Person("mia", orgA, OrgRole.MEMBER, browser = false),
        "bob-browser" to Person("bob", orgB, OrgRole.OWNER, browser = true),
        "bob-token" to Person("bob", orgB, OrgRole.OWNER, browser = false),
        // A token the identity module does not name (no token id).
        "bob-key" to Person("bob", orgB, OrgRole.OWNER, browser = false, tokenNamed = false),
    )
    private val users = mutableMapOf<String, String>()

    private fun subjectOf(name: String) = users[name] ?: name

    private fun personOf(subject: String) = people.values.first { subjectOf(it.name) == subject }

    /** A governance route of another module: a token's write is held, the console's made at once. */
    private inner class GovernedModule : ServerModule {
        override val name: String = "governed"
        override val koinModule: Module = module { single<GovernanceChange>(named("governed")) { change } }

        override fun routes(route: Route, koin: Koin) {
            route.authenticated {
                put("/governed") {
                    val principal = call.requirePrincipal()
                    val body = call.receive<JsonObject>()
                    if (principal.confirmsByConsole) {
                        val write = GovernanceWrite(FakeChange.KIND, body)
                        val org = personOf(principal.subjectId).org
                        call.holdForConfirmation(org, GovernanceScopes.GATES_POLICY, write)
                    } else {
                        change.current = body["to"]
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }
    }

    private val overrides = module {
        single<Clock> { Time }
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String): AuthenticatedPrincipal? = people[token]?.let {
                    AuthenticatedPrincipal(
                        subjectOf(it.name),
                        PrincipalKind.USER,
                        if (it.browser) setOf("user:*") else GovernanceScopes.ALL,
                        credentialId = token.takeUnless { _ -> it.browser || !it.tokenNamed },
                        channel = if (it.browser) PrincipalChannel.BROWSER else PrincipalChannel.API_TOKEN,
                        authenticatedAt = Time.now() - it.age.minutes,
                        personalTokenName = it.personalToken,
                    )
                }
            }
        }
        single<PrincipalOrgResolver> {
            object : PrincipalOrgResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
                    people.values.firstOrNull { subjectOf(it.name) == principal.subjectId }?.let {
                        OrgPrincipal(it.org, MEMBER_IDS.getValue(it.name), it.role)
                    }
            }
        }
    }

    private fun governanceTest(block: suspend (TestServer) -> Unit) =
        serverTest(listOf(IdentityModule, GovernanceModule, GovernedModule()), overrides) { server ->
            Time.offset = Duration.ZERO
            change.current = JsonPrimitive("sole-approver")
            for (name in listOf("ada", "olga", "mia")) {
                val user = server.koin.get<ResolveUserUseCase>()(
                    VerifiedIdentity(
                        IdentityProvider.GITHUB,
                        name,
                        "$name@example.com",
                        true,
                        displayName = name.uppercase(),
                        login = name,
                    ),
                )
                users[name] = user.id.toString()
            }
            block(server)
        }

    private suspend fun TestServer.ask(token: String, to: String): HttpResponse = client.put("/governed") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(JsonObject(mapOf("to" to JsonPrimitive(to))))
    }

    private suspend fun TestServer.read(token: String?, id: String, wait: Int? = null) =
        client.get("/api/v1/confirmations/$id" + (wait?.let { "?wait=$it" } ?: "")) { token?.let { bearerAuth(it) } }

    private suspend fun TestServer.answer(token: String?, id: String, answer: String) =
        client.post("/api/v1/confirmations/$id/answer") {
            token?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(AnswerConfirmationRequest(answer))
        }

    private suspend fun HttpResponse.type(): String = bodyAsText().substringAfter("\"type\":\"").substringBefore('"')

    @Test
    fun anAdminsTokenWritesNothingUntilAnOwnerConfirmsOnceWithAFreshSignIn() = governanceTest { server ->
        val asked = server.ask("ada-token", "never")
        assertEquals(HttpStatusCode.Accepted, asked.status, asked.bodyAsText())
        val pending = asked.body<GovernanceConfirmationPending>()
        assertTrue(pending.confirmUrl.endsWith("/confirm/${pending.confirmationId}"), pending.confirmUrl)
        assertEquals(JsonPrimitive("sole-approver"), change.current, "Nothing written")
        val id = pending.confirmationId
        assertEquals(id, server.ask("ada-token", "never").body<GovernanceConfirmationPending>().confirmationId)

        val shown = server.read("ada-token", id).body<ConfirmationDto>()
        assertEquals("pending", shown.status)
        assertEquals("gates.policy", shown.kind)
        assertEquals("PUT /governed gates:policy", "${shown.method} ${shown.path} ${shown.scope}")
        assertEquals(JsonPrimitive("sole-approver"), shown.before)
        assertEquals(JsonPrimitive("never"), shown.after)
        assertEquals("admin", shown.credential)
        assertEquals(MEMBER_IDS.getValue("ada").toString(), shown.requestedBy.memberId)
        assertEquals("ada" to "ADA", shown.requestedBy.login to shown.requestedBy.name)
        assertEquals(pending.expiresAt, shown.expiresAt)

        val byToken = server.answer("ada-token", id, "confirm")
        assertEquals(HttpStatusCode.Forbidden, byToken.status, "A token never answers")
        assertEquals(HttpStatusCode.Forbidden, server.answer("mia-browser", id, "confirm").status, "A member")
        val stale = server.answer("olga-stale", id, "confirm")
        assertEquals(HttpStatusCode.Forbidden, stale.status)
        assertEquals("step_up_required", stale.type())
        assertEquals(JsonPrimitive("sole-approver"), change.current)

        val confirmed = server.answer("olga-browser", id, "confirm")
        assertEquals(HttpStatusCode.OK, confirmed.status, confirmed.bodyAsText())
        val done = confirmed.body<ConfirmationDto>()
        assertEquals("confirmed", done.status)
        assertEquals(JsonPrimitive("never"), done.result)
        assertEquals("olga", done.answeredBy?.login)
        assertEquals(JsonPrimitive("never"), change.current, "Written as Olga's decision")
        assertEquals(users.getValue("olga"), change.confirmers.single().principal.subjectId)
        val again = server.answer("olga-browser", id, "confirm")
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("confirmation_closed", again.type())
        assertEquals("confirmed", server.read("ada-token", id, wait = 5).body<ConfirmationDto>().status, "No wait")

        val events = server.koin.get<AuditRepository>().list(orgA, null, null, 50).map { it.entry }
        val steps = events.filter { it.targetId == id }.map { it.action }.reversed()
        assertEquals(listOf("governance.confirmation.created", "governance.confirmation.confirmed"), steps)
        val created = events.first { it.action == "governance.confirmation.created" }
        assertEquals(Uuid.parse(users.getValue("ada")), created.actorId)
        assertEquals("ada-token", created.details["token"])
        assertEquals("PUT /governed", created.details["request"])
    }

    @Test
    fun aPersonalTokensConfirmationNamesItsTokenAndAnAdminTokensDoesNot() = governanceTest { server ->
        val id = server.ask("ada-personal", "never").body<GovernanceConfirmationPending>().confirmationId
        val shown = server.read("olga-browser", id).body<ConfirmationDto>()
        assertEquals("api_token", shown.credential)
        assertEquals(ConfirmationTokenDto("ada-personal", "deploy bot"), shown.token)
        assertEquals(shown, server.read("ada-personal", id).body<ConfirmationDto>(), "Its requester reads the same")

        val admin = server.ask("ada-token", "everyone").body<GovernanceConfirmationPending>().confirmationId
        val byAdmin = server.read("olga-browser", admin).body<ConfirmationDto>()
        assertEquals("admin" to null, byAdmin.credential to byAdmin.token)

        val events = server.koin.get<AuditRepository>().list(orgA, null, null, 50).map { it.entry }
        val created = events.first { it.targetId == id && it.action == "governance.confirmation.created" }
        assertEquals("api_token" to "deploy bot", created.details["credential"] to created.details["tokenName"])
    }

    @Test
    fun aRefusalAnExpiryAndAStaleChangeWriteNothing() = governanceTest { server ->
        val refused = server.ask("ada-token", "never").body<GovernanceConfirmationPending>().confirmationId
        assertEquals("refused", server.answer("olga-stale", refused, "refuse").body<ConfirmationDto>().status)
        assertEquals(HttpStatusCode.BadRequest, server.answer("olga-browser", refused, "yes").status)

        val late = server.ask("ada-token", "never").body<GovernanceConfirmationPending>().confirmationId
        Time.offset = 16.minutes
        val gone = server.answer("olga-browser", late, "confirm")
        assertEquals(HttpStatusCode.Gone, gone.status)
        assertEquals("confirmation_expired", gone.type())
        assertEquals("expired", server.read("ada-token", late).body<ConfirmationDto>().status)

        val moved = server.ask("ada-token", "never").body<GovernanceConfirmationPending>().confirmationId
        assertEquals(HttpStatusCode.NoContent, server.ask("olga-browser", "always").status, "The console writes")
        val conflict = server.answer("olga-browser", moved, "confirm")
        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertEquals("confirmation_stale", conflict.type())
        val failed = server.read("olga-browser", moved).body<ConfirmationDto>()
        assertEquals("failed", failed.status)
        assertEquals("confirmation_stale", failed.error?.type)
        assertEquals(JsonPrimitive("always"), change.current)
        val actions = server.koin.get<AuditRepository>().list(orgA, null, null, 50).map { it.entry.action }.toSet()
        val expected = listOf("created", "refused", "expired", "failed").map { "governance.confirmation.$it" }
        assertTrue(actions.containsAll(expected), "$actions")
    }

    @Test
    fun aChangeFromNothingIsShownAsNothingAndOneThatFailsOnTheServerIsKeptAsFailed() = governanceTest { server ->
        // A setting never chosen, asked to be nothing, by a token without an id, of a person without a name.
        change.current = null
        val empty = server.client.put("/governed") {
            bearerAuth("bob-key")
            contentType(ContentType.Application.Json)
            setBody(JsonObject(emptyMap()))
        }
        val id = empty.body<GovernanceConfirmationPending>().confirmationId
        val shown = server.read("bob-browser", id).body<ConfirmationDto>()
        assertEquals(JsonNull to JsonNull, shown.before to shown.after)
        assertEquals(null to null, shown.requestedBy.login to shown.requestedBy.name)
        assertEquals("confirmed", server.answer("bob-browser", id, "confirm").body<ConfirmationDto>().status)
        assertNull(change.current)
        val created = server.koin.get<AuditRepository>().list(orgB, null, null, 50).map { it.entry }
            .first { it.targetId == id && it.action == "governance.confirmation.created" }
        assertNull(created.details["token"], "No token to name")

        // The write fails for a reason the server does not show: kept as failed, never read as written.
        val crashing = server.ask("bob-token", "never").body<GovernanceConfirmationPending>().confirmationId
        change.refuseApply = IllegalStateException("database")
        assertEquals(HttpStatusCode.InternalServerError, server.answer("bob-browser", crashing, "confirm").status)
        val failed = server.read("bob-browser", crashing).body<ConfirmationDto>()
        assertEquals("failed" to "internal_error", failed.status to failed.error?.type)
        assertNull(change.current)
    }

    @Test
    fun aMemberAsksNothingAndAnotherOrganizationSeesNothing() = governanceTest { server ->
        assertEquals(HttpStatusCode.Forbidden, server.ask("mia-token", "never").status)
        val id = server.ask("ada-token", "never").body<GovernanceConfirmationPending>().confirmationId
        assertEquals(HttpStatusCode.NotFound, server.read("mia-token", id).status, "Neither theirs nor a manager")
        assertEquals(HttpStatusCode.NotFound, server.read("bob-browser", id).status)
        assertEquals(HttpStatusCode.NotFound, server.answer("bob-browser", id, "confirm").status)
        assertEquals(HttpStatusCode.NotFound, server.read("olga-browser", "gcf_unknown").status)
        assertEquals(HttpStatusCode.Unauthorized, server.read(null, id).status)
        assertEquals(HttpStatusCode.Unauthorized, server.answer(null, id, "confirm").status)
        val repository = server.koin.get<ConfirmationsRepository>()
        assertNull(repository.get(orgB, id), "Row level security")
        val unscoped = server.driver.fetchAll("SELECT id FROM governance_confirmations").getOrThrow()
        assertTrue(unscoped.toList().isEmpty(), "No organization set: no row")
        assertEquals(JsonPrimitive("sole-approver"), change.current)
    }

    @Test
    fun theSweepExpiresTheOverdueConfirmationsOfEveryOrganization() = governanceTest { server ->
        val a = server.ask("ada-token", "never").body<GovernanceConfirmationPending>().confirmationId
        val b = server.ask("bob-token", "never").body<GovernanceConfirmationPending>().confirmationId
        val expire = server.koin.get<ExpireConfirmationsUseCase>()
        assertEquals(0, expire.everywhere())
        Time.offset = 15.minutes
        assertEquals(2, expire.everywhere())
        val repository = server.koin.get<ConfirmationsRepository>()
        assertEquals("expired", repository.get(orgA, a)?.status?.wire)
        assertEquals("expired", repository.get(orgB, b)?.status?.wire)
        assertEquals(0, expire.everywhere(), "Each once")
        val people = server.koin.get<ConfirmationPeople>()
        assertEquals(PersonName("mia", "MIA"), people.nameOf(users.getValue("mia")))
        assertNull(people.nameOf(Uuid.random().toString()))
        assertNull(people.nameOf("bob"))
    }

    @Test
    fun aWaitingReadAnswersAtTheEndOfItsWait() = governanceTest { server ->
        val id = server.ask("ada-token", "never").body<GovernanceConfirmationPending>().confirmationId
        val before = Clock.System.now()
        assertEquals("pending", server.read("ada-token", id, wait = 1).body<ConfirmationDto>().status)
        assertTrue(Clock.System.now() - before >= 1.seconds, "Waited")
    }

    @Test
    fun aConfirmedWriteReadsPendingUntilItsResultIsWrittenEvenNone() = governanceTest { server ->
        val id = server.ask("ada-token", "never").body<GovernanceConfirmationPending>().confirmationId
        val repository = server.koin.get<ConfirmationsRepository>()
        val asked = checkNotNull(repository.get(orgA, id))
        val outcome = ConfirmationOutcome(ConfirmationStatus.CONFIRMED, asked.requestedBy, Clock.System.now())
        assertTrue(repository.answer(orgA, id, outcome))
        // confirmed, the write being made: the CLI that waits must not read it without its result
        assertEquals(false, repository.get(orgA, id)?.settled)
        assertEquals("pending", server.read("ada-token", id).body<ConfirmationDto>().status)
        // a write that returns nothing (a removal) is settled all the same
        repository.settle(orgA, id, null, null)
        val settled = checkNotNull(repository.get(orgA, id))
        assertEquals(true to null, settled.settled to settled.result)
        assertEquals("confirmed", server.read("ada-token", id).body<ConfirmationDto>().status)
    }

    @Test
    fun theSweepFailsAConfirmedWriteWhoseResultWasLost() = governanceTest { server ->
        // decision of 14/11/2026: confirmed with no result 5 minutes past its expiry is failed, result_lost
        val id = server.ask("ada-token", "never").body<GovernanceConfirmationPending>().confirmationId
        val repository = server.koin.get<ConfirmationsRepository>()
        val asked = checkNotNull(repository.get(orgA, id))
        val outcome = ConfirmationOutcome(ConfirmationStatus.CONFIRMED, asked.requestedBy, Time.now())
        assertTrue(repository.answer(orgA, id, outcome))
        val expire = server.koin.get<ExpireConfirmationsUseCase>()
        Time.offset = 19.minutes
        expire.everywhere()
        assertEquals("pending", server.read("ada-token", id).body<ConfirmationDto>().status)
        Time.offset = 21.minutes
        expire.everywhere()
        val failed = server.read("ada-token", id).body<ConfirmationDto>()
        assertEquals("failed" to "result_lost", failed.status to failed.error?.type)
        assertEquals(ConfirmationRules.RESULT_LOST_MESSAGE, failed.error?.message)
        // a result that comes after changes nothing
        repository.settle(orgA, id, JsonPrimitive("never"), null)
        assertEquals(ConfirmationStatus.FAILED to null, repository.get(orgA, id)?.let { it.status to it.result })
        assertEquals(0, expire.everywhere(), "Once: nothing left to sweep there")
        val actions = server.koin.get<AuditRepository>().list(orgA, null, null, 50).map { it.entry.action }
        assertTrue("governance.confirmation.failed" in actions, "$actions")
    }

    @Test
    fun theQueriesUseTheirIndexes() = governanceTest { server ->
        for ((org, count) in listOf(orgB to 6_000, orgA to 60)) {
            server.driver.withOrganization(org) { tx ->
                tx.execute(
                    "INSERT INTO governance_confirmations (org_id, id, kind, scope, method, path, payload, summary, " +
                        "before_json, after_json, requested_by_user, requested_by_member, credential, status, " +
                        "result_json, created_at, expires_at) SELECT CAST('$org' AS uuid), " +
                        "'gcf_' || md5(random() || '' || i), " +
                        "'gates.policy', 'gates.policy', 'PUT', '/p', '{}', 's', 'null', 'null', 'u' || (i % 50), " +
                        "gen_random_uuid(), 'admin', CASE WHEN i % 20 = 0 THEN 'pending' ELSE 'confirmed' END, " +
                        // a confirmed write has its result, but for the rare one whose server stopped (result_lost)
                        "CASE WHEN i % 20 = 0 OR i % 500 = 1 THEN NULL ELSE 'null' END, " +
                        "now() - i * INTERVAL '1 minute', now() - i * INTERVAL '1 minute' + INTERVAL '15 minutes' " +
                        "FROM generate_series(1, $count) AS i",
                ).getOrThrow()
            }
        }
        server.database.superuser.execute("VACUUM ANALYZE governance_confirmations").getOrThrow()
        val now = Time.now()
        val plans = linkedMapOf(
            "one confirmation" to server.explain(PostgresConfirmationsRepository.GET) { bind("id", "gcf_x") },
            "a person's waiting ones" to server.explain(PostgresConfirmationsRepository.WAITING) {
                bind("user", "u7").bind("now", now)
            },
            "the overdue ones" to server.explain(PostgresConfirmationsRepository.EXPIRE, analyze = false) {
                bind("now", now).bind("lost_before", now - ConfirmationRules.RESULT_GRACE)
                    .bind("lost_type", ConfirmationRules.RESULT_LOST).bind("lost_message", "m")
            },
            "an answer" to server.explain(PostgresConfirmationsRepository.ANSWER, analyze = false) {
                bind("id", "gcf_x").bind("status", "confirmed").bind("by_user", "u").bind("by_member", Uuid.random())
                    .bind("at", now).bindNull("error_type", String::class).bindNull("error_message", String::class)
            },
            "a result" to server.explain(PostgresConfirmationsRepository.SETTLE_RESULT, analyze = false) {
                bind("id", "gcf_x").bind("result", "null")
            },
            "a failure" to server.explain(PostgresConfirmationsRepository.SETTLE_ERROR, analyze = false) {
                bind("id", "gcf_x").bind("error_type", "internal_error").bind("error_message", "m")
            },
        )
        val sweep = server.database.superuser.fetchAll(
            "EXPLAIN (ANALYZE, BUFFERS) SELECT DISTINCT c.org_id FROM governance_confirmations c " +
                "WHERE c.status = 'pending' AND c.expires_at <= now() LIMIT 100",
        ).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
        plans["the sweep's organizations (the router function's query)"] = sweep
        val lost = server.database.superuser.fetchAll(
            "EXPLAIN (ANALYZE, BUFFERS) SELECT DISTINCT c.org_id FROM governance_confirmations c " +
                "WHERE c.status = 'confirmed' AND c.result_json IS NULL AND c.expires_at <= now() LIMIT 100",
        ).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
        plans["the sweep's organizations with a lost result (the router function's query)"] = lost
        val report = plans.entries.joinToString("\n\n") { (name, plan) -> "-- $name\n$plan" }
        SystemFileSystem.createDirectories(Path("build/reports/explain"))
        SystemFileSystem.sink(Path("build/reports/explain/governance.txt")).buffered().use { it.writeString(report) }
        for ((name, plan) in plans) {
            assertFalse("Seq Scan on governance_confirmations" in plan, "$name scans the table:\n$plan")
        }
    }

    /** The plan of [sql] in org A, scoped like the repository's queries; `:org_id` is bound, [bind] adds the rest. */
    private suspend fun TestServer.explain(
        sql: String,
        analyze: Boolean = true,
        bind: Statement.() -> Statement,
    ): String {
        val options = if (analyze) "(ANALYZE, BUFFERS) " else ""
        val statement = Statement.create("EXPLAIN $options$sql").bind("org_id", orgA).bind()
        return driver.withOrganization(orgA) { tx ->
            tx.fetchAll(statement).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
        }
    }

    private companion object {
        val MEMBER_IDS: Map<String, Uuid> = listOf("ada", "olga", "mia", "bob").associateWith { Uuid.random() }
    }
}
