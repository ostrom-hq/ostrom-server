// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners

import dev.ostrom.server.runners.domain.RegisteredRunnerKey
import dev.ostrom.server.runners.domain.RegisteredRunnersRepository
import dev.ostrom.server.runners.domain.RunnerTokens
import dev.ostrom.server.runners.fakes.ORG_A
import dev.ostrom.server.runners.fakes.ORG_B
import dev.ostrom.server.runners.fakes.SettableClock
import dev.ostrom.server.runners.fakes.testIdentities
import dev.ostrom.server.runners.infrastructure.RegisteredRunnersRepositoryImpl
import dev.ostrom.server.runners.infrastructure.inTenant
import dev.ostrom.server.runners.presentation.RegisterRunnerRequest
import dev.ostrom.server.runners.presentation.RegisteredRunnerDto
import dev.ostrom.server.runners.presentation.RegisteredRunnerHeartbeatResponse
import dev.ostrom.server.runners.presentation.RunnersPage
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Against a real PostgreSQL, as the serving role: a registered runner's heartbeat
 * (`POST /api/v1/runners/{id}/heartbeat`) authenticated by its own key, which shows it `online` for 90 seconds, every
 * refusal, and the one read across organizations it needs (`runners_registered_org`).
 */
class RegisteredRunnersHeartbeatPostgresTest {
    private val clock = SettableClock()
    private val overrides = module {
        includes(testIdentities)
        single<Clock> { clock }
    }

    @Test
    fun aRunnerGoesOnlineWithItsHeartbeatAndOfflineAfterTheWindow() =
        serverTest(listOf(RunnersModule), overrides) { server ->
            val runner = server.register("dana", "build-1")
            assertEquals("offline", server.status("alice"))

            val beat = server.heartbeat(runner.id, runner.token)
            assertEquals(HttpStatusCode.OK, beat.status)
            assertEquals(
                RegisteredRunnerHeartbeatResponse(emptyList(), emptyList(), emptyList()),
                beat.body<RegisteredRunnerHeartbeatResponse>(),
            )
            assertEquals("online", server.status("alice"))
            val seen = server.client.get("/api/v1/runners") { bearerAuth("alice") }.body<RunnersPage>().items.single()
            assertEquals(clock.now.toString(), seen.lastSeenAt)

            clock.now += 90.seconds
            assertEquals("online", server.status("alice"), "Still online at the edge of the window")
            clock.now += 1.seconds
            assertEquals("offline", server.status("alice"), "Offline once 90 seconds have passed without a heartbeat")

            assertEquals(HttpStatusCode.OK, server.heartbeat(runner.id, runner.token).status)
            assertEquals("online", server.status("alice"), "Back online with the next heartbeat")
        }

    @Test
    fun aHeartbeatIsRefusedWithoutTheRunnersOwnKey() = serverTest(listOf(RunnersModule), overrides) { server ->
        val mine = server.register("dana", "build-1")
        val sibling = server.register("dana", "build-2")
        val carols = server.register("carol", "build-1")

        val refused = listOf(
            "no key" to server.client.post("/api/v1/runners/${mine.id}/heartbeat"),
            "not a bearer" to server.client.post("/api/v1/runners/${mine.id}/heartbeat") {
                header(HttpHeaders.Authorization, "Basic ${mine.token}")
            },
            "a wrong key" to server.heartbeat(mine.id, "ostrom_runner_" + "0".repeat(64)),
            "a sibling's key" to server.heartbeat(mine.id, sibling.token),
            "another organization's key" to server.heartbeat(mine.id, carols.token),
            "its key for another organization's runner" to server.heartbeat(carols.id, mine.token),
            "a member's credential" to server.heartbeat(mine.id, "dana"),
            "an unknown runner" to server.heartbeat(Uuid.random().toString(), mine.token),
            "a malformed id" to server.heartbeat("not-a-uuid", mine.token),
        )
        for ((why, response) in refused) assertEquals(HttpStatusCode.Unauthorized, response.status, why)

        // Nothing was marked seen, in either organization.
        assertEquals(listOf("offline", "offline"), server.statuses("dana"))
        assertEquals(listOf("offline"), server.statuses("carol"))
    }

    @Test
    fun theRunnersKeyIsReadAcrossOrganizationsOnlyThroughItsFunction() =
        serverTest(listOf(RunnersModule), overrides) { server ->
            val runner = server.register("dana", "build-1")
            val id = Uuid.parse(runner.id)
            val hash = server.koin.get<RunnerTokens>().hash(runner.token)
            val repository = server.koin.get<RegisteredRunnersRepository>()

            // As the serving role, without an organization: the table shows nothing, the function one runner's key.
            val direct = server.driver.fetchAll(Statement.create("SELECT org_id FROM runners_registered")).getOrThrow()
            assertEquals(0, direct.size)
            assertEquals(RegisteredRunnerKey(ORG_A, hash), repository.keyOf(id))
            assertNull(repository.keyOf(Uuid.random()))

            // Written in its organization, with its key, only; an older sign of life never replaces a newer one.
            val later = clock.now + 30.seconds
            assertTrue(repository.touch(ORG_A, id, hash, later, null))
            assertTrue(repository.touch(ORG_A, id, hash, clock.now, 3))
            assertFalse(repository.touch(ORG_B, id, hash, later + 30.seconds, 9))
            assertFalse(repository.touch(ORG_A, id, "another-hash", later + 30.seconds, 9))
            assertEquals(3, repository.list(ORG_A, 10).single().capacity, "The capacity of its last heartbeat")
            val seen = server.driver.inTenant(ORG_A) {
                fetchAll(Statement.create("SELECT last_seen_at FROM runners_registered")).getOrThrow()
            }.single().get("last_seen_at").asInstant()
            assertEquals(later, seen)
        }

    @Test
    fun theHeartbeatsQueriesUseTheirIndexes() = serverTest(listOf(RunnersModule)) { server ->
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
        val one = server.driver.inTenant(ORG_A) {
            fetchAll(Statement.create("SELECT id, key_hash FROM runners_registered LIMIT 1")).getOrThrow()
        }.single()
        val id = one.get("id").asUuid()
        val hash = one.get("key_hash").asString()
        // The function's own query, as `ostrom_router` reads it (its policy opens every row), then the write.
        val plans = linkedMapOf(
            "key of a runner (body of runners_registered_key)" to server.plan(
                null,
                "SELECT r.org_id, r.key_hash FROM runners_registered r WHERE r.id = :id",
            ) { bind("id", id) },
            "sign of life" to server.plan(ORG_A, RegisteredRunnersRepositoryImpl.TOUCH_SQL) {
                bind("at", clock.now).bind("capacity", 2).bind("org_id", ORG_A).bind("id", id).bind("key_hash", hash)
            },
        )
        val report = plans.entries.joinToString("\n\n") { (name, plan) -> "-- $name\n$plan" }
        SystemFileSystem.createDirectories(Path("build/reports/explain"))
        SystemFileSystem.sink(Path("build/reports/explain/runners-heartbeat.txt")).buffered()
            .use { it.writeString(report) }
        for ((name, plan) in plans) {
            assertFalse("Seq Scan on runners_registered" in plan, "$name scans the table:\n$plan")
            // A unique index either way: the primary key, or the key's hash for the write (both are one row).
            val unique = listOf("runners_registered_pkey", "runners_registered_key")
                .any { "Index Scan using $it " in plan }
            assertTrue(unique, "$name does not use a unique index:\n$plan")
        }
    }

    /** The plan of [sql]: in [org]'s transaction, or as the test's superuser for a router function's body. */
    private suspend fun TestServer.plan(org: Uuid?, sql: String, bind: Statement.() -> Statement): String {
        val explain = Statement.create("EXPLAIN (ANALYZE, BUFFERS) $sql").bind()
        val rows = if (org == null) {
            database.superuser.fetchAll(explain).getOrThrow()
        } else {
            driver.inTenant(org) { fetchAll(explain).getOrThrow() }
        }
        return rows.joinToString("\n") { it.get("QUERY PLAN").asString() }
    }

    private suspend fun TestServer.register(token: String, name: String): RegisteredRunnerDto {
        val response = client.post("/api/v1/runners") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(RegisterRunnerRequest(name, emptyList()))
        }
        assertEquals(HttpStatusCode.Created, response.status, "$token registers $name")
        return response.body()
    }

    private suspend fun TestServer.heartbeat(id: String, key: String): HttpResponse =
        client.post("/api/v1/runners/$id/heartbeat") { bearerAuth(key) }

    private suspend fun TestServer.statuses(token: String): List<String> =
        client.get("/api/v1/runners") { bearerAuth(token) }.body<RunnersPage>().items.map { it.status }

    private suspend fun TestServer.status(token: String): String = statuses(token).single()
}
