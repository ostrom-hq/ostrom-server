// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners

import dev.ostrom.server.runners.domain.RegisteredRunnersRepository
import dev.ostrom.server.runners.fakes.ORG_A
import dev.ostrom.server.runners.fakes.ORG_B
import dev.ostrom.server.runners.fakes.SettableClock
import dev.ostrom.server.runners.fakes.testIdentities
import dev.ostrom.server.runners.presentation.RegisterRunnerRequest
import dev.ostrom.server.runners.presentation.RegisteredRunnerDto
import dev.ostrom.server.runners.presentation.RegisteredRunnerHeartbeatResponse
import dev.ostrom.server.runners.presentation.RunnersPage
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
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
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
            assertEquals(RegisteredRunnerHeartbeatResponse(emptyList()), beat.body<RegisteredRunnerHeartbeatResponse>())
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
        assertEquals("offline", server.status("dana"))
        assertEquals("offline", server.status("carol"))
    }

    @Test
    fun theRunnersOrganizationIsReadAcrossOrganizationsOnlyThroughItsFunction() =
        serverTest(listOf(RunnersModule), overrides) { server ->
            val runner = server.register("dana", "build-1")
            val id = Uuid.parse(runner.id)
            val repository = server.koin.get<RegisteredRunnersRepository>()

            // As the serving role, without an organization: the table shows nothing, the function one organization.
            val direct = server.driver.fetchAll(Statement.create("SELECT org_id FROM runners_registered")).getOrThrow()
            assertEquals(0, direct.size)
            val routed = server.driver.fetchAll(
                Statement.create("SELECT runners_registered_org(CAST(:id AS UUID)) AS org_id").bind("id", id),
            ).getOrThrow().single().get("org_id").asUuidOrNull()
            assertEquals(ORG_A, routed)
            assertEquals(ORG_A, repository.organizationOf(id))
            assertNull(repository.organizationOf(Uuid.random()))

            // Read in its organization only; an older sign of life never replaces a newer one.
            assertNull(repository.get(ORG_B, id))
            val later = clock.now + 30.seconds
            repository.touch(ORG_A, id, later)
            repository.touch(ORG_A, id, clock.now)
            repository.touch(ORG_B, id, later + 30.seconds)
            assertEquals(later, repository.get(ORG_A, id)?.lastSeenAt)
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

    private suspend fun TestServer.status(token: String): String =
        client.get("/api/v1/runners") { bearerAuth(token) }.body<RunnersPage>().items.single().status
}
