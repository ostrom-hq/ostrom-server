// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sessions

import dev.ostrom.core.receipts.Digests
import dev.ostrom.server.gates.domain.models.GateMode
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.usecases.SessionGate
import dev.ostrom.server.gates.domain.usecases.SessionGateDecision
import dev.ostrom.server.sessions.domain.models.EndSession
import dev.ostrom.server.sessions.domain.models.Session
import dev.ostrom.server.sessions.domain.models.SessionNotFoundException
import dev.ostrom.server.sessions.domain.models.StartSession
import dev.ostrom.server.sessions.domain.usecases.CloseSessionUseCaseImpl
import dev.ostrom.server.sessions.fakes.ORG
import dev.ostrom.server.sessions.fakes.SessionBench
import dev.ostrom.server.sessions.fakes.agent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CloseSessionTest {
    private fun vectorSession(id: String, startedAt: String) = Session(
        orgId = ORG,
        id = id,
        rootId = id.substringBefore("~"),
        segment = 1,
        repo = "acme-api",
        agent = "claude-code",
        dev = "dev-a",
        harnessVersion = "h-compat",
        startedAt = Instant.parse(startedAt),
    )

    // The session vectors of ostrom-amont/mvp/compat/vectors/neutral/receipts.json.
    @Test
    fun theSessionPreimageIsThePrototypes() {
        val simple = vectorSession("sess-simple", "2026-10-30T09:00:00Z")
        assertEquals(
            "fa5d45c582fb6c611f0abe30d015b95fce7ac0020131429b32ea6eff1c18ccbd",
            digest(simple, "2026-10-30T09:12:34.567Z", 0),
        )
        val resumed = vectorSession("sess-resume~r2", "2026-10-30T09:25:00Z").copy(
            segment = 2,
            resumedFrom = "sess-resume",
            previousReceiptId = "rcpt_26307e91-82ec-4d6c-815e-668e6d3ce2ce",
        )
        assertEquals(
            "bb1df51a8be19e961ff82040f52e788cc42b79b16fab221039527d74a4fb7f98",
            digest(resumed, "2026-10-30T09:40:00Z", 0),
        )
        val lost = vectorSession("sess-wake", "2026-10-30T10:00:00Z")
        assertEquals(
            "009ed1c462c6a292d3ef382fe5c7c0ed4318886b0b607750baab415f4b7430ff",
            digest(lost, "2026-10-30T10:05:00Z", null),
        )
    }

    private fun digest(session: Session, endedAt: String, exitCode: Int?) =
        Digests.digestOf(CloseSessionUseCaseImpl.preimage(session, Instant.parse(endedAt), exitCode, emptyList()))

    @Test
    fun anEndedSegmentIsSignedWithItsGatesRulesAndAnswers() = runTest {
        val bench = SessionBench()
        val session = bench.start(agent(), StartSession(id = "s-1", agent = "claude-code", repo = "acme/api"))
        val gateA = Uuid.parse("00000000-0000-0000-0000-0000000000a1")
        val gateB = Uuid.parse("00000000-0000-0000-0000-0000000000b2")
        val approval = Uuid.parse("00000000-0000-0000-0000-0000000000c3")
        val at = Instant.parse("2026-10-30T09:05:00Z")
        bench.gates.gates["s-1"] = listOf(
            SessionGate(
                gateA,
                "push-main",
                GateMode.ENFORCED,
                GateStatus.RESERVED,
                "rcpt_gate_a",
                listOf(SessionGateDecision(approval, "rev-b", at, approved = true, selfApproved = true)),
            ),
            SessionGate(
                gateB,
                "merge",
                GateMode.SHADOW,
                GateStatus.REJECTED,
                null,
                listOf(SessionGateDecision(approval, "rev-c", at, approved = false, selfApproved = false)),
            ),
        )
        bench.clock.now += 10.minutes
        bench.costs.cost = 1.5
        val ended = bench.close(ORG, session.id, EndSession(tools = 7, model = "opus", costUsd = 9.0, exitCode = 0))

        assertEquals("rcpt_session_s-1", ended.receiptId)
        assertEquals(Instant.parse("2026-10-30T09:10:00.123Z"), ended.endedAt)
        assertEquals(7, ended.tools)
        assertEquals("opus", ended.model)
        assertEquals(1.5, ended.costUsd)
        assertEquals(
            listOf(Triple(session.launchId, session.startedAt, ended.endedAt!!)),
            bench.costs.asked,
            "The cost is the launch's while the segment ran",
        )
        assertEquals(0, ended.exitCode)
        assertEquals(ended, bench.repository.get(ORG, "s-1"))

        val draft = bench.sign.signed.getValue("rcpt_session_s-1")
        assertReceiptDraft(draft)
        assertGatesPreimage(bench, session, ended, gateA to gateB, draft)
    }

    private fun assertReceiptDraft(draft: JsonObject) {
        assertEquals("lyes", draft.text("initiator"))
        assertEquals("claude-code", draft.text("agent"))
        assertEquals("opus", draft.text("model"))
        assertEquals("1.5", draft.text("costUsd"))
        assertEquals("r-commit-style", draft.getValue("rules").jsonArray.single().jsonObject.text("id"))
        val decisions = draft.getValue("decisions").jsonArray.map { it.jsonObject }
        assertEquals(listOf("approved", "rejected"), decisions.map { it.text("result") })
        assertEquals("2026-10-30T09:05:00.000Z", decisions[0].text("at"))
        assertEquals("true", decisions[0].text("selfApproved"))
        assertFalse("selfApproved" in decisions[1])
    }

    private fun assertGatesPreimage(
        bench: SessionBench,
        session: Session,
        ended: Session,
        gates: Pair<Uuid, Uuid>,
        draft: JsonObject,
    ) {
        val (gateA, gateB) = gates
        val preimage = CloseSessionUseCaseImpl.preimage(
            session,
            ended.endedAt ?: error("ended"),
            0,
            bench.gates.gates.getValue("s-1"),
        )
        assertEquals(
            """[{"id":"$gateA","class":"push-main","mode":"enforced","status":"approved",""" +
                """"receiptId":"rcpt_gate_a"},""" +
                """{"id":"$gateB","class":"merge","mode":"shadow","status":"rejected","receiptId":null}]""",
            preimage.getValue("gates").toString(),
        )
        assertEquals(Digests.digestOf(preimage), draft.getValue("subject").jsonObject.text("digest"))
        val subject = draft.getValue("subject").jsonObject
        assertEquals("session" to "s-1", subject.text("kind") to subject.text("id"))
    }

    @Test
    fun endingTwiceKeepsTheReceiptAndAddsTheExitCode() = runTest {
        val bench = SessionBench()
        val session = bench.start(agent(), StartSession(id = "s-1", agent = "claude-code"))
        val first = bench.close(ORG, session.id, EndSession(source = "hook"))
        val draft = bench.sign.signed.getValue("rcpt_session_s-1")
        assertFalse("model" in draft || "costUsd" in draft)
        assertNull(first.exitCode)
        assertEquals("hook", first.source)

        bench.clock.now += 1.minutes
        val second = bench.close(ORG, session.id, EndSession(model = "sonnet", exitCode = 3, source = "net"))
        assertEquals(first.copy(model = "sonnet", exitCode = 3), second, "Outside the receipt, nothing else changes")
        assertEquals(1, bench.sign.signed.size)
        assertEquals(second, bench.close(ORG, session.id, EndSession(model = "opus", exitCode = 4)))
        assertFailsWith<SessionNotFoundException> { bench.close(ORG, "nope", EndSession()) }
    }

    @Test
    fun aStartingModelIsTheReceiptsUnlessTheEndSaysOtherwise() = runTest {
        val bench = SessionBench()
        val session = bench.start(agent(), StartSession(id = "s-1", agent = "codex", model = "gpt", cwd = "/w"))
        val ended = bench.close(ORG, session.id, EndSession(source = "x".repeat(400)))
        assertEquals("gpt", bench.sign.signed.getValue("rcpt_session_s-1").text("model"))
        assertEquals(300, ended.source?.length)
        assertEquals("gpt", ended.model)
    }

    @Test
    fun anEndThatFailsBeforeItsReceiptLeavesItToTheNextEnd() = runTest {
        val bench = SessionBench()
        val session = bench.start(agent(), StartSession(id = "s-1", agent = "claude-code", model = "gpt"))
        bench.clock.now += 10.minutes
        bench.costs.cost = 0.7
        bench.sign.failing = 1
        assertFailsWith<IllegalStateException> { bench.close(ORG, session.id, EndSession(tools = 2, exitCode = 1)) }
        val written = bench.repository.get(ORG, "s-1") ?: error("written")
        assertNull(written.receiptId, "The end is written, its receipt is not")
        val endedAt = written.endedAt ?: error("The segment is ended: no gate joins it any more")

        bench.clock.now += 1.minutes
        val ended = bench.close(ORG, session.id, EndSession(model = "opus", exitCode = 4, source = "net"))
        assertEquals("rcpt_session_s-1", ended.receiptId)
        assertEquals(endedAt to 1, ended.endedAt to ended.exitCode, "The first end's time and exit code stay")
        assertEquals(listOf(endedAt), bench.costs.asked.map { it.third }, "Its cost was measured once")
        val draft = bench.sign.signed.getValue("rcpt_session_s-1")
        assertEquals("gpt" to "0.7", draft.text("model") to draft.text("costUsd"))
        val preimage = CloseSessionUseCaseImpl.preimage(session, endedAt, 1, emptyList())
        assertEquals(Digests.digestOf(preimage), draft.getValue("subject").jsonObject.text("digest"))
        assertEquals(ended, bench.repository.get(ORG, "s-1"))
    }

    @Test
    fun anEndThatAnotherFinishedWhileItReadKeepsTheOthersReceipt() = runTest {
        val bench = SessionBench()
        val session = bench.start(agent(), StartSession(id = "s-1", agent = "claude-code"))
        var first: Session? = null
        bench.costs.meanwhile = { first = bench.close(ORG, session.id, EndSession(source = "hook")) }
        val second = bench.close(ORG, session.id, EndSession(model = "sonnet", exitCode = 3, source = "net"))
        val hook = first ?: error("the hook ended it first")
        assertEquals(hook.copy(model = "sonnet", exitCode = 3), second, "Only what lies outside the receipt is added")
        assertEquals(listOf("rcpt_session_s-1"), bench.sign.signed.keys.toList())
    }

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
}
