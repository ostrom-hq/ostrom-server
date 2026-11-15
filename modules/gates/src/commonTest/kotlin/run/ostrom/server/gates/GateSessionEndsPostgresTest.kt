// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates

import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.gates.domain.services.GateReceiptSigner
import run.ostrom.server.gates.domain.repositories.GatesRepository
import run.ostrom.server.testing.serverTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        val repository = server.koin.get<GatesRepository>()
        repository.transaction(orgA) {
            lockSession(orgA, "s-1")
            assertNull(sessionEndedAt(orgA, "s-1"))
            markSessionEnded(orgA, "s-1", endedAt)
            markSessionEnded(orgA, "s-1", endedAt + WAIT)
        }
        assertEquals(endedAt, repository.transaction(orgA) { sessionEndedAt(orgA, "s-1") }, "The first time stays")
        assertNull(repository.transaction(orgB) { sessionEndedAt(orgB, "s-1") }, "Another organization sees no end")
        repository.transaction(orgB) { markSessionEnded(orgB, "s-1", endedAt + WAIT) }
        assertEquals(endedAt + WAIT, repository.transaction(orgB) { sessionEndedAt(orgB, "s-1") })
        assertEquals(endedAt, repository.transaction(orgA) { sessionEndedAt(orgA, "s-1") }, "Its own row only")
        assertEquals(emptyList(), repository.transaction(orgA) { gatesOfSession(orgA, "s-1") })
    }

    @Test
    fun aSessionsLockHoldsItsOtherTransactionsUntilItEnds() = serverTest(listOf(GatesModule), signer) { server ->
        val repository = server.koin.get<GatesRepository>()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        // On the real clock, each transaction on a thread of its own: they really wait for each other.
        withContext(Dispatchers.Default) {
            coroutineScope {
                val ending = async {
                    repository.transaction(orgA) {
                        lockSession(orgA, "s-2")
                        locked.countDown()
                        check(release.await(HOLD_SECONDS, TimeUnit.SECONDS)) { "Never released" }
                        markSessionEnded(orgA, "s-2", endedAt)
                    }
                }
                check(locked.await(HOLD_SECONDS, TimeUnit.SECONDS)) { "Never locked" }
                val asking = async {
                    repository.transaction(orgA) {
                        lockSession(orgA, "s-2")
                        sessionEndedAt(orgA, "s-2")
                    }
                }
                delay(WAIT)
                assertFalse(asking.isCompleted, "The second transaction waits for the session's lock")
                release.countDown()
                ending.await()
                assertEquals(endedAt, asking.await(), "It reads the end once the first transaction committed")
            }
        }
    }

    private companion object {
        val WAIT = 300.milliseconds
        const val HOLD_SECONDS = 10L
    }
}
