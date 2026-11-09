// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gates

import run.ostrom.server.gates.domain.models.GateNotUsableException
import run.ostrom.server.gates.domain.models.GateSessionClosedException
import run.ostrom.server.gates.domain.models.GateStatus
import run.ostrom.server.gates.domain.usecases.GateUse
import run.ostrom.server.gates.domain.usecases.ListSessionGatesUseCaseImpl
import run.ostrom.server.gates.fakes.GateBench
import run.ostrom.server.gates.fakes.ORG
import run.ostrom.server.gates.fakes.OTHER_SESSION
import run.ostrom.server.gates.fakes.SESSION
import run.ostrom.server.gates.fakes.SHA_B
import run.ostrom.server.gates.fakes.agent
import run.ostrom.server.kernel.errors.ErrorKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * AUDIT-SERVEUR-3 B2: the end of a session and its gates take the session's lock in a transaction of the gates. The
 * end marks the session ended and lists its gates; a gate asked after the mark is refused, one asked before is listed.
 */
class SessionEndsTest {
    @Test
    fun theEndListsTheGatesAskedBeforeIt() = runTest {
        val bench = GateBench()
        val asked = bench.open(bench.pushRequest())
        bench.answer(asked, "rev-b")
        val pending = bench.open(bench.pushRequest(sha = SHA_B))
        bench.open(bench.pushRequest(session = OTHER_SESSION, diff = "elsewhere"))
        val list = ListSessionGatesUseCaseImpl(bench.repository)

        val gates = list.close(ORG, SESSION, bench.clock.now)

        assertEquals(listOf(asked.gate.id, pending.gate.id), gates.map { it.id })
        assertEquals(listOf(true), gates.first().decisions.map { it.approved }, "The answers are read in its lock")
        assertEquals(listOf(SESSION, SESSION, OTHER_SESSION, SESSION), bench.repository.lockedSessions)
        assertEquals(mapOf((ORG to SESSION) to bench.clock.now), bench.repository.sessionEnds.toMap())
        assertEquals(0, bench.repository.batchedRequestReads, "Read in the end's transaction, not apart")
        assertEquals(gates, list(ORG, SESSION), "The plain read lists the same gates")
    }

    @Test
    fun aGateAskedOnceTheEndListedTheGatesIsRefused() = runTest {
        val bench = GateBench()
        val first = bench.clock.now
        val list = ListSessionGatesUseCaseImpl(bench.repository)
        assertEquals(emptyList(), list.close(ORG, SESSION, first))
        bench.clock.now += 1.minutes
        assertEquals(emptyList(), list.close(ORG, SESSION, bench.clock.now), "Ending again changes nothing")

        val refused = assertFailsWith<GateSessionClosedException> { bench.open(bench.pushRequest()) }

        assertEquals(ErrorKind.CONFLICT, refused.kind)
        assertEquals("conflict", refused.type, "The sessions module's answer for a closed session")
        assertEquals(
            "session closed: $SESSION ended at $first; its receipt is signed and nothing more is added to it. " +
                "Reopen a segment (POST /api/v1/sessions/$SESSION/reopen) and send it there.",
            refused.message,
        )
        assertTrue(bench.repository.gates.isEmpty())
        assertEquals(first, bench.repository.sessionEnds.getValue(ORG to SESSION), "The first end's time stays")
        // Another session, and the GitHub check, which asks in no session, are not held.
        bench.open(bench.pushRequest(session = OTHER_SESSION))
        bench.open(bench.mergeRequest(viaCheck = true))
        assertEquals(listOf(SESSION, SESSION, SESSION, OTHER_SESSION), bench.repository.lockedSessions)
    }

    // A yes is taken up only in a session whose end has not listed its gates, even when the sessions module has not
    // seen the end yet; another session of the gate still takes it.
    @Test
    fun aYesIsNotTakenUpInASessionWhoseEndListedItsGates() = runTest {
        val bench = GateBench()
        bench.open(bench.mergeRequest(session = OTHER_SESSION))
        val gate = bench.open(bench.mergeRequest(session = SESSION)).also { bench.answer(it, "rev-b") }.gate.id
        ListSessionGatesUseCaseImpl(bench.repository).close(ORG, SESSION, bench.clock.now)
        bench.repository.lockedSessions.clear()

        for (use in listOf(GateUse.RESERVE, GateUse.CONSUME)) {
            val error = assertFailsWith<GateNotUsableException> { bench.use(agent(), gate, use) }
            assertEquals("The session that asked for this yes has ended. Ask again in an open session.", error.message)
        }
        assertEquals(GateStatus.APPROVED, bench.repository.gates.getValue(gate).status)
        assertEquals(GateStatus.RESERVED, bench.use(agent(OTHER_SESSION), gate, GateUse.RESERVE).status)
        assertEquals(listOf(SESSION, SESSION, OTHER_SESSION), bench.repository.lockedSessions)
    }
}
