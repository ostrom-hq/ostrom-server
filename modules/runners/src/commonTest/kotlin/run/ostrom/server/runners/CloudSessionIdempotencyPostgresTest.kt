// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.core.api.Idempotency
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.CreateKey
import run.ostrom.server.runners.domain.KeyedCreateOutcome
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.testIdentities
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.infrastructure.RunnersCreateKeys
import run.ostrom.server.runners.presentation.CloudSessionResponse
import run.ostrom.server.runners.presentation.CreateCloudSessionRequest
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

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
        // A record whose session no longer exists.
        server.koin.get<JdbcDatabase>().inOrg(ORG_A) {
            RunnersCreateKeys.insert {
                it[orgId] = ORG_A
                it[memberId] = "alice"
                it[idempotencyKey] = "k"
                it[requestDigest] = DIGEST
                it[sessionId] = Uuid.random()
                it[createdAt] = now
                it[expiresAt] = now + 1.hours
            }
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
        val left = server.koin.get<JdbcDatabase>().inOrg(ORG_A) { RunnersCreateKeys.selectAll().count() }
        assertEquals(1L, left)
    }

    @Test
    fun theModulePurgesTheExpiredRecordsOnItsOwn() = serverTest(listOf(RunnersModule)) { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val now = Clock.System.now()
        repository.createKeyed(session(createdAt = now - 2.hours), 5, CreateKey("old", DIGEST, now - 1.hours))
        suspend fun left() = server.koin.get<JdbcDatabase>().inOrg(ORG_A) { RunnersCreateKeys.selectAll().count() }
        val job = Job()
        RunnersModule.start(CoroutineScope(currentCoroutineContext() + job), server.koin)
        withTimeout(20.seconds) {
            while (left() > 0) delay(100.milliseconds)
        }
        job.cancelAndJoin()
    }

    private companion object {
        const val DIGEST = "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
