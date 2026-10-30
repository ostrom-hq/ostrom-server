package dev.ostrom.server.gates

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.Answer
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateNotFoundException
import dev.ostrom.server.gates.domain.models.GateNotUsableException
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.NotYourGateException
import dev.ostrom.server.gates.domain.usecases.GateUse
import dev.ostrom.server.gates.fakes.GateBench
import dev.ostrom.server.gates.fakes.OTHER_ORG
import dev.ostrom.server.gates.fakes.OTHER_SESSION
import dev.ostrom.server.gates.fakes.SESSION
import dev.ostrom.server.gates.fakes.SHA_B
import dev.ostrom.server.gates.fakes.agent
import dev.ostrom.server.gates.fakes.device
import dev.ostrom.server.gates.fakes.user
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class UseGateUseCaseTest {
    private suspend fun approved(bench: GateBench) = bench.open(bench.pushRequest()).also { bench.answer(it, "rev-b") }

    @Test
    fun aYesIsReservedThenConsumedOnce() = runTest {
        val bench = GateBench()
        val view = approved(bench)
        val reserved = bench.use(agent(), view.gate.id, GateUse.RESERVE)
        assertEquals(GateStatus.RESERVED, reserved.status)
        assertEquals(SESSION, reserved.reservedBy)

        val consumed = bench.use(agent(), view.gate.id, GateUse.CONSUME)
        assertEquals(GateStatus.CONSUMED, consumed.status)
        assertEquals(bench.clock.now, consumed.consumedAt)

        val again = assertFailsWith<GateNotUsableException> { bench.use(agent(), view.gate.id, GateUse.RESERVE) }
        assertEquals("This yes was already used. Ask again: one yes covers one action.", again.message)
    }

    @Test
    fun twoParallelToolCallsCannotBothUseTheYes() = runTest {
        val bench = GateBench()
        val view = approved(bench)
        val outcomes = List(2) {
            async {
                try {
                    bench.use(agent(), view.gate.id, GateUse.RESERVE).status
                } catch (error: GateNotUsableException) {
                    error.message
                }
            }
        }.awaitAll()

        assertEquals(
            setOf<String?>("RESERVED", "This yes is already in use by another action. Ask again for this one."),
            outcomes.map { it.toString() }.toSet(),
        )
    }

    @Test
    fun aReleasedYesCanBeReservedAgain() = runTest {
        val bench = GateBench()
        val view = approved(bench)
        bench.use(agent(), view.gate.id, GateUse.RESERVE)
        val released = bench.use(agent(), view.gate.id, GateUse.RELEASE)

        assertEquals(GateStatus.APPROVED, released.status)
        assertNull(released.reservedBy)
        assertNull(released.reservedAt)
        assertEquals(GateStatus.RESERVED, bench.use(agent(), view.gate.id, GateUse.RESERVE).status)
    }

    @Test
    fun releasingNeedsTheReservation() = runTest {
        val bench = GateBench()
        val view = approved(bench)
        val error = assertFailsWith<GateNotUsableException> { bench.use(agent(), view.gate.id, GateUse.RELEASE) }
        assertEquals("This yes is not reserved by your session: reserve it before the action.", error.message)
    }

    @Test
    fun aHookThatDidNotReserveConsumesTheYesOnce() = runTest {
        val bench = GateBench()
        val view = approved(bench)
        assertEquals(GateStatus.CONSUMED, bench.use(agent(), view.gate.id, GateUse.CONSUME).status)
        val again = assertFailsWith<GateNotUsableException> { bench.use(agent(), view.gate.id, GateUse.CONSUME) }
        assertEquals("This yes was already used. Ask again: one yes covers one action.", again.message)
    }

    @Test
    fun twoParallelConsumesUseTheYesOnce() = runTest {
        val bench = GateBench()
        val view = approved(bench)
        val outcomes = List(2) {
            async {
                try {
                    bench.use(agent(), view.gate.id, GateUse.CONSUME).status.name
                } catch (error: GateNotUsableException) {
                    error.message
                }
            }
        }.awaitAll()
        assertEquals(setOf<String?>("CONSUMED", "This yes was already used. Ask again: one yes covers one action."), outcomes.toSet())
    }

    @Test
    fun consumingAShadowOrUngatedGateChangesNothing() = runTest {
        val bench = GateBench()
        val shadow = bench.open(bench.pushRequest(node = bench.node("rev-b", enforced = false)))
        val ungated = bench.open(bench.pushRequest(sha = SHA_B, node = null))
        val updates = bench.repository.gateUpdates
        assertEquals(GateStatus.SHADOW, bench.use(agent(), shadow.gate.id, GateUse.CONSUME).status)
        assertEquals(GateStatus.UNGATED, bench.use(agent(), ungated.gate.id, GateUse.CONSUME).status)
        assertEquals(updates, bench.repository.gateUpdates)
    }

    @Test
    fun aReservationHeldByAnotherSessionCannotBeConsumed() = runTest {
        val bench = GateBench()
        bench.open(bench.mergeRequest(session = OTHER_SESSION))
        val view = bench.open(bench.mergeRequest(session = SESSION))
        bench.answer(view, "rev-b")
        bench.use(agent(OTHER_SESSION), view.gate.id, GateUse.RESERVE)

        val consume = assertFailsWith<GateNotUsableException> { bench.use(agent(SESSION), view.gate.id, GateUse.CONSUME) }
        assertEquals("This yes is already in use by another action. Ask again for this one.", consume.message)
        val pending = bench.open(bench.pushRequest(sha = "d".repeat(40)))
        val early = assertFailsWith<GateNotUsableException> { bench.use(agent(), pending.gate.id, GateUse.CONSUME) }
        assertEquals("This gate is still waiting for a yes.", early.message)
    }

    @Test
    fun onlyASessionThatAskedUsesTheYes() = runTest {
        val bench = GateBench()
        val view = approved(bench)
        val strangers = listOf(
            agent(OTHER_SESSION),
            device(),
            user("rev-b"),
            GateActor("agent", dev.ostrom.server.gates.fakes.ORG, ActorKind.AGENT_SESSION, null),
        )
        for (actor in strangers) {
            assertFailsWith<NotYourGateException> { bench.use(actor, view.gate.id, GateUse.RESERVE) }
        }
        assertFailsWith<GateNotFoundException> { bench.use(agent(org = OTHER_ORG), view.gate.id, GateUse.RESERVE) }
        assertFailsWith<GateNotFoundException> { bench.use(agent(), Uuid.random(), GateUse.RESERVE) }
    }

    @Test
    fun aGateThatWasNotApprovedHasNoYesToReserve() = runTest {
        val bench = GateBench()
        val pending = bench.open(bench.pushRequest())
        val rejected = bench.open(bench.pushRequest(sha = SHA_B)).also { bench.answer(it, "rev-b", Answer.NO) }
        val shadow = bench.open(bench.pushRequest(sha = "c".repeat(40), node = bench.node("rev-b", enforced = false)))
        val expected = mapOf(
            pending.gate.id to "This gate is still waiting for a yes.",
            rejected.gate.id to "This gate was refused.",
            shadow.gate.id to "This action is not gated: there is no yes to reserve.",
        )
        for ((id, message) in expected) {
            val error = assertFailsWith<GateNotUsableException> { bench.use(agent(), id, GateUse.RESERVE) }
            assertEquals(message, error.message)
        }
    }
}
