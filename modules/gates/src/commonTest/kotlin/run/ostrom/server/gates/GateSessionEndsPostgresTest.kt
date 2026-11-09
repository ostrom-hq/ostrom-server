// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gates

import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.gates.domain.services.GateReceiptSigner
import run.ostrom.server.gates.infrastructure.PostgresGatesRepository
import run.ostrom.server.testing.serverTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * AUDIT-SERVEUR-3 B2 on PostgreSQL: the lock of a session holds every other transaction of the same session until it
 * ends, and the mark of its end (`gates_session_ends`) keeps its first time and is its organization's only.
 */
class GateSessionEndsPostgresTest {
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val orgB = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    private val endedAt = Instant.parse("2026-11-03T19:00:00Z")

    // The routes need a receipt signer; no receipt is signed here.
    private val signer = module {
        single<GateReceiptSigner> {
            object : GateReceiptSigner {
                override suspend fun sign(gate: Gate): String = "rcpt_${gate.id}"
            }
        }
    }

    @Test
    fun theEndOfASessionIsMarkedOnceInItsOrganization() = serverTest(listOf(GatesModule), signer) { server ->
        val repository = PostgresGatesRepository(server.driver)
        repository.transaction {
            lockSession(orgA, "s-1")
            assertNull(sessionEndedAt(orgA, "s-1"))
            markSessionEnded(orgA, "s-1", endedAt)
            markSessionEnded(orgA, "s-1", endedAt + WAIT)
        }
        assertEquals(endedAt, repository.transaction { sessionEndedAt(orgA, "s-1") }, "The first time stays")
        assertNull(repository.transaction { sessionEndedAt(orgB, "s-1") }, "Another organization sees no end")
        repository.transaction { markSessionEnded(orgB, "s-1", endedAt + WAIT) }
        assertEquals(endedAt + WAIT, repository.transaction { sessionEndedAt(orgB, "s-1") })
        assertEquals(endedAt, repository.transaction { sessionEndedAt(orgA, "s-1") }, "Its own row only")
        assertEquals(emptyList(), repository.transaction { gatesOfSession(orgA, "s-1") })
    }

    @Test
    fun aSessionsLockHoldsItsOtherTransactionsUntilItEnds() = serverTest(listOf(GatesModule), signer) { server ->
        val repository = PostgresGatesRepository(server.driver)
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coroutineScope {
            val ending = async {
                repository.transaction {
                    lockSession(orgA, "s-2")
                    locked.complete(Unit)
                    release.await()
                    markSessionEnded(orgA, "s-2", endedAt)
                }
            }
            locked.await()
            val asking = async {
                repository.transaction {
                    lockSession(orgA, "s-2")
                    sessionEndedAt(orgA, "s-2")
                }
            }
            delay(WAIT)
            assertFalse(asking.isCompleted, "The second transaction waits for the session's lock")
            release.complete(Unit)
            ending.await()
            assertEquals(endedAt, asking.await(), "It reads the end once the first transaction committed")
        }
    }

    private companion object {
        val WAIT = 300.milliseconds
    }
}
