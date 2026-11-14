// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events

import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.koin.dsl.module
import run.ostrom.server.events.domain.OrgEventsRepository
import run.ostrom.server.events.infrastructure.OrgEventJournalImpl
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.kernel.events.JournalPosition
import run.ostrom.server.kernel.events.OrgEventDraft
import run.ostrom.server.kernel.events.OrgEventJournal
import run.ostrom.server.kernel.events.OrgEventPublisher
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Real PostgreSQL: the readers of the journal, their cursors, their pages, their races and their isolation. */
class OrgEventJournalPostgresTest {
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val orgB = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    private val mia = Uuid.parse("0000000a-0000-0000-0000-0000000000b1")
    private val reader = "notifications"

    private val overrides = module { single<SecretCipher> { ContextCipher() } }

    private fun wait(id: String, member: Uuid? = mia) =
        OrgEventDraft(OrgEventType.WAIT_CREATED, id, member, JsonObject(mapOf("waitId" to JsonPrimitive(id))))

    private suspend fun TestServer.publish(org: Uuid, vararg events: OrgEventDraft) =
        koin.get<OrgEventPublisher>().publish(org, events.toList())

    private suspend fun TestServer.readers(org: Uuid): Long = database.superuser.fetchAll(
        "SELECT count(*) AS n FROM events_readers WHERE org_id = '$org'",
    ).getOrThrow().first().get("n").asLong()

    @Test
    fun aReaderStartsAtTheHeadThenReadsItsTypesPageByPage() = serverTest(listOf(EventsModule), overrides) { server ->
        val journal = server.koin.get<OrgEventJournal>()
        assertIs<OrgEventJournalImpl>(journal, "The events module binds it")
        server.publish(orgA, wait("w-1"), wait("w-2"))
        assertEquals(emptyList(), journal.behind(reader, 10), "What came before the reader is not handed to it")

        server.publish(orgA, wait("w-3"), OrgEventDraft(OrgEventType.TODO_UPDATED, "s-1", mia), wait("w-4", null))
        server.publish(orgB, wait("w-5"))
        val position = JournalPosition(orgA, 2, 5)
        assertEquals(listOf(position), journal.behind(reader, 10), "B started at its head when first seen")
        assertEquals(emptyList(), journal.behind("webhooks-2", 10), "Another reader has its own cursors")

        val first = journal.read(position, setOf(OrgEventType.WAIT_CREATED), 1)
        assertEquals(listOf(3L), first.entries.map { it.seq })
        assertEquals(3, first.through, "A full page stops at its last event")
        val entry = first.entries.single()
        assertEquals(OrgEventType.WAIT_CREATED, entry.type)
        assertEquals("w-3", entry.subjectId)
        assertEquals(mia, entry.memberId)
        assertEquals(JsonObject(mapOf("waitId" to JsonPrimitive("w-3"))), entry.data)
        val rest = journal.read(position, setOf(OrgEventType.WAIT_CREATED, OrgEventType.WAIT_RESOLVED), 10)
        assertEquals(listOf(3L, 5L), rest.entries.map { it.seq }, "Other types are skipped")
        assertEquals(null, rest.entries.last().memberId)
        assertEquals(5, rest.through, "and the reader moves to the head")
        assertEquals(5, journal.read(position, emptySet(), 10).through)

        // Rolled back with the reader's work, the cursor stays where it was.
        assertFailsWith<IllegalStateException> {
            server.driver.withOrganization(orgA) { tx ->
                assertTrue(journal.advanceIn(tx, reader, position, 5))
                error("The work fails")
            }
        }
        assertEquals(listOf(position), journal.behind(reader, 10))
        server.driver.withOrganization(orgA) { tx ->
            assertTrue(journal.advanceIn(tx, reader, position, 5))
            assertFalse(journal.advanceIn(tx, reader, position, 5), "A page is handled once")
        }
        assertEquals(emptyList(), journal.behind(reader, 10))
        assertFailsWith<IllegalArgumentException> { journal.behind("Not a reader", 10) }
    }

    @Test
    fun aReaderOnExposedMovesItsCursorInItsOwnTransaction() = serverTest(listOf(EventsModule), overrides) { server ->
        val journal = server.koin.get<OrgEventJournal>()
        val database = server.koin.get<JdbcDatabase>()
        server.publish(orgA, wait("w-1"))
        journal.behind(reader, 10)
        server.publish(orgA, wait("w-2"))
        val position = journal.behind(reader, 10).single()

        // Rolled back with the reader's work, the cursor stays where it was.
        assertFailsWith<IllegalStateException> {
            database.inOrg(orgA) {
                assertTrue(journal.advanceIn(this, reader, position, position.head))
                error("The work fails")
            }
        }
        assertEquals(listOf(position), journal.behind(reader, 10))
        database.inOrg(orgB) {
            assertFalse(journal.advanceIn(this, reader, position, position.head), "Not B's cursor")
        }
        database.inOrg(orgA) {
            assertTrue(journal.advanceIn(this, reader, position, position.head))
            assertFalse(journal.advanceIn(this, reader, position, position.head), "A page is handled once")
        }
        assertEquals(emptyList(), journal.behind(reader, 10))
    }

    @Test
    fun anOrganizationNeverSeesAnothersReadersAndADeletedOneLosesThem() =
        serverTest(listOf(EventsModule), overrides) { server ->
            val journal = server.koin.get<OrgEventJournal>()
            server.publish(orgA, wait("w-1"))
            server.publish(orgB, wait("w-2"))
            journal.behind(reader, 10)
            val seen = server.driver.withOrganization(orgB) { tx ->
                tx.fetchAll(Statement.create("SELECT count(*) AS n FROM events_readers")).getOrThrow()
                    .first().get("n").asLong()
            }
            assertEquals(1, seen, "Row-level security confines events_readers to the transaction's organization")
            server.driver.withOrganization(orgB) { tx ->
                assertFalse(journal.advanceIn(tx, reader, JournalPosition(orgA, 1, 2), 2), "Not A's cursor")
            }

            server.koin.get<OrgEventsRepository>().erase(orgA)
            assertEquals(0, server.readers(orgA))
            assertEquals(1, server.readers(orgB))
        }
}
