// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.core.api.Idempotency
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.CreateKey
import run.ostrom.server.runners.domain.KeyedCreateOutcome
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.testIdentities
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.infrastructure.inTenant
import run.ostrom.server.runners.presentation.CloudSessionResponse
import run.ostrom.server.runners.presentation.CreateCloudSessionRequest
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/** Against a real PostgreSQL: a creation's `Idempotency-Key` (`runners.cloud.idempotency.v1`, TeamCode's create). */
class CloudSessionIdempotencyPostgresTest {
    private fun request(name: String = "Fix it") = CreateCloudSessionRequest(agent = "codex", name = name)

    @Test
    fun theSameKeyReplaysTheSessionAndAnotherRequestIsRefused() =
        serverTest(listOf(RunnersModule), testIdentities) { server ->
            suspend fun create(key: String, body: CreateCloudSessionRequest, member: String = "alice") =
                server.client.post("/api/v1/cloud/sessions") {
                    bearerAuth(member)
                    header(Idempotency.KEY_HEADER, key)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }

            val first = create("press-1", request())
            assertEquals(HttpStatusCode.Created, first.status)
            assertNull(first.headers[Idempotency.REPLAYED_HEADER])
            val replay = create("press-1", request())
            assertEquals(HttpStatusCode.Accepted, replay.status)
            assertEquals("true", replay.headers[Idempotency.REPLAYED_HEADER])
            assertEquals(first.body<CloudSessionResponse>().id, replay.body<CloudSessionResponse>().id)

            val other = create("press-1", request(name = "Something else"))
            assertEquals(HttpStatusCode.UnprocessableEntity, other.status)
            assertTrue(Idempotency.CONFLICT in other.bodyAsText())
            assertEquals(HttpStatusCode.BadRequest, create("not a key", request()).status)
            // The key is the member's: another member's same key creates their own session.
            assertEquals(HttpStatusCode.Created, create("press-1", request(), member = "bob").status)
        }

    @Test
    fun concurrentRequestsWithOneKeyCreateOneSession() = serverTest(listOf(RunnersModule)) { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val now = Clock.System.now()
        val outcomes = coroutineScope {
            (1..8).map {
                async { repository.createKeyed(session(createdAt = now), 5, CreateKey("k", DIGEST, now + 24.hours)) }
            }.awaitAll()
        }

        assertEquals(1, outcomes.count { it == KeyedCreateOutcome.Created })
        assertEquals(7, outcomes.count { it is KeyedCreateOutcome.Replayed })
    }

    @Test
    fun anExpiredKeyCreatesAgainAndTheCapStillHolds() = serverTest(listOf(RunnersModule)) { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val now = Clock.System.now()
        val first = session(createdAt = now - 25.hours)
        repository.createKeyed(first, 5, CreateKey("k", DIGEST, now - 1.hours))

        val again = session(createdAt = now)
        val created = repository.createKeyed(again, 2, CreateKey("k", DIGEST, now + 1.hours))
        assertEquals(KeyedCreateOutcome.Created, created)
        assertEquals(
            KeyedCreateOutcome.AtCap,
            repository.createKeyed(session(createdAt = now), 2, CreateKey("k2", DIGEST, now + 1.hours)),
        )
    }

    @Test
    fun aKeyWhoseSessionIsGoneIsAConflict() = serverTest(listOf(RunnersModule)) { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val now = Clock.System.now()
        val gone = session(createdAt = now)
        repository.createKeyed(gone, 5, CreateKey("k", DIGEST, now + 1.hours))
        server.driver.inTenant(ORG_A) {
            execute(Statement.create("DELETE FROM runners_session_routes WHERE session_id = :id").bind("id", gone.id))
                .getOrThrow()
            execute(Statement.create("DELETE FROM runners_sessions WHERE id = :id").bind("id", gone.id)).getOrThrow()
        }

        assertEquals(
            KeyedCreateOutcome.Conflict,
            repository.createKeyed(session(createdAt = now), 5, CreateKey("k", DIGEST, now + 1.hours)),
        )
    }

    @Test
    fun thePurgeDeletesOnlyExpiredRecordsOfEveryOrganization() = serverTest(listOf(RunnersModule)) { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val now = Clock.System.now()
        repository.createKeyed(session(createdAt = now - 2.hours), 5, CreateKey("old", DIGEST, now - 1.hours))
        repository.createKeyed(session(createdAt = now), 5, CreateKey("live", DIGEST, now + 1.hours))

        assertEquals(1, repository.purgeExpiredCreateKeys())
        assertEquals(0, repository.purgeExpiredCreateKeys())
        val left = server.driver.inTenant(ORG_A) {
            fetchAll(Statement.create("SELECT COUNT(*) AS n FROM runners_create_keys")).getOrThrow()
                .first().get("n").asLong()
        }
        assertEquals(1, left)
    }

    private companion object {
        const val DIGEST = "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
