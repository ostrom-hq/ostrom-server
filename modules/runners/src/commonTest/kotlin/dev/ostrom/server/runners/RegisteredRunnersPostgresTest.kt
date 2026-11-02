// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners

import dev.ostrom.server.runners.domain.RegisteredRunner
import dev.ostrom.server.runners.domain.RegisteredRunnersRepository
import dev.ostrom.server.runners.domain.RunnerRegistrationOutcome
import dev.ostrom.server.runners.domain.RunnerTokens
import dev.ostrom.server.runners.fakes.DANA
import dev.ostrom.server.runners.fakes.ORG_A
import dev.ostrom.server.runners.fakes.ORG_B
import dev.ostrom.server.runners.fakes.testIdentities
import dev.ostrom.server.runners.infrastructure.RegisteredRunnersRepositoryImpl
import dev.ostrom.server.runners.infrastructure.inTenant
import dev.ostrom.server.runners.presentation.RegisterRunnerRequest
import dev.ostrom.server.runners.presentation.RegisteredRunnerDto
import dev.ostrom.server.runners.presentation.RunnerDto
import dev.ostrom.server.runners.presentation.RunnersPage
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Against a real PostgreSQL, as the serving role (`ostrom_app`, RLS forced): the registered runners' SQL, their table's
 * isolation, the `GET` and `POST /api/v1/runners` routes with every refusal, and the plans of the request-path reads.
 */
class RegisteredRunnersPostgresTest {
    private val t0 = Instant.parse("2026-11-02T10:00:00Z")

    private fun runner(orgId: Uuid, name: String) = RegisteredRunner(
        id = Uuid.random(),
        orgId = orgId,
        name = name,
        labels = listOf("linux", "gpu"),
        version = "1.0",
        keyHash = "hash-${Uuid.random()}",
        registeredBy = "dana",
        createdAt = t0,
        lastSeenAt = null,
    )

    private suspend fun TestServer.count(orgId: Uuid): Long = driver.inTenant(orgId) {
        fetchAll(Statement.create("SELECT COUNT(*) AS n FROM runners_registered")).getOrThrow().first().get("n")
            .asLong()
    }

    @Test
    fun repositoryListsByNameAndRefusesATakenNameOrAFullOrganization() = serverTest(listOf(RunnersModule)) { server ->
        val repository = server.koin.get<RegisteredRunnersRepository>()
        val b = runner(ORG_A, "b").copy(version = null, labels = emptyList(), lastSeenAt = t0)
        val a = runner(ORG_A, "a")
        assertEquals(RunnerRegistrationOutcome.REGISTERED, repository.register(b, 3))
        assertEquals(RunnerRegistrationOutcome.REGISTERED, repository.register(a, 3))
        assertEquals(RunnerRegistrationOutcome.NAME_TAKEN, repository.register(runner(ORG_A, "a"), 3))
        // The name is unique per organization only.
        assertEquals(RunnerRegistrationOutcome.REGISTERED, repository.register(runner(ORG_B, "a"), 3))

        assertEquals(listOf(a, b), repository.list(ORG_A, 10))
        assertEquals(listOf(a), repository.list(ORG_A, 1))
        assertEquals(RunnerRegistrationOutcome.LIMIT_REACHED, repository.register(runner(ORG_A, "c"), 2))
        assertEquals(listOf("a"), repository.list(ORG_B, 10).map { it.name })
    }

    @Test
    fun concurrentRegistrationsNeverPassTheCapTogether() = serverTest(listOf(RunnersModule)) { server ->
        val repository = server.koin.get<RegisteredRunnersRepository>()
        val outcomes = coroutineScope {
            (1..6).map { async { repository.register(runner(ORG_A, "r$it"), 3) } }.awaitAll()
        }
        assertEquals(3, outcomes.count { it == RunnerRegistrationOutcome.REGISTERED })
        assertEquals(3, outcomes.count { it == RunnerRegistrationOutcome.LIMIT_REACHED })
        assertEquals(3L, server.count(ORG_A))
    }

    @Test
    fun anotherOrganizationNeitherSeesNorChangesTheRunnersTable() = serverTest(listOf(RunnersModule)) { server ->
        server.koin.get<RegisteredRunnersRepository>().register(runner(ORG_A, "a"), 5)
        assertEquals(1L, server.count(ORG_A))
        // No WHERE org_id: the policy alone hides organization A's rows.
        assertEquals(0L, server.count(ORG_B))
        for (sql in listOf("UPDATE runners_registered SET name = 'x'", "DELETE FROM runners_registered")) {
            assertEquals(0L, server.driver.inTenant(ORG_B) { execute(Statement.create(sql)).getOrThrow() }, sql)
        }
        assertFailsWith<Exception> {
            server.driver.inTenant(ORG_B) {
                execute(
                    Statement.create(
                        """
                        INSERT INTO runners_registered (id, org_id, name, labels, key_hash, registered_by, created_at)
                        VALUES (:id, :org, 'x', '[]', 'h', 'carol', :at)
                        """,
                    ).bind("id", Uuid.random()).bind("org", ORG_A).bind("at", t0),
                ).getOrThrow()
            }
        }
        assertEquals(listOf("a"), server.koin.get<RegisteredRunnersRepository>().list(ORG_A, 10).map { it.name })
    }

    @Test
    fun anAdminRegistersARunnerWhoseKeyIsShownOnceAndEveryMemberListsIt() =
        serverTest(listOf(RunnersModule), testIdentities) { server ->
            val response = server.register("dana", RegisterRunnerRequest("build-1", listOf("linux", "gpu"), "1.2.0"))
            assertEquals(HttpStatusCode.Created, response.status)
            val created = response.body<RegisteredRunnerDto>()
            assertTrue(created.token.matches(Regex("ostrom_runner_[0-9a-f]{64}")))
            assertEquals("build-1", created.name)
            assertEquals(listOf("linux", "gpu"), created.labels)
            assertEquals("offline", created.status)
            assertEquals("1.2.0", created.version)

            // Only the key's hash is stored, with who registered it.
            val stored = server.driver.inTenant(ORG_A) {
                fetchAll(Statement.create("SELECT key_hash, registered_by FROM runners_registered")).getOrThrow()
            }.single()
            assertEquals(server.koin.get<RunnerTokens>().hash(created.token), stored.get("key_hash").asString())
            assertEquals(DANA.toString(), stored.get("registered_by").asString())

            // Every member of the organization lists it, a service token with `cloud:read` too; never its key.
            val expected = RunnerDto(created.id, "build-1", listOf("linux", "gpu"), "offline", null, "1.2.0")
            for (token in listOf("alice", "dana", "reader")) {
                val page = server.client.get("/api/v1/runners") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, page.status, token)
                assertFalse(created.token in page.bodyAsText(), token)
                assertEquals(listOf(expected), page.body<RunnersPage>().items, token)
            }

            // The name is taken in organization A.
            val again = server.register("dana", RegisterRunnerRequest("build-1", emptyList()))
            assertEquals(HttpStatusCode.Conflict, again.status)
            assertTrue("already has a runner of that name" in again.bodyAsText())
        }

    @Test
    fun anotherOrganizationNeitherSeesNorRegistersInIt() = serverTest(listOf(RunnersModule), testIdentities) { server ->
        assertEquals(HttpStatusCode.Created, server.register("dana", RegisterRunnerRequest("a-1", emptyList())).status)
        // Carol, owner of organization B, registers hers under the same name: each organization has its own.
        assertEquals(HttpStatusCode.Created, server.register("carol", RegisterRunnerRequest("a-1", listOf("b"))).status)

        val carols = server.client.get("/api/v1/runners") { bearerAuth("carol") }.body<RunnersPage>().items
        assertEquals(listOf(listOf("b")), carols.map { it.labels })
        val danas = server.client.get("/api/v1/runners") { bearerAuth("dana") }.body<RunnersPage>().items
        assertEquals(listOf(emptyList<String>()), danas.map { it.labels })

        // Naming organization A, of which carol is not a member: 404, and nothing is written there.
        val read = server.client.get("/api/v1/runners") {
            bearerAuth("carol")
            header("Ostrom-Org", ORG_A.toString())
        }
        assertEquals(HttpStatusCode.NotFound, read.status)
        val write = server.register("carol", RegisterRunnerRequest("intruder", emptyList()), org = ORG_A)
        assertEquals(HttpStatusCode.NotFound, write.status)
        assertEquals(1L, server.count(ORG_A))
        assertEquals(1L, server.count(ORG_B))
    }

    @Test
    fun registeringIsRefusedToAnyoneButAnAdminInTheConsoleAndToAMalformedBody() =
        serverTest(listOf(RunnersModule), testIdentities) { server ->
            val valid = """{"name":"b","labels":[]}"""
            assertEquals(HttpStatusCode.Unauthorized, server.registerRaw(null, valid).status)
            assertEquals(HttpStatusCode.NotFound, server.registerRaw("nobody", valid).status)
            // A member who is neither owner nor admin.
            val member = server.registerRaw("alice", valid)
            assertEquals(HttpStatusCode.Forbidden, member.status)
            assertTrue("Only an owner or an admin" in member.bodyAsText())
            // An admin's personal API token: minting a long-lived key needs the console (CONVENTIONS 16.2).
            assertEquals(HttpStatusCode.Forbidden, server.registerRaw("dana-cli", valid).status)
            // A service token.
            assertEquals(HttpStatusCode.Forbidden, server.registerRaw("reader", valid).status)
            // The contract requires the labels; a blank name, a bad label.
            val malformed = listOf(
                """{"name":"b"}""",
                """{"name":"  ","labels":[]}""",
                """{"name":"b","labels":["a b"]}""",
            )
            for (body in malformed) {
                assertEquals(HttpStatusCode.BadRequest, server.registerRaw("dana", body).status, body)
            }
            assertEquals(0L, server.count(ORG_A))

            assertEquals(HttpStatusCode.Unauthorized, server.client.get("/api/v1/runners").status)
            assertEquals(
                HttpStatusCode.NotFound,
                server.client.get("/api/v1/runners") { bearerAuth("nobody") }.status,
            )
        }

    @Test
    fun theRequestPathReadsUseTheNameIndex() = serverTest(listOf(RunnersModule)) { server ->
        for ((org, rows) in listOf(ORG_B to 4_000, ORG_A to 100)) {
            server.driver.inTenant(org) {
                execute(
                    Statement.create(
                        """
                        INSERT INTO runners_registered (id, org_id, name, labels, key_hash, registered_by, created_at)
                        SELECT gen_random_uuid(), CAST(:org AS UUID), 'r' || i, '[]', md5(:org || i), 'dana', now()
                        FROM generate_series(1, $rows) AS i
                        """,
                    ).bind("org", org.toString()),
                ).getOrThrow()
            }
        }
        // Statistics only, which no request writes: the serving role may not vacuum (#68).
        server.database.superuser.execute("VACUUM ANALYZE runners_registered").getOrThrow()
        val plans = linkedMapOf(
            "list of an organization" to server.explain(RegisteredRunnersRepositoryImpl.LIST_SQL, limit = true),
            "count of an organization" to server.explain(RegisteredRunnersRepositoryImpl.COUNT_SQL, limit = false),
        )
        val report = plans.entries.joinToString("\n\n") { (name, plan) -> "-- $name\n$plan" }
        SystemFileSystem.createDirectories(Path("build/reports/explain"))
        SystemFileSystem.sink(Path("build/reports/explain/runners.txt")).buffered().use { it.writeString(report) }
        for ((name, plan) in plans) {
            assertFalse("Seq Scan on runners_registered" in plan, "$name scans the table:\n$plan")
        }
    }

    /** The plan of [sql] in org A, scoped like the repository's reads. */
    private suspend fun TestServer.explain(sql: String, limit: Boolean): String {
        val base = Statement.create("EXPLAIN (ANALYZE, BUFFERS) $sql").bind("org_id", ORG_A)
        val statement = if (limit) base.bind("limit", 100) else base
        return driver.inTenant(ORG_A) {
            fetchAll(statement).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
        }
    }

    private suspend fun TestServer.register(token: String, request: RegisterRunnerRequest, org: Uuid? = null) =
        client.post("/api/v1/runners") {
            bearerAuth(token)
            if (org != null) header("Ostrom-Org", org.toString())
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun TestServer.registerRaw(token: String?, body: String): HttpResponse =
        client.post("/api/v1/runners") {
            if (token != null) bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
}
