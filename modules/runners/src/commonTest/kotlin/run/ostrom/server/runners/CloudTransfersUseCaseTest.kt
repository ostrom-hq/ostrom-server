// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CloudSessionNotFoundException
import run.ostrom.server.runners.domain.CloudTransfer
import run.ostrom.server.runners.domain.CloudTransfersUseCase
import run.ostrom.server.runners.domain.CloudTransfersUseCaseImpl
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.IdempotencyConflictException
import run.ostrom.server.runners.domain.InvalidTransferException
import run.ostrom.server.runners.domain.RecipientIneligibleException
import run.ostrom.server.runners.domain.SessionNotRunningException
import run.ostrom.server.runners.domain.SessionPreparingException
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.domain.TransferDirection
import run.ostrom.server.runners.domain.TransferDisabledException
import run.ostrom.server.runners.domain.TransferExpiredException
import run.ostrom.server.runners.domain.TransferForbiddenException
import run.ostrom.server.runners.domain.TransferIdempotency
import run.ostrom.server.runners.domain.TransferInProgressException
import run.ostrom.server.runners.domain.TransferListing
import run.ostrom.server.runners.domain.TransferNotFoundException
import run.ostrom.server.runners.domain.TransferNotOpenException
import run.ostrom.server.runners.domain.TransferPendingException
import run.ostrom.server.runners.domain.TransferRecipient
import run.ostrom.server.runners.domain.TransferRequest
import run.ostrom.server.runners.domain.TransferState
import run.ostrom.server.runners.fakes.FakeTransferRecipients
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.InMemoryCloudTransfersRepository
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.SwitchedTransferPolicy
import run.ostrom.server.runners.fakes.session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/** Ownership transfers (TeamCode's `CloudTransferService`): who may transfer, the offer, the decisions, the reads. */
class CloudTransfersUseCaseTest {
    private val sessions = InMemoryCloudSessionsRepository()
    private val transfers = InMemoryCloudTransfersRepository(sessions)
    private val recipients = FakeTransferRecipients(
        TransferRecipient("alice", "alice", false),
        TransferRecipient("bob", "bob", false),
        TransferRecipient("carol", "carol", true),
    )
    private val policy = SwitchedTransferPolicy()
    private val clock = SettableClock()
    private val audit = mutableListOf<OrgAuditRecord>()
    private val principal = AuthenticatedPrincipal("alice", PrincipalKind.USER, emptySet())
    private val use: CloudTransfersUseCase =
        CloudTransfersUseCaseImpl(sessions, transfers, recipients, policy, OrgAuditTrail { audit += it }, clock)

    private val alice = Caller(ORG_A, "alice")
    private val bob = Caller(ORG_A, "bob")
    private val carol = Caller(ORG_A, "carol", admin = true)
    private val key = TransferIdempotency("k-1", "digest-1")

    private fun running(owner: String = "alice", state: SessionState = SessionState.RUNNING) =
        session(state = state, desired = DesiredState.RUNNING, ownerId = owner).copy(epoch = 1)
            .also(sessions::put)

    private suspend fun offer(session: run.ostrom.server.runners.domain.CloudSession = running()) =
        use.request(alice, principal, session.id, TransferRequest("bob", resume = false), key).transfer

    @Test
    fun anOwnerWhoIsNotAnAdministratorOffersAndTheSessionIsLeftAlone() = runTest {
        val running = running()
        val result = use.request(alice, principal, running.id, TransferRequest("bob", true), key)

        val transfer = result.transfer
        assertFalse(result.replayed)
        assertEquals(TransferState.OFFERED, transfer.state)
        assertEquals("alice" to "bob", transfer.fromMemberId to transfer.toMemberId)
        assertEquals(clock.now + CloudTransfer.OFFER_VALIDITY, transfer.expiresAt)
        assertTrue(transfer.resume)
        assertNull(sessions.sessions.getValue(running.id).transferId)
        assertEquals(listOf("cloud.transfer.offered"), audit.map { it.event })
    }

    @Test
    fun anAdministratorsTransferIsAcceptedAtOnceAndTheSessionPausesTowardIt() = runTest {
        val own = running(owner = "carol")
        val transfer = use.request(carol, principal, own.id, TransferRequest("bob", false), key).transfer

        assertEquals(TransferState.ACCEPTED, transfer.state)
        assertNull(transfer.expiresAt)
        val moving = sessions.sessions.getValue(own.id)
        assertEquals(transfer.id, moving.transferId)
        assertEquals(DesiredState.PAUSED, moving.desiredState)
        assertEquals(clock.now, sessions.next[own.id])
        assertEquals(listOf("cloud.transfer.requested"), audit.map { it.event })
    }

    @Test
    fun theRecipientCanBeNamedByLoginAndOnlyTheOwnerGivesASessionAway() = runTest {
        val running = running()
        val offered = use.request(alice, principal, running.id, TransferRequest("bob", false), key)
        assertEquals("bob", offered.transfer.toMemberId)
        assertFailsWith<CloudSessionNotFoundException> {
            use.request(bob, principal, running.id, TransferRequest("carol", false), key.copy(key = "k-2"))
        }
        assertFailsWith<CloudSessionNotFoundException> {
            use.request(alice, principal, Uuid.random(), TransferRequest("bob", false), key.copy(key = "k-3"))
        }
    }

    @Test
    fun aSessionThatCannotMoveIsRefusedWithItsReason() = runTest {
        assertFailsWith<SessionPreparingException> {
            use.request(alice, principal, running(state = SessionState.CREATING).id, TransferRequest("bob", false), key)
        }
        assertFailsWith<SessionNotRunningException> {
            use.request(
                alice,
                principal,
                running(state = SessionState.TERMINATED).id,
                TransferRequest("bob", false),
                key,
            )
        }
        val deleting = running().copy(desiredState = DesiredState.TERMINATED).also(sessions::put)
        assertFailsWith<SessionNotRunningException> {
            use.request(alice, principal, deleting.id, TransferRequest("bob", false), key)
        }
    }

    @Test
    fun theRecipientMustDifferAndStillBeAMember() = runTest {
        val running = running()
        assertFailsWith<InvalidTransferException> {
            use.request(alice, principal, running.id, TransferRequest("alice", false), key)
        }
        val refused = assertFailsWith<RecipientIneligibleException> {
            use.request(alice, principal, running.id, TransferRequest("nobody", false), key)
        }
        assertTrue("recipient_not_found" in refused.message)
    }

    @Test
    fun membersOfferOnlyWhileTheInstanceAllowsItAndAdministratorsAlways() = runTest {
        policy.membersOffer = false
        assertFailsWith<TransferDisabledException> {
            use.request(alice, principal, running().id, TransferRequest("bob", false), key)
        }
        assertFailsWith<TransferDisabledException> { use.recipients(alice) }
        assertEquals(3, use.recipients(carol).size)
        val byAdmin = use.request(carol, principal, running("carol").id, TransferRequest("bob", false), key)
        assertEquals(TransferState.ACCEPTED, byAdmin.transfer.state)
    }

    @Test
    fun aSessionHasOneOpenTransferAndAnExpiredOfferNoLongerHoldsIt() = runTest {
        val running = running()
        offer(running)
        assertFailsWith<TransferPendingException> {
            use.request(alice, principal, running.id, TransferRequest("carol", false), key.copy(key = "k-2"))
        }
        clock.now += CloudTransfer.OFFER_VALIDITY + 1.days
        val again = use.request(
            alice,
            principal,
            running.id,
            TransferRequest("carol", false),
            key.copy(key = "k-3"),
        ).transfer
        assertEquals("carol", again.toMemberId)
        assertEquals(1, transfers.transfers.values.count { it.state == TransferState.EXPIRED })
    }

    @Test
    fun theSameKeyAndRequestReplaysAndAnotherRequestConflicts() = runTest {
        val running = running()
        val first = use.request(alice, principal, running.id, TransferRequest("bob", false), key)
        val replay = use.request(alice, principal, running.id, TransferRequest("bob", false), key)
        assertTrue(replay.replayed)
        assertEquals(first.transfer.id, replay.transfer.id)
        assertEquals(1, audit.size)
        assertFailsWith<IdempotencyConflictException> {
            use.request(alice, principal, running.id, TransferRequest("bob", false), key.copy(requestDigest = "other"))
        }
        // The key is forgotten after 24 hours: the offer is the session's open one, so it is pending, not replayed.
        clock.now += 2.days
        assertFailsWith<TransferPendingException> {
            use.request(alice, principal, running.id, TransferRequest("bob", false), key.copy(requestDigest = "other"))
        }
    }

    @Test
    fun theRecipientAcceptsAndTheSessionPausesTowardTheNewOwner() = runTest {
        val running = running()
        val offered = offer(running)

        val accepted = use.accept(bob, principal, offered.id, resume = true)

        assertEquals(TransferState.ACCEPTED, accepted.state)
        assertTrue(accepted.resume)
        assertEquals("bob", accepted.decidedBy)
        assertEquals(offered.id, sessions.sessions.getValue(running.id).transferId)
        assertEquals(DesiredState.PAUSED, sessions.sessions.getValue(running.id).desiredState)
        // Accepting twice changes nothing.
        assertEquals(accepted, use.accept(bob, principal, offered.id, null))
        assertFailsWith<TransferForbiddenException> { use.accept(alice, principal, offered.id, null) }
    }

    @Test
    fun anExpiredOfferCannotBeAcceptedAndNobodyAcceptsAnotherMembersOffer() = runTest {
        val offered = offer()
        clock.now += CloudTransfer.OFFER_VALIDITY + 1.days
        assertFailsWith<TransferExpiredException> { use.accept(bob, principal, offered.id, null) }
        assertEquals(TransferState.EXPIRED, transfers.transfers.getValue(offered.id).state)
        assertFailsWith<TransferNotOpenException> { use.accept(bob, principal, offered.id, null) }
        assertFailsWith<TransferNotFoundException> { use.get(carol, offered.id) }
    }

    @Test
    fun acceptingAnOfferMadeBeforeTheInstanceStoppedThemIsRefusedToMembers() = runTest {
        val offered = offer()
        policy.membersOffer = false
        assertFailsWith<TransferDisabledException> { use.accept(bob, principal, offered.id, null) }
    }

    @Test
    fun anOfferWhoseSessionChangedOwnerOrEndedIsCancelledOnAcceptance() = runTest {
        val running = running()
        val offered = offer(running)
        sessions.put(sessions.sessions.getValue(running.id).copy(ownerId = "carol"))
        assertFailsWith<run.ostrom.server.runners.domain.CloudSessionStateException> {
            use.accept(bob, principal, offered.id, null)
        }
        val cancelled = transfers.transfers.getValue(offered.id)
        assertEquals(TransferState.CANCELLED, cancelled.state)
        assertEquals("owner_changed", cancelled.failure?.code)

        val other = running()
        val second = use.request(
            alice,
            principal,
            other.id,
            TransferRequest("bob", false),
            key.copy(key = "k-2"),
        ).transfer
        sessions.put(sessions.sessions.getValue(other.id).copy(state = SessionState.TERMINATED))
        assertFailsWith<run.ostrom.server.runners.domain.CloudSessionStateException> {
            use.accept(bob, principal, second.id, null)
        }
        assertEquals("session_not_running", transfers.transfers.getValue(second.id).failure?.code)
    }

    @Test
    fun theRecipientDeclinesAnOfferOnly() = runTest {
        val offered = offer()
        assertFailsWith<TransferForbiddenException> { use.decline(alice, principal, offered.id) }
        val declined = use.decline(bob, principal, offered.id)
        assertEquals(TransferState.DECLINED, declined.state)
        assertEquals(declined, use.decline(bob, principal, offered.id))
        assertFailsWith<TransferNotOpenException> { use.accept(bob, principal, offered.id, null) }

        val accepted = use.request(
            carol,
            principal,
            running("carol").id,
            TransferRequest("bob", false),
            key.copy(key = "k-2"),
        ).transfer
        assertFailsWith<TransferNotOpenException> { use.decline(bob, principal, accepted.id) }
    }

    @Test
    fun theOwnerWithdrawsAnOfferAndOnlyAnAdministratorCancelsAnAcceptedTransfer() = runTest {
        val offered = offer()
        assertFailsWith<TransferForbiddenException> { use.cancel(bob, principal, offered.id) }
        val withdrawn = use.cancel(alice, principal, offered.id)
        assertEquals(TransferState.CANCELLED, withdrawn.state)
        assertEquals(withdrawn, use.cancel(alice, principal, offered.id))
        assertFailsWith<TransferNotOpenException> { use.decline(bob, principal, offered.id) }

        val owned = running("carol")
        val accepted = use.request(
            carol,
            principal,
            owned.id,
            TransferRequest("bob", false),
            key.copy(key = "k-2"),
        ).transfer
        val cancelled = use.cancel(carol, principal, accepted.id)
        assertEquals(TransferState.CANCELLED, cancelled.state)
        assertNull(sessions.sessions.getValue(owned.id).transferId)
    }

    @Test
    fun aMemberCannotCancelAnAcceptedTransferNorOneBeingApplied() = runTest {
        val running = running()
        val accepted = use.accept(bob, principal, offer(running).id, null)
        assertFailsWith<TransferForbiddenException> { use.cancel(alice, principal, accepted.id) }

        val owned = running("carol")
        val own = use.request(carol, principal, owned.id, TransferRequest("bob", false), key.copy(key = "k-2")).transfer
        sessions.put(sessions.sessions.getValue(owned.id).copy(state = SessionState.TRANSFERRING))
        assertFailsWith<TransferInProgressException> { use.cancel(carol, principal, own.id) }
    }

    @Test
    fun aTransferIsVisibleToItsTwoMembersAndListedNewestFirstWithItsFilters() = runTest {
        val first = offer()
        clock.now += 1.hours
        val second = use.request(
            alice,
            principal,
            running().id,
            TransferRequest("carol", false),
            key.copy(key = "k-2"),
        ).transfer
        clock.now += 1.days
        val third = use.request(
            carol,
            principal,
            running("carol").id,
            TransferRequest("alice", false),
            key.copy(key = "k-3"),
        ).transfer

        assertEquals(first, use.get(bob, first.id))
        assertFailsWith<TransferNotFoundException> { use.get(bob, second.id) }
        assertFailsWith<TransferNotFoundException> { use.get(alice, Uuid.random()) }

        fun listing(direction: TransferDirection, states: Set<TransferState> = emptySet(), limit: Int = 50) =
            TransferListing(direction, states, null, null, limit)
        assertEquals(
            listOf(third.id, second.id, first.id),
            use.list(alice, listing(TransferDirection.BOTH)).map { it.id },
        )
        assertEquals(listOf(third.id), use.list(alice, listing(TransferDirection.INCOMING)).map { it.id })
        assertEquals(listOf(second.id, first.id), use.list(alice, listing(TransferDirection.OUTGOING)).map { it.id })
        assertEquals(
            listOf(third.id),
            use.list(alice, listing(TransferDirection.BOTH, setOf(TransferState.ACCEPTED))).map { it.id },
        )
        assertEquals(
            listOf(second.id),
            use.list(alice, TransferListing(TransferDirection.BOTH, emptySet(), null, third.id, 1)).map { it.id },
        )
        assertEquals(
            first.sessionId,
            use.list(alice, TransferListing(TransferDirection.BOTH, emptySet(), first.sessionId, null, 5))
                .single().sessionId,
        )
        assertFailsWith<run.ostrom.server.runners.domain.InvalidCloudSessionRequestException> {
            use.list(alice, listing(TransferDirection.BOTH, limit = 51))
        }
    }

    @Test
    fun anOfferPastItsValidityReadsExpiredBeforeItIsRecorded() = runTest {
        val offered = offer()
        clock.now += CloudTransfer.OFFER_VALIDITY + 1.days
        assertEquals(TransferState.EXPIRED, use.get(bob, offered.id).state)
        assertEquals(
            TransferState.EXPIRED,
            use.list(bob, TransferListing(TransferDirection.BOTH, emptySet(), null, null, 5)).single().state,
        )
        assertEquals(TransferState.OFFERED, transfers.transfers.getValue(offered.id).state)
    }
}
