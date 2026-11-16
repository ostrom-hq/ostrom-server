// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events

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
 * The journal's two writers on one organization: `publish` in its own transaction, and `publishIn` in the caller's.
 * Alternating, rolled back and concurrent, they share one gap-free sequence, each call's events in a row, and a
 * publisher waits for the other's commit (the cursor's row lock).
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

    private suspend fun TestServer.viaOwnTransaction(org: Uuid, vararg events: OrgEventDraft) =
        koin.get<OrgEventPublisher>().publish(org, events.toList())

    private suspend fun TestServer.viaCallersTransaction(org: Uuid, vararg events: OrgEventDraft) {
        val publisher = koin.get<OrgEventPublisher>()
        koin.get<JdbcDatabase>().inOrg(org) { publisher.publishIn(this, org, events.toList()) }
    }

    @Test
    fun alternatingWritersShareOneSequenceAndARollbackTakesNone() = serverTest(listOf(EventsModule), overrides) {
        val server = it
        server.viaOwnTransaction(orgA, draft("s-1"), draft("s-2", admin))
        server.viaCallersTransaction(orgA, draft("e-1", admin))
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
        assertFailsWith<IllegalArgumentException> { server.viaCallersTransaction(orgA, tooLong) }
        server.viaCallersTransaction(orgA)
        server.viaOwnTransaction(orgA, draft("s-3"))
        server.viaCallersTransaction(orgA, draft("e-2"), draft("e-3"), draft("e-4"))
        server.viaCallersTransaction(orgB, draft("b-1"))

        val events = server.journal()
        assertEquals((1L..7L).toList(), events.map { e -> e.seq }, "No gap: the rolled-back event took no sequence")
        assertEquals(listOf("s-1", "s-2", "e-1", "s-3", "e-2", "e-3", "e-4"), events.map { e -> e.subjectId })
        assertEquals(7, server.koin.get<OrgEventsRepository>().head(orgA))
        val callers = events[2]
        assertEquals(OrgEventType.APPROVAL_REQUESTED.wire, callers.type)
        assertEquals(OrgEventType.APPROVAL_REQUESTED.subject, callers.subject)
        assertEquals(admin, callers.memberId)
        assertEquals("""{"approvalId":"e-1"}""", callers.data)
        assertEquals(listOf(1L), server.journal(orgB).map { e -> e.seq }, "Each organization counts on its own")
    }

    @Test
    fun concurrentWritersNeverInterleaveACall() = serverTest(listOf(EventsModule), overrides) { server ->
        // On the real clock: the transactions really wait for each other.
        withContext(Dispatchers.Default) {
            coroutineScope {
                (0 until WRITERS).map { writer ->
                    async {
                        val events = (0 until PER_CALL).map { draft("w$writer-$it") }.toTypedArray()
                        repeat(CALLS) { call ->
                            val named = events.map { e -> e.copy(subjectId = "${e.subjectId}-$call") }.toTypedArray()
                            // Each writer alternates the two ways to publish, and the writers start on different ones.
                            if ((writer + call) % 2 == 0) {
                                server.viaOwnTransaction(orgA, *named)
                            } else {
                                server.viaCallersTransaction(orgA, *named)
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
    fun aPublisherWaitsForTheOthersCommit() = serverTest(listOf(EventsModule), overrides) { server ->
        val publisher = server.koin.get<OrgEventPublisher>()
        val database = server.koin.get<JdbcDatabase>()
        withContext(Dispatchers.Default) {
            // The caller's transaction holds the cursor's lock: a publisher in its own waits for its commit.
            val published = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = async {
                database.inOrg(orgA) {
                    publisher.publishIn(this, orgA, listOf(draft("caller-first")))
                    published.countDown()
                    check(release.await(HOLD_SECONDS, TimeUnit.SECONDS)) { "Never released" }
                }
            }
            check(published.await(HOLD_SECONDS, TimeUnit.SECONDS)) { "Never published" }
            val waiter = async { server.viaOwnTransaction(orgA, draft("own-second")) }
            delay(WAIT_MILLIS)
            assertFalse(waiter.isCompleted, "The publisher in its own transaction waits for the caller's")
            release.countDown()
            holder.await()
            waiter.await()

            // Another caller's transaction holds it: the next caller's publisher waits, and comes after it.
            val held = CountDownLatch(1)
            val free = CountDownLatch(1)
            val first = async {
                database.inOrg(orgA) {
                    publisher.publishIn(this, orgA, listOf(draft("caller-third")))
                    held.countDown()
                    check(free.await(HOLD_SECONDS, TimeUnit.SECONDS)) { "Never freed" }
                }
            }
            check(held.await(HOLD_SECONDS, TimeUnit.SECONDS)) { "Never held" }
            val next = async { server.viaCallersTransaction(orgA, draft("caller-fourth")) }
            delay(WAIT_MILLIS)
            assertFalse(next.isCompleted, "The next caller's publisher waits for the first caller's transaction")
            free.countDown()
            first.await()
            next.await()
        }
        val events = server.journal()
        val order = listOf("caller-first", "own-second", "caller-third", "caller-fourth")
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
