// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import kotlinx.coroutines.test.runTest
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.runners.domain.CloudTransfer
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.SessionChange
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.domain.TransferCompletion
import run.ostrom.server.runners.domain.TransferDirection
import run.ostrom.server.runners.domain.TransferOpening
import run.ostrom.server.runners.domain.TransferQuery
import run.ostrom.server.runners.domain.TransferState
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.ORG_B
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.testing.TestDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The transfers' SQL on PostgreSQL: the session's compare-and-set, the one open transfer, the key and the tenant. */
class CloudTransfersPostgresTest {
    private val now = Instant.parse("2026-11-14T10:00:00Z")

    private fun transfer(
        sessionId: Uuid,
        key: String? = null,
        digest: String? = null,
        org: Uuid = ORG_A,
    ) = CloudTransfer(
        id = Uuid.random(),
        orgId = org,
        sessionId = sessionId,
        fromMemberId = "alice",
        toMemberId = "bob",
        initiatedBy = "alice",
        state = TransferState.OFFERED,
        resume = false,
        version = 1,
        createdAt = now,
        updatedAt = now,
        expiresAt = now + 7.hours,
        idempotencyKey = key,
        requestDigest = digest,
    )

    @Test
    fun aTransferIsOpenedReplayedCompletedAndKeptInItsTenant() = runTest {
        val database = TestDatabase.create() ?: return@runTest
        try {
            database.migrationRunner(JsonLogger(LogLevel.WARN)).migrate("runners", RunnersMigrations)
            val sessions = CloudSessionsRepositoryImpl(database.driver)
            val transfers = CloudTransfersRepositoryImpl(database.driver, sessions)
            val paused = session(state = SessionState.PAUSED, desired = DesiredState.PAUSED)
            check(sessions.createWithinCap(paused, 10))
            val stored = sessions.get(ORG_A, paused.id)!!

            val first = transfer(paused.id, "k-1", "digest")
            assertIs<TransferOpening.Opened>(transfers.open(first, now, null))
            assertEquals(first.id, transfers.find(ORG_A, first.id)?.id)
            assertNull(transfers.find(ORG_B, first.id))

            // The same key and request replays; another request under the key conflicts; the session holds one open.
            val replayed = transfers.open(transfer(paused.id, "k-1", "digest"), now, null)
            assertEquals(first.id, assertIs<TransferOpening.Replayed>(replayed).transfer.id)
            assertIs<TransferOpening.KeyConflict>(transfers.open(transfer(paused.id, "k-1", "other"), now, null))
            assertIs<TransferOpening.Pending>(transfers.open(transfer(paused.id, "k-2", "digest"), now, null))
            val incoming = TransferQuery("bob", TransferDirection.INCOMING, emptySet(), null, null, 10)
            assertEquals(listOf(first.id), transfers.list(ORG_A, incoming).map { it.id })
            val both = TransferQuery("bob", TransferDirection.BOTH, emptySet(), null, null, 10)
            assertEquals(emptyList(), transfers.list(ORG_B, both))

            // Accepting moves the session to paused-for-transfer in the same transaction.
            val accepted = first.copy(state = TransferState.ACCEPTED, version = 2)
            val change = SessionChange(stored, stored.copy(transferId = first.id, updatedAt = now), now)
            assertEquals(accepted.state, transfers.update(first, accepted, change)?.state)
            assertEquals(first.id, sessions.get(ORG_A, paused.id)?.transferId)
            // A stale session loses and writes nothing.
            assertNull(transfers.update(accepted, accepted.copy(version = 3), change))

            val moving = sessions.get(ORG_A, paused.id)!!
            val done = accepted.copy(state = TransferState.COMPLETED, version = 3, completedAt = now)
            val owned = moving.copy(ownerId = "bob", transferId = null, untrustedWorkspace = true, updatedAt = now)
            assertIs<TransferCompletion.Completed>(transfers.complete(moving, owned, done))
            val after = sessions.get(ORG_A, paused.id)!!
            assertEquals("bob", after.ownerId)
            assertEquals(true, after.untrustedWorkspace)
            assertNull(after.transferId)
            assertEquals(TransferState.COMPLETED, transfers.find(ORG_A, first.id)?.state)
            assertIs<TransferCompletion.Conflict>(transfers.complete(moving, owned, done))
        } finally {
            database.close()
        }
    }
}
