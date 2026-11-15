// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import io.github.smyrgeorge.sqlx4k.Statement
import kotlinx.coroutines.test.runTest
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.runners.domain.CloudTransfer
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.GitHubAccessMode
import run.ostrom.server.runners.domain.SessionChange
import run.ostrom.server.runners.domain.SessionFailure
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
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
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
                .copy(githubAccess = GitHubAccessMode.SHARED)
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
            val owned = moving.copy(ownerId = "bob", transferId = null, untrustedWorkspace = true,
                githubAccess = GitHubAccessMode.OWNER, updatedAt = now)
            assertIs<TransferCompletion.Completed>(transfers.complete(moving, owned, done))
            val after = sessions.get(ORG_A, paused.id)!!
            assertEquals("bob", after.ownerId)
            assertEquals(GitHubAccessMode.OWNER, after.githubAccess)
            assertEquals(true, after.untrustedWorkspace)
            assertNull(after.transferId)
            assertEquals(TransferState.COMPLETED, transfers.find(ORG_A, first.id)?.state)
            assertIs<TransferCompletion.Conflict>(transfers.complete(moving, owned, done))
        } finally {
            database.close()
        }
    }

    private suspend fun repositories(
        database: TestDatabase,
    ): Pair<CloudSessionsRepositoryImpl, CloudTransfersRepositoryImpl> {
        database.migrationRunner(JsonLogger(LogLevel.WARN)).migrate("runners", RunnersMigrations)
        val sessions = CloudSessionsRepositoryImpl(database.driver)
        return sessions to CloudTransfersRepositoryImpl(database.driver, sessions)
    }

    private suspend fun pausedSession(sessions: CloudSessionsRepositoryImpl) =
        session(state = SessionState.PAUSED, desired = DesiredState.PAUSED)
            .also { check(sessions.createWithinCap(it, 50)) }

    @Test
    fun transfersAreListedByDirectionStateSessionAndCursor() = runTest {
        val database = TestDatabase.create() ?: return@runTest
        try {
            val (sessions, transfers) = repositories(database)
            val first = pausedSession(sessions)
            val second = pausedSession(sessions)
            val older = transfer(first.id).copy(createdAt = now - 2.hours, updatedAt = now - 2.hours)
            val newer = transfer(second.id).copy(fromMemberId = "carol", initiatedBy = "carol", toMemberId = "alice")
            assertIs<TransferOpening.Opened>(transfers.open(older, now, null))
            assertIs<TransferOpening.Opened>(transfers.open(newer, now, null))

            fun query(
                direction: TransferDirection,
                states: Set<TransferState> = emptySet(),
                session: Uuid? = null,
                before: Uuid? = null,
                limit: Int = 10,
            ) = TransferQuery("alice", direction, states, session, before, limit)
            suspend fun ids(query: TransferQuery) = transfers.list(ORG_A, query).map { it.id }

            assertEquals(listOf(newer.id, older.id), ids(query(TransferDirection.BOTH)))
            assertEquals(listOf(older.id), ids(query(TransferDirection.OUTGOING)))
            assertEquals(listOf(newer.id), ids(query(TransferDirection.INCOMING)))
            assertEquals(
                listOf(older.id),
                ids(query(TransferDirection.BOTH, setOf(TransferState.OFFERED), session = first.id)),
            )
            assertEquals(emptyList(), ids(query(TransferDirection.BOTH, setOf(TransferState.DECLINED))))
            assertEquals(listOf(older.id), ids(query(TransferDirection.BOTH, before = newer.id)))
            assertEquals(listOf(newer.id), ids(query(TransferDirection.BOTH, limit = 1)))
        } finally {
            database.close()
        }
    }

    @Test
    fun keysAreKeptThenForgottenAndExpiredOffersStopHoldingTheirSession() = runTest {
        val database = TestDatabase.create() ?: return@runTest
        try {
            val (sessions, transfers) = repositories(database)
            val target = pausedSession(sessions)
            // A session nobody has is a conflict.
            assertIs<TransferOpening.Conflict>(transfers.open(transfer(Uuid.random()), now, null))
            // A session change that lost its race too: nothing is stored.
            val stored = sessions.get(ORG_A, target.id)!!
            val stale = SessionChange(stored, stored.copy(transferId = Uuid.random(), updatedAt = now), now)
            check(sessions.compareAndSet(stored.copy(updatedAt = now + 1.minutes), null) != null)
            assertIs<TransferOpening.Conflict>(transfers.open(transfer(target.id), now, stale))

            val first = transfer(target.id, "k", "d")
            assertIs<TransferOpening.Opened>(transfers.open(first, now, null))
            val declined = first.copy(state = TransferState.DECLINED, version = 2)
            assertEquals(TransferState.DECLINED, transfers.update(first, declined)?.state)
            // A write on a version that is not the stored one is lost.
            assertNull(transfers.update(first, declined.copy(version = 3)))
            assertIs<TransferOpening.Replayed>(transfers.open(transfer(target.id, "k", "d"), now + 1.hours, null))

            val later = now + 2.days
            val again = transfer(target.id, "k", "d")
                .copy(createdAt = later, updatedAt = later, expiresAt = later + 7.hours)
            assertIs<TransferOpening.Opened>(transfers.open(again, later, null))
            assertNull(transfers.find(ORG_A, first.id)?.idempotencyKey)

            val third = transfer(target.id, "k-3", "d-3")
                .copy(createdAt = later + 8.hours, updatedAt = later + 8.hours, expiresAt = later + 20.hours)
            assertIs<TransferOpening.Opened>(transfers.open(third, later + 8.hours, null))
            assertEquals(TransferState.EXPIRED, transfers.find(ORG_A, again.id)?.state)

            val failed = third.copy(
                state = TransferState.FAILED,
                version = 2,
                failure = SessionFailure("transfer_timeout", "Too slow."),
            )
            assertEquals("Too slow.", transfers.update(third, failed)?.failure?.message)

            // A state this version does not know reads as a failed transfer, never an open one.
            database.superuser.execute(
                Statement.create("UPDATE runners_session_transfers SET state = 'weird' WHERE id = :id")
                    .bind("id", again.id),
            ).getOrThrow()
            assertEquals(TransferState.FAILED, transfers.find(ORG_A, again.id)?.state)
        } finally {
            database.close()
        }
    }

    @Test
    fun anOwnerChangeThatLostTheSessionsRaceLeavesTheTransferAccepted() = runTest {
        val database = TestDatabase.create() ?: return@runTest
        try {
            val (sessions, transfers) = repositories(database)
            val target = pausedSession(sessions)
            val accepted = transfer(target.id).copy(state = TransferState.ACCEPTED)
            assertIs<TransferOpening.Opened>(transfers.open(accepted, now, null))
            val stored = sessions.get(ORG_A, target.id)!!
            check(sessions.compareAndSet(stored.copy(updatedAt = now + 1.minutes), null) != null)

            val done = accepted.copy(state = TransferState.COMPLETED, version = 2, completedAt = now)
            val owned = stored.copy(ownerId = "bob", updatedAt = now)
            assertIs<TransferCompletion.Conflict>(transfers.complete(stored, owned, done))
            assertEquals(TransferState.ACCEPTED, transfers.find(ORG_A, accepted.id)?.state)
            assertEquals("alice", sessions.get(ORG_A, target.id)?.ownerId)
        } finally {
            database.close()
        }
    }

    @Test
    fun anAdministratorsTransferAndItsSessionChangeAreStoredTogether() = runTest {
        val database = TestDatabase.create() ?: return@runTest
        try {
            val (sessions, transfers) = repositories(database)
            val target = pausedSession(sessions)
            val stored = sessions.get(ORG_A, target.id)!!
            val change = SessionChange(stored, stored.copy(transferId = null, updatedAt = now), now)
            val failed = transfer(target.id, "k", "d").copy(
                state = TransferState.FAILED,
                failure = SessionFailure("recipient_unavailable", "Gone."),
            )
            assertIs<TransferOpening.Opened>(transfers.open(failed, now, change))
            assertEquals("recipient_unavailable", transfers.find(ORG_A, failed.id)?.failure?.code)
            assertEquals(1, sessions.get(ORG_A, target.id)?.generation)
        } finally {
            database.close()
        }
    }
}
