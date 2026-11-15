// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.koin.dsl.module
import run.ostrom.server.events.domain.EventAudience
import run.ostrom.server.events.domain.JournalEvent
import run.ostrom.server.events.domain.OrgEventsRepository
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.kernel.events.OrgEventDraft
import run.ostrom.server.kernel.events.OrgEventPublisher
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The journal's two writers on one organization: the sqlx4k `publishIn` of the modules not yet on Exposed, and the
 * Exposed one. Alternating, rolled back and concurrent, they share one gap-free sequence, each call's events in a row,
 * and a publisher on either pool waits for the other's commit (the cursor's row lock).
 */
class JournalWritersPostgresTest {
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val orgB = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    private val admin = Uuid.parse("0000000a-0000-0000-0000-0000000000a1")

    private val overrides = module { single<SecretCipher> { ContextCipher() } }

    private fun draft(subject: String, member: Uuid? = null) = OrgEventDraft(
        OrgEventType.APPROVAL_REQUESTED,
        subject,
        member,
        JsonObject(mapOf("approvalId" to JsonPrimitive(subject))),
    )

    // The admin of org A reads the whole organization's events and their own.
    private suspend fun TestServer.journal(org: Uuid = orgA): List<JournalEvent> =
        koin.get<OrgEventsRepository>().read(org, EventAudience(admin, true), 0, null, LIMIT).events

    private suspend fun TestServer.viaSqlx4k(org: Uuid, vararg events: OrgEventDraft) =
        driver.withOrganization(org) { tx -> koin.get<OrgEventPublisher>().publishIn(tx, org, events.toList()) }

    private suspend fun TestServer.viaExposed(org: Uuid, vararg events: OrgEventDraft) {
        val publisher = koin.get<OrgEventPublisher>()
        koin.get<JdbcDatabase>().inOrg(org) { publisher.publishIn(this, org, events.toList()) }
    }

    @Test
    fun alternatingWritersShareOneSequenceAndARollbackTakesNone() = serverTest(listOf(EventsModule), overrides) {
        val server = it
        server.viaSqlx4k(orgA, draft("s-1"), draft("s-2", admin))
        server.viaExposed(orgA, draft("e-1", admin))
        val publisher = server.koin.get<OrgEventPublisher>()
        val database = server.koin.get<JdbcDatabase>()
        assertFailsWith<IllegalStateException> {
            database.inOrg(orgA) {
                publisher.publishIn(this, orgA, listOf(draft("e-rolled-back")))
                error("The change fails")
            }
        }
        val tooLong = OrgEventDraft(
            OrgEventType.TODO_UPDATED,
            "e-long",
            null,
            JsonObject(mapOf("text" to JsonPrimitive("x".repeat(5_000)))),
        )
        assertFailsWith<IllegalArgumentException> { server.viaExposed(orgA, tooLong) }
        server.viaExposed(orgA)
        server.viaSqlx4k(orgA, draft("s-3"))
        server.viaExposed(orgA, draft("e-2"), draft("e-3"), draft("e-4"))
        server.viaExposed(orgB, draft("b-1"))

        val events = server.journal()
        assertEquals((1L..7L).toList(), events.map { e -> e.seq }, "No gap: the rolled-back event took no sequence")
        assertEquals(listOf("s-1", "s-2", "e-1", "s-3", "e-2", "e-3", "e-4"), events.map { e -> e.subjectId })
        assertEquals(7, server.koin.get<OrgEventsRepository>().head(orgA))
        val exposed = events[2]
        assertEquals(OrgEventType.APPROVAL_REQUESTED.wire, exposed.type)
        assertEquals(OrgEventType.APPROVAL_REQUESTED.subject, exposed.subject)
        assertEquals(admin, exposed.memberId)
        assertEquals("""{"approvalId":"e-1"}""", exposed.data)
        assertEquals(listOf(1L), server.journal(orgB).map { e -> e.seq }, "Each organization counts on its own")
    }

    @Test
    fun concurrentWritersOnBothPoolsNeverInterleaveACall() = serverTest(listOf(EventsModule), overrides) { server ->
        // On the real clock: the transactions really wait for each other.
        withContext(Dispatchers.Default) {
            coroutineScope {
                (0 until WRITERS).map { writer ->
                    async {
                        val events = (0 until PER_CALL).map { draft("w$writer-$it") }.toTypedArray()
                        repeat(CALLS) { call ->
                            val named = events.map { e -> e.copy(subjectId = "${e.subjectId}-$call") }.toTypedArray()
                            // Each writer alternates pools, and the writers start on different ones.
                            if ((writer + call) % 2 == 0) {
                                server.viaSqlx4k(orgA, *named)
                            } else {
                                server.viaExposed(orgA, *named)
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        val events = server.journal()
        val total = WRITERS * CALLS * PER_CALL
        assertEquals((1L..total.toLong()).toList(), events.map { it.seq }, "One gap-free sequence")
        // A call's events are in a row, in its order: the cursor moved once by their number, under its row lock.
        events.chunked(PER_CALL).forEach { call ->
            val prefix = call.first().subjectId.substringBefore('-')
            val suffix = call.first().subjectId.substringAfterLast('-')
            assertEquals((0 until PER_CALL).map { "$prefix-$it-$suffix" }, call.map { it.subjectId })
        }
    }

    @Test
    fun aPublisherOnEitherPoolWaitsForTheOthersCommit() = serverTest(listOf(EventsModule), overrides) { server ->
        val publisher = server.koin.get<OrgEventPublisher>()
        val database = server.koin.get<JdbcDatabase>()
        withContext(Dispatchers.Default) {
            // Exposed holds the cursor's lock: the sqlx4k publisher waits for its commit, and comes after it.
            val published = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = async {
                database.inOrg(orgA) {
                    publisher.publishIn(this, orgA, listOf(draft("exposed-first")))
                    published.countDown()
                    check(release.await(HOLD_SECONDS, TimeUnit.SECONDS)) { "Never released" }
                }
            }
            check(published.await(HOLD_SECONDS, TimeUnit.SECONDS)) { "Never published" }
            val waiter = async { server.viaSqlx4k(orgA, draft("sqlx4k-second")) }
            delay(WAIT_MILLIS)
            assertFalse(waiter.isCompleted, "The sqlx4k publisher waits for the Exposed transaction")
            release.countDown()
            holder.await()
            waiter.await()

            // sqlx4k holds it: the Exposed publisher waits, and comes after it.
            val held = CompletableDeferred<Unit>()
            val free = CompletableDeferred<Unit>()
            val sqlx4k = async {
                server.driver.withOrganization(orgA) { tx ->
                    publisher.publishIn(tx, orgA, listOf(draft("sqlx4k-third")))
                    held.complete(Unit)
                    free.await()
                }
            }
            held.await()
            val exposed = async { server.viaExposed(orgA, draft("exposed-fourth")) }
            delay(WAIT_MILLIS)
            assertFalse(exposed.isCompleted, "The Exposed publisher waits for the sqlx4k transaction")
            free.complete(Unit)
            sqlx4k.await()
            exposed.await()
        }
        val events = server.journal()
        val order = listOf("exposed-first", "sqlx4k-second", "sqlx4k-third", "exposed-fourth")
        assertEquals(order, events.map { it.subjectId })
        assertTrue(events.zipWithNext().all { (a, b) -> b.seq == a.seq + 1 })
    }

    private companion object {
        const val LIMIT = 1_000
        const val WRITERS = 8
        const val CALLS = 6
        const val PER_CALL = 3
        const val HOLD_SECONDS = 10L
        const val WAIT_MILLIS = 500L
    }
}
