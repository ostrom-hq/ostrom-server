// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates

import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateNotUsableException
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.usecases.GateUse
import dev.ostrom.server.gates.fakes.GateBench
import dev.ostrom.server.gates.fakes.agent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** SECURITY-AUDIT S2-29: a yes covers one head commit and one destination, for 15 minutes. */
class YesLifetimeTest {
    private suspend fun approved(bench: GateBench) = bench.open(bench.pushRequest()).also { bench.answer(it, "rev-b") }

    private val expired = "This yes expired: it waits 15 minutes for its action. Ask again."

    @Test
    fun aYesUnusedFor15MinutesNoLongerCoversTheAction() = runTest {
        val bench = GateBench()
        val view = approved(bench)
        bench.clock.now += Gate.YES_LIFETIME
        val reserve = assertFailsWith<GateNotUsableException> { bench.use(agent(), view.gate.id, GateUse.RESERVE) }
        assertEquals(expired, reserve.message)
        val consume = assertFailsWith<GateNotUsableException> { bench.use(agent(), view.gate.id, GateUse.CONSUME) }
        assertEquals(expired, consume.message)

        val again = bench.open(bench.pushRequest())
        assertNotEquals(view.gate.id, again.gate.id, "The same action asks a new yes")
        assertEquals(GateStatus.PENDING, again.gate.status)
    }

    @Test
    fun aYesJustUnder15MinutesOldAndAReservedYesStillServe() = runTest {
        val bench = GateBench()
        val view = approved(bench)
        bench.clock.now += Gate.YES_LIFETIME - 1.seconds
        assertEquals(view.gate.id, bench.open(bench.pushRequest()).gate.id, "Asking again joins the live yes")
        assertEquals(GateStatus.RESERVED, bench.use(agent(), view.gate.id, GateUse.RESERVE).status)
        bench.clock.now += 30.minutes
        assertEquals(GateStatus.CONSUMED, bench.use(agent(), view.gate.id, GateUse.CONSUME).status)
    }

    @Test
    fun onlyAnApprovedYesWithItsDateExpires() = runTest {
        val bench = GateBench()
        val gate = bench.open(bench.pushRequest()).gate.copy(status = GateStatus.APPROVED, decidedAt = bench.clock.now)
        val later = bench.clock.now + 1.minutes + Gate.YES_LIFETIME
        assertTrue(gate.yesExpired(later))
        assertFalse(gate.copy(decidedAt = null).yesExpired(later))
        assertFalse(gate.copy(status = GateStatus.PENDING).yesExpired(later))
    }
}
