// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.runners.domain.BackgroundWork
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CloudTransfer
import run.ostrom.server.runners.domain.CompleteCloudTransferUseCase
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.ExecutionStatus
import run.ostrom.server.runners.domain.GitHubAccessMode
import run.ostrom.server.runners.domain.ReconcileCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.RepositoryCloudUsageMeter
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.domain.TransferState
import run.ostrom.server.runners.domain.TransferRecipient
import run.ostrom.server.runners.fakes.FakeRunnerTokens
import run.ostrom.server.runners.fakes.FakeRuntimeDriver
import run.ostrom.server.runners.fakes.FakeTransferRecipients
import run.ostrom.server.runners.fakes.FixedPlans
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.InMemoryCloudTransfersRepository
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** The reconciler's side of a transfer: the owner changes only at a paused boundary, with nothing left running. */
class CompleteCloudTransferUseCaseTest {
    private val repository = InMemoryCloudSessionsRepository()
    private val transfers = InMemoryCloudTransfersRepository(repository)
    private val driver = FakeRuntimeDriver().also { it.status = ExecutionStatus.MISSING }
    private val recipients = FakeTransferRecipients(TransferRecipient("bob", "bob", false))
    private val clock = SettableClock()
    private val audit = mutableListOf<OrgAuditRecord>()
    private val complete =
        CompleteCloudTransferUseCase(repository, transfers, driver, recipients, OrgAuditTrail { audit += it }, clock)
    private val reconcile = ReconcileCloudSessionUseCaseImpl(
        repository,
        driver,
        FakeRunnerTokens(),
        FixedPlans(),
        RepositoryCloudUsageMeter(repository),
        clock,
        transfers = complete,
    ) { BackgroundWork.DEFAULT_MAX_MINUTES }
    private val t0 = clock.now

    private fun transfer(
        session: CloudSession,
        resume: Boolean = false,
        state: TransferState = TransferState.ACCEPTED,
    ) =
        CloudTransfer(
            id = Uuid.random(),
            orgId = ORG_A,
            sessionId = session.id,
            fromMemberId = "alice",
            toMemberId = "bob",
            initiatedBy = "alice",
            state = state,
            resume = resume,
            version = 2,
            createdAt = t0,
            updatedAt = t0,
        ).also { transfers.transfers[it.id] = it }

    private fun moving(
        state: SessionState = SessionState.PAUSED,
        resume: Boolean = false,
    ): Pair<CloudSession, CloudTransfer> {
        val base = session(state = state, desired = DesiredState.PAUSED)
            .copy(epoch = 3, githubAccess = GitHubAccessMode.SHARED)
        val transfer = transfer(base, resume)
        val stored = base.copy(transferId = transfer.id).also(repository::put)
        return stored to transfer
    }

    private fun stored(session: CloudSession) = repository.sessions.getValue(session.id)

    @Test
    fun aPausedSessionWithAnAcceptedTransferEntersTransferring() = runTest {
        val (paused, transfer) = moving()

        assertEquals(t0, reconcile(paused))

        assertEquals(SessionState.TRANSFERRING, stored(paused).state)
        assertEquals(CompleteCloudTransferUseCase.PHASE, stored(paused).phase)
        assertEquals(transfer.id, stored(paused).transferId)
        assertEquals(3, transfers.transfers.getValue(transfer.id).version)
    }

    @Test
    fun theOwnerChangesWithNothingRunningAndTheWorkspaceIsUntrusted() = runTest {
        val (paused, transfer) = moving()
        reconcile(paused)

        reconcile(stored(paused))

        val after = stored(paused)
        assertEquals("bob", after.ownerId)
        assertEquals(SessionState.PAUSED, after.state)
        assertEquals(DesiredState.PAUSED, after.desiredState)
        assertNull(after.transferId)
        assertTrue(after.untrustedWorkspace)
        assertEquals(GitHubAccessMode.OWNER, after.githubAccess)
        val done = transfers.transfers.getValue(transfer.id)
        assertEquals(TransferState.COMPLETED, done.state)
        assertEquals(3L, done.atEpoch)
        assertEquals(t0, done.completedAt)
        assertTrue("stop" in driver.calls)
        assertEquals(listOf("cloud.session.transferred"), audit.map { it.event })
        assertEquals("alice", audit.single().details["from"])
        // Nothing follows: the new owner resumes it when they want.
        assertNull(reconcile(after))
    }

    @Test
    fun aTransferThatResumesStartsTheRecipientsExecution() = runTest {
        val (paused, _) = moving(resume = true)
        reconcile(paused)

        reconcile(stored(paused))

        val after = stored(paused)
        assertEquals("bob", after.ownerId)
        assertEquals(SessionState.RESUMING, after.state)
        assertEquals(DesiredState.RUNNING, after.desiredState)
        assertTrue(after.untrustedWorkspace)
    }

    @Test
    fun theOwnerDoesNotChangeWhileAnExecutionIsStillThere() = runTest {
        val (paused, transfer) = moving()
        reconcile(paused)
        driver.status = ExecutionStatus.RUNNING

        assertEquals(t0 + 2.seconds, reconcile(stored(paused)))

        assertEquals("alice", stored(paused).ownerId)
        assertEquals(TransferState.ACCEPTED, transfers.transfers.getValue(transfer.id).state)
        driver.status = ExecutionStatus.ENDED
        reconcile(stored(paused))
        assertEquals("bob", stored(paused).ownerId)
    }

    @Test
    fun aRecipientWhoLeftFailsTheTransferAndTheSessionStaysWithItsOwner() = runTest {
        val (paused, transfer) = moving()
        reconcile(paused)
        recipients.members.clear()

        reconcile(stored(paused))

        val after = stored(paused)
        assertEquals("alice", after.ownerId)
        assertEquals(SessionState.PAUSED, after.state)
        assertNull(after.transferId)
        assertFalse(after.untrustedWorkspace)
        val failed = transfers.transfers.getValue(transfer.id)
        assertEquals(TransferState.FAILED, failed.state)
        assertEquals("recipient_unavailable", failed.failure?.code)
        assertTrue(audit.isEmpty())
    }

    @Test
    fun aTransferringSessionThatNeverFinishesFailsWithATimeoutBeforeAnyRuntimeCall() = runTest {
        val (paused, transfer) = moving()
        reconcile(paused)
        driver.calls.clear()
        clock.now += CloudTransfer.DEADLINE + 1.minutes

        reconcile(stored(paused))

        assertEquals("transfer_timeout", transfers.transfers.getValue(transfer.id).failure?.code)
        assertEquals(SessionState.PAUSED, stored(paused).state)
        assertEquals("alice", stored(paused).ownerId)
        assertFalse("stop" in driver.calls)
    }

    @Test
    fun aTransferThatIsNoLongerAcceptedIsDroppedFromTheSession() = runTest {
        val (paused, transfer) = moving()
        transfers.transfers[transfer.id] = transfer.copy(state = TransferState.CANCELLED)
        reconcile(paused)
        assertNull(stored(paused).transferId)
        assertEquals(SessionState.PAUSED, stored(paused).state)

        val (second, other) = moving(state = SessionState.TRANSFERRING)
        transfers.transfers[other.id] = other.copy(state = TransferState.CANCELLED)
        reconcile(second)
        assertNull(stored(second).transferId)
        assertEquals(SessionState.PAUSED, stored(second).state)
    }

    @Test
    fun deletingASessionCancelsItsAcceptedTransferThenDeletesIt() = runTest {
        val (paused, transfer) = moving(state = SessionState.TRANSFERRING)
        repository.put(paused.copy(desiredState = DesiredState.TERMINATED))

        reconcile(stored(paused))

        val cancelled = transfers.transfers.getValue(transfer.id)
        assertEquals(TransferState.CANCELLED, cancelled.state)
        assertEquals("session_not_running", cancelled.failure?.code)
        assertEquals(SessionState.PAUSED, stored(paused).state)
        assertNull(stored(paused).transferId)

        reconcile(stored(paused))
        assertEquals(SessionState.TERMINATED, stored(paused).state)

        val (other, none) = moving()
        transfers.transfers.remove(none.id)
        repository.put(other.copy(desiredState = DesiredState.TERMINATED))
        reconcile(stored(other))
        assertNull(stored(other).transferId)
    }

    @Test
    fun aRunningSessionPausedForATransferIsLookedAtAgainOnceItIsPaused() = runTest {
        val base = session(state = SessionState.RUNNING, desired = DesiredState.PAUSED)
            .copy(epoch = 1, executionStartedAt = t0, readyAt = t0, lastHeartbeatAt = t0, lastActivityAt = t0)
        val transfer = transfer(base)
        val pausing = base.copy(
            state = SessionState.PAUSING,
            pausingSince = t0,
            runnerStoppedAt = t0,
            transferId = transfer.id,
        )
        repository.put(pausing)

        assertEquals(t0, reconcile(pausing))

        assertEquals(SessionState.PAUSED, stored(pausing).state)
        assertEquals(t0, repository.next[pausing.id])
    }

    @Test
    fun aSessionThatPausesBeforeItsFirstReadyIsLookedAtAgainToo() = runTest {
        val base = session(state = SessionState.CREATING, desired = DesiredState.PAUSED)
        val transfer = transfer(base)
        val creating = base.copy(transferId = transfer.id).also(repository::put)

        assertEquals(t0, reconcile(creating))
        assertEquals(SessionState.PAUSED, stored(creating).state)
    }

    @Test
    fun aServerWithoutTransfersReturnsAStrandedSessionToItsOwner() = runTest {
        val (transferring, _) = moving(state = SessionState.TRANSFERRING)
        val bare = ReconcileCloudSessionUseCaseImpl(
            repository,
            driver,
            FakeRunnerTokens(),
            FixedPlans(),
            RepositoryCloudUsageMeter(repository),
            clock,
        ) { BackgroundWork.DEFAULT_MAX_MINUTES }

        bare(transferring)

        assertEquals(SessionState.PAUSED, stored(transferring).state)
        assertNull(stored(transferring).transferId)
        assertEquals("alice", stored(transferring).ownerId)
    }

    private val bare = ReconcileCloudSessionUseCaseImpl(
        repository,
        driver,
        FakeRunnerTokens(),
        FixedPlans(),
        RepositoryCloudUsageMeter(repository),
        clock,
    ) { BackgroundWork.DEFAULT_MAX_MINUTES }

    @Test
    fun aGitHubAccessThatWasNotSharedStaysWhatItWas() = runTest {
        val base = session(state = SessionState.PAUSED, desired = DesiredState.PAUSED)
            .copy(githubAccess = GitHubAccessMode.OWNER)
        val transfer = transfer(base)
        val paused = base.copy(transferId = transfer.id).also(repository::put)
        reconcile(paused)

        reconcile(stored(paused))

        assertEquals("bob", stored(paused).ownerId)
        assertEquals(GitHubAccessMode.OWNER, stored(paused).githubAccess)
    }

    @Test
    fun aSessionThatChangedMeanwhileDoesNotCompleteTheTransferNorLeaveAnAuditLine() = runTest {
        val (paused, transfer) = moving()
        reconcile(paused)
        repository.loseNextRaces = 1

        reconcile(stored(paused))

        assertEquals("alice", stored(paused).ownerId)
        assertEquals(TransferState.ACCEPTED, transfers.transfers.getValue(transfer.id).state)
        assertTrue(audit.isEmpty())
    }

    @Test
    fun abandoningASessionWithoutATransferOrWithADecidedOneJustReleasesIt() = runTest {
        val plain = session(state = SessionState.PAUSED, desired = DesiredState.TERMINATED).also(repository::put)
        complete.abandon(plain)
        assertNull(stored(plain).transferId)

        val (paused, transfer) = moving()
        transfers.transfers[transfer.id] = transfer.copy(state = TransferState.COMPLETED)
        complete.abandon(paused)
        assertNull(stored(paused).transferId)
        assertEquals(TransferState.COMPLETED, transfers.transfers.getValue(transfer.id).state)
    }

    @Test
    fun aTransferOfAnotherSessionIsNotThisSessionsTransfer() = runTest {
        val other = session(state = SessionState.PAUSED, desired = DesiredState.PAUSED)
        val foreign = transfer(other)
        val paused = session(state = SessionState.PAUSED, desired = DesiredState.PAUSED)
            .copy(transferId = foreign.id).also(repository::put)

        reconcile(paused)

        assertNull(stored(paused).transferId)
        assertEquals(TransferState.ACCEPTED, transfers.transfers.getValue(foreign.id).state)
    }

    @Test
    fun aServerWithoutTransfersDeletesAStrandedSessionAndReturnsAPausedOneToItsOwner() = runTest {
        val (paused, _) = moving()
        bare(paused)
        assertNull(stored(paused).transferId)
        assertEquals(SessionState.PAUSED, stored(paused).state)

        val (deleted, transfer) = moving()
        repository.put(deleted.copy(desiredState = DesiredState.TERMINATED))
        bare(stored(deleted))
        // Nothing here abandons the transfer: the session is simply deleted.
        assertEquals(TransferState.ACCEPTED, transfers.transfers.getValue(transfer.id).state)
    }

    @Test
    fun aFirstStartThatFailsKeepsTheTransferNamedByTheSessionUntilItIsReleased() = runTest {
        val start = session().copy(
            epoch = 1,
            executionStartedAt = t0,
            runnerTokenHash = "hash(token-1)",
            transferId = Uuid.random(),
        )
        repository.put(start)
        driver.status = ExecutionStatus.FAILED

        assertNull(reconcile(start))
        assertEquals(SessionState.FAILED, stored(start).state)
    }

    @Test
    fun aSessionNamingNoTransferOrOneNobodyHoldsReturnsToItsOwner() = runTest {
        val unknown = session(state = SessionState.PAUSED, desired = DesiredState.PAUSED)
            .copy(transferId = Uuid.random()).also(repository::put)
        reconcile(unknown)
        assertNull(stored(unknown).transferId)

        val lost = session(state = SessionState.TRANSFERRING, desired = DesiredState.PAUSED).also(repository::put)
        reconcile(lost)
        assertEquals(SessionState.PAUSED, stored(lost).state)

        // Without the transfers wiring, a transferring session with no transfer is only watched.
        val stranded = session(state = SessionState.TRANSFERRING, desired = DesiredState.PAUSED)
            .also(repository::put)
        assertEquals(t0 + 15.seconds, bare(stranded))
    }
}
