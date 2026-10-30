// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.slack

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.Answer
import dev.ostrom.server.gates.domain.models.AnswerChannel
import dev.ostrom.server.gates.domain.models.ApprovalView
import dev.ostrom.server.gates.domain.models.Evidence
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateAlreadyDecidedException
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.GateView
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.gates.domain.usecases.AnswerGateUseCase
import dev.ostrom.server.gates.domain.usecases.GetApprovalUseCase
import dev.ostrom.server.integrations.slack.domain.HandleSlackClickUseCaseImpl
import dev.ostrom.server.integrations.slack.domain.SlackClick
import dev.ostrom.server.integrations.slack.domain.SlackClickOutcome
import dev.ostrom.server.integrations.slack.domain.SlackGateChannel
import dev.ostrom.server.integrations.slack.domain.SlackLink
import dev.ostrom.server.integrations.slack.domain.SlackLinksUseCaseImpl
import dev.ostrom.server.integrations.slack.domain.SlackMessageRef
import dev.ostrom.server.integrations.slack.domain.SlackMessages
import dev.ostrom.server.integrations.slack.domain.SlackRisk
import dev.ostrom.server.integrations.slack.domain.SlackText
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.kernel.logging.LogLevel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The Slack integration's rules: which gates Slack may answer, the messages, the channel, a click and the links. */
class SlackDomainTest {
    private val messages = SlackMessages("https://console.example")
    private val link = SlackLink(ORG, "rev-b", "T0ACME", "U0REVB", "lyes", NOW)

    private fun click(yes: Boolean = true, user: String = "U0REVB", request: Uuid = requestOf(pushGate(), "rev-b").id) =
        SlackClick("T0ACME", user, yes, request, "shown", "D-U0REVB", "ts-1")

    private fun blocks(text: SlackText) = text.blocks.map { it.jsonObject }

    private fun textOf(block: JsonObject) = block["text"]!!.jsonObject["text"]!!.jsonPrimitive.content

    @Test
    fun aSmallPlainPushIsAnswerableOnSlack() {
        assertTrue(SlackRisk.answerable(pushGate()))
        assertTrue(SlackRisk.answerable(pushGate(diff = (1..15).joinToString("\n") { "+ line $it" })))
    }

    @Test
    fun aLargeAForcedAMergeOrAnUnseenChangeIsAnsweredInOstromOnly() {
        assertFalse(SlackRisk.answerable(pushGate(diff = (1..16).joinToString("\n") { "- line $it" })), "16 lines")
        assertFalse(SlackRisk.answerable(pushGate(force = true)), "a forced push")
        assertFalse(SlackRisk.answerable(pushGate(push = false)), "a merge or any gate without a push")
        assertFalse(SlackRisk.answerable(pushGate(diff = null)), "no diff shown")
        assertFalse(SlackRisk.answerable(pushGate(diff = "+++ b/a.kt\n--- a/a.kt\n context")), "no changed line")
    }

    @Test
    fun fileHeadersAreNotChangedLines() {
        assertEquals(2, SlackRisk.changedLines("--- a/x\n+++ b/x\n@@ -1 +1 @@\n-old\n+new\n same"))
    }

    @Test
    fun aRequestShowsTheEvidenceEscapedAndTheButtonsBoundToWhatWasShown() {
        val gate = pushGate()
        val request = requestOf(gate, "rev-b")
        val text = messages.request(gate, request)
        assertEquals("lyes asks for your yes: Merge into main", text.text)
        val all = blocks(text)
        assertEquals("*Command*\ngit push &lt;origin&gt; &amp; main", textOf(all[1]))
        val buttons = all.last()["elements"]!!.jsonArray.map { it.jsonObject }
        val actions = buttons.map { it["action_id"]!!.jsonPrimitive.content }
        assertEquals(listOf(SlackMessages.YES, SlackMessages.NO), actions)
        assertTrue(buttons.all { it["value"]!!.jsonPrimitive.content == "${request.id}:shown" })
    }

    @Test
    fun aRequestSlackCannotAnswerLinksToTheConsoleAndClipsLongValues() {
        val gate = pushGate(diff = "+x\n".repeat(20)).let {
            it.copy(evidence = it.evidence + List(10) { n -> Evidence("Line $n", "v".repeat(2_000)) })
        }
        val request = requestOf(gate, "rev-b")
        val all = blocks(messages.request(gate, request))
        assertEquals(1 + 8 + 1, all.size, "title, eight evidence lines at most, the link")
        val console = "<https://console.example/console/approvals/${request.id}|open the request>"
        assertTrue(textOf(all.last()).contains(console))
        assertTrue(all.none { it["type"]!!.jsonPrimitive.content == "actions" })
        assertTrue(textOf(all[3]).endsWith("v…"))
    }

    @Test
    fun aDecidedGateSaysWhoDecidedAndHowAndARefusalLinksToTheConsole() {
        val approved = messages.decided(pushGate(status = GateStatus.APPROVED))
        assertEquals("Merge into main: approved by rev-b", approved.text)
        assertEquals("Merge into main: refused by rev-b", messages.decided(pushGate(status = GateStatus.REJECTED)).text)
        val refused = messages.refused("r1", "No.")
        assertEquals("No. Answer in Ostrom: https://console.example/console/approvals/r1", refused.text)
    }

    @Test
    fun theChannelSendsEachPendingLinkedApproverADirectMessageAndUpdatesThemOnceDecided() = runTest {
        val repository = MemorySlackRepository().apply { links += link }
        val api = FakeSlackApi()
        val channel = SlackGateChannel(repository, api, messages)
        val gate = pushGate()
        val requests = listOf(
            requestOf(gate, "rev-b", n = 1),
            requestOf(gate, "rev-c", n = 2),
            requestOf(gate, "rev-d", status = RequestStatus.CLOSED, n = 3),
        )
        assertEquals("slack", channel.name)
        channel.requested(gate, requests)
        assertEquals(listOf("U0REVB"), api.opened, "only the linked, pending approver")
        assertEquals(listOf(SlackMessageRef(ORG, requests[0].id, gate.id, "D-U0REVB", "ts-1")), repository.messages)
        channel.decided(gate.copy(status = GateStatus.APPROVED, decidedBy = "rev-b"), requests)
        assertEquals(listOf("D-U0REVB" to "ts-1"), api.updated.map { it.first to it.second })
        assertEquals("Merge into main: approved by rev-b", api.updated.single().third.text)
    }

    @Test
    fun anUnconfiguredSlackIsNeverCalled() = runTest {
        val repository = MemorySlackRepository().apply { links += link }
        val api = FakeSlackApi(configured = false)
        val channel = SlackGateChannel(repository, api, messages)
        channel.requested(pushGate(), listOf(requestOf(pushGate(), "rev-b")))
        channel.decided(pushGate(status = GateStatus.APPROVED), emptyList())
        assertTrue(api.opened.isEmpty() && api.updated.isEmpty())
    }

    private class Gates(val gate: Gate = pushGate(), val failure: OstromException? = null) :
        GetApprovalUseCase, AnswerGateUseCase {
        val answers = mutableListOf<Triple<GateActor, Answer, AnswerChannel>>()

        override suspend fun invoke(actor: GateActor, approvalId: Uuid): ApprovalView {
            failure?.let { throw it }
            return ApprovalView(requestOf(gate, actor.id), gate)
        }

        override suspend fun invoke(
            actor: GateActor,
            requestId: Uuid,
            answer: Answer,
            shownDigest: String,
            channel: AnswerChannel,
        ): GateView {
            answers += Triple(actor, answer, channel)
            return GateView(gate, emptyList(), emptyList())
        }
    }

    private fun handler(gates: Gates, api: FakeSlackApi = FakeSlackApi(), logger: RecordingLogger = RecordingLogger()) =
        HandleSlackClickUseCaseImpl(
            MemorySlackRepository().apply { links += link },
            gates,
            gates,
            api,
            messages,
            logger,
        )

    @Test
    fun aClickOfTheLinkedApproverAnswersAsThatPersonFromSlack() = runTest {
        val gates = Gates()
        assertEquals(SlackClickOutcome.ANSWERED, handler(gates)(click(yes = true)))
        assertEquals(SlackClickOutcome.ANSWERED, handler(gates)(click(yes = false)))
        val actor = GateActor("rev-b", ORG, ActorKind.USER, personId = "rev-b")
        assertEquals(
            listOf(Triple(actor, Answer.YES, AnswerChannel.SLACK), Triple(actor, Answer.NO, AnswerChannel.SLACK)),
            gates.answers,
        )
    }

    @Test
    fun aClickOfAnUnknownSlackMemberAnswersNothing() = runTest {
        val gates = Gates()
        val api = FakeSlackApi()
        assertEquals(SlackClickOutcome.UNKNOWN_MEMBER, handler(gates, api)(click(user = "U0NOBODY")))
        assertTrue(gates.answers.isEmpty())
        assertTrue(api.updated.single().third.text.startsWith("This Slack account is not linked"))
    }

    @Test
    fun s231AYesOnSlackForALargeOrForcedPushIsRefusedWhateverTheButtonSays() = runTest {
        for (gate in listOf(pushGate(diff = "+x\n".repeat(16)), pushGate(force = true), pushGate(push = false))) {
            val gates = Gates(gate)
            assertEquals(SlackClickOutcome.NOT_ON_SLACK, handler(gates)(click()))
            assertTrue(gates.answers.isEmpty(), "never answered")
        }
    }

    @Test
    fun aRefusalOfTheGatesIsShownByItsKindOnlyAndLogged() = runTest {
        val api = FakeSlackApi()
        val logger = RecordingLogger()
        val outcome = handler(Gates(failure = GateAlreadyDecidedException()), api, logger)(click())
        assertEquals(SlackClickOutcome.REFUSED, outcome)
        assertTrue(api.updated.single().third.text.startsWith("Ostrom did not take this answer (conflict)."))
        assertEquals(listOf(LogLevel.INFO to "slack answer refused"), logger.lines)
    }

    @Test
    fun aRefusalSlackCannotShowStillStands() = runTest {
        val api = FakeSlackApi().apply { failure = slackDown() }
        val logger = RecordingLogger()
        assertEquals(SlackClickOutcome.UNKNOWN_MEMBER, handler(Gates(), api, logger)(click(user = "U0NOBODY")))
        assertEquals(listOf(LogLevel.WARN to "slack update failed"), logger.lines)
    }

    @Test
    fun linksAreValidatedAndOneSlackAccountIsOneMember() = runTest {
        val repository = MemorySlackRepository()
        val links = SlackLinksUseCaseImpl(repository, SettableClock())
        val saved = links.link(ORG, "rev-b", " T0ACME " to "U0REVB", "lyes")
        assertEquals(link, saved)
        assertEquals(listOf(link), links.list(ORG))
        assertEquals(link, links.link(ORG, "rev-b", "T0ACME" to "U0REVB", "lyes"), "linking again is a no-op")
        val taken = assertFailsWith<OstromException> { links.link(ORG, "rev-c", "T0ACME" to "U0REVB", "lyes") }
        assertEquals(ErrorKind.CONFLICT, taken.kind)
        for ((person, slack) in listOf(
            "" to ("T0ACME" to "U0X1"),
            "p".repeat(201) to ("T0ACME" to "U0X1"),
            "rev-c" to ("acme" to "U0X1"),
            "rev-c" to ("T0ACME" to "<@here>"),
        )) {
            val bad = assertFailsWith<OstromException> { links.link(ORG, person, slack, "lyes") }
            assertEquals(ErrorKind.INVALID_REQUEST, bad.kind)
        }
        links.unlink(ORG, "rev-b")
        assertEquals(ErrorKind.NOT_FOUND, assertFailsWith<OstromException> { links.unlink(ORG, "rev-b") }.kind)
    }
}
