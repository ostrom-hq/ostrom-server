// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack

import kotlinx.coroutines.test.runTest
import run.ostrom.server.gates.domain.models.Answer
import run.ostrom.server.gates.domain.models.AnswerChannel
import run.ostrom.server.gates.domain.models.ApprovalRequest
import run.ostrom.server.gates.domain.models.ApprovalView
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.models.GateStatus
import run.ostrom.server.gates.domain.models.GateView
import run.ostrom.server.gates.domain.models.RequestStatus
import run.ostrom.server.gates.domain.usecases.AnswerGateUseCase
import run.ostrom.server.gates.domain.usecases.GetApprovalUseCase
import run.ostrom.server.integrations.slack.domain.ReceiveSlackInteractionUseCaseImpl
import run.ostrom.server.integrations.slack.domain.SlackApps
import run.ostrom.server.integrations.slack.domain.SlackInteractionOutcome
import run.ostrom.server.integrations.slack.domain.SlackMember
import run.ostrom.server.integrations.slack.domain.StoredSlackLink
import run.ostrom.server.integrations.slack.infrastructure.HmacSlackSigner
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Slack's signature is checked before anything is read; only a linked member's Reject reaches the gates. */
class SlackInteractionsTest {
    private val now = Instant.parse("2026-11-15T09:00:00Z")
    private val settings = SlackInstanceSettings()
    private val signer = HmacSlackSigner()
    private val answered = mutableListOf<Pair<GateActor, Answer>>()
    private val org = Uuid.random()
    private val bob = Uuid.random()
    private val gone = Uuid.random()
    private val links = InMemorySlackLinks().apply {
        links[org to bob] = StoredSlackLink(bob, "U0BOB", now)
        links[org to gone] = StoredSlackLink(gone, "U0GONE", now)
    }
    private val people = MapSlackPeople(mapOf(bob to SlackMember(bob, "bob", OrgRole.MEMBER, null, "Acme")))
    private val gate = sampleDecidedGate(org, Uuid.random(), now).copy(status = GateStatus.PENDING)
    private var refusal: OstromException? = null

    private val interactions = ReceiveSlackInteractionUseCaseImpl(
        SlackApps(settings),
        signer,
        links,
        people,
        object : GetApprovalUseCase {
            override suspend fun invoke(actor: GateActor, approvalId: Uuid): ApprovalView = ApprovalView(
                ApprovalRequest(approvalId, gate.id, org, "bob", RequestStatus.PENDING, createdAt = now),
                gate,
            )
        },
        object : AnswerGateUseCase {
            override suspend fun invoke(
                actor: GateActor,
                requestId: Uuid,
                answer: Answer,
                shownDigest: String,
                channel: AnswerChannel,
            ): GateView {
                refusal?.let { throw it }
                assertEquals(gate.shownDigest to AnswerChannel.SLACK, shownDigest to channel)
                answered += actor to answer
                return GateView(gate, emptyList(), emptyList())
            }
        },
        object : Clock {
            override fun now(): Instant = now
        },
    )

    private fun reject(user: String, value: String) =
        """{"type":"block_actions","user":{"id":"$user"},"actions":[{"action_id":"ostrom_reject","value":"$value"}]}"""

    @Test
    fun aRejectFromALinkedMembersSlackAnswersNoAsThemAndTheGatesRefusalChangesNothing() = runTest {
        val stamp = now.epochSeconds.toString()
        val payload = reject("U0BOB", "$org:${Uuid.random()}")
        assertEquals(SlackInteractionOutcome.HANDLED, interactions(payload, payload, signed(payload), stamp))
        assertEquals(listOf(GateActor.person("bob", org, OrgRole.MEMBER) to Answer.NO), answered)
        refusal = OstromException(ErrorKind.FORBIDDEN, "Not the approver.")
        assertEquals(SlackInteractionOutcome.HANDLED, interactions(payload, payload, signed(payload), stamp))
        for (other in listOf(reject("U0GONE", "$org:${Uuid.random()}"), reject("U0BOB", "$org:bad"))) {
            assertEquals(SlackInteractionOutcome.HANDLED, interactions(other, other, signed(other), stamp))
        }
        assertEquals(1, answered.size)
    }

    private suspend fun signed(body: String, timestamp: String = now.epochSeconds.toString()) =
        "v0=" + signer.sign(SIGNING_SECRET, "v0:$timestamp:$body")

    @Test
    fun theSignatureIsSlacksHmacOfTheTimestampAndTheBody() = runTest {
        // Slack's own example (api.slack.com/authentication/verifying-requests-from-slack).
        val body = "token=xyzz0WbapA4vBCDEFasx0q6G&team_id=T1DC2JH3J&team_domain=testteamnow&channel_id=G8PSS9T3V" +
            "&channel_name=foobar&user_id=U2CERLKJA&user_name=roadrunner&command=%2Fwebhook-collect&text=" +
            "&response_url=https%3A%2F%2Fhooks.slack.com%2Fcommands%2FT1DC2JH3J%2F397700885554" +
            "%2F96rGlfmibIGlgcZRskXaIFfN" +
            "&trigger_id=398738663015.47445629121.803a0bc887a14d10d2c447fce8b6703c"
        assertEquals(
            "a2114d57b48eac39b9ad189dd8316235a7b4a8d21a10bd27519666489c69b503",
            signer.sign(SIGNING_SECRET, "v0:1531420618:$body"),
        )
    }

    @Test
    fun aRequestNotSignedByTheAppOrTooOldIsRefusedBeforeItsPayload() = runTest {
        val body = "payload=%7B%7D"
        val payload = "{}"
        val stamp = now.epochSeconds.toString()
        val handled = interactions(body, payload, signed(body), stamp)
        assertEquals(SlackInteractionOutcome.HANDLED, handled)
        assertEquals(SlackInteractionOutcome.UNAUTHORIZED, interactions(body, payload, "v0=00", stamp))
        assertEquals(SlackInteractionOutcome.UNAUTHORIZED, interactions(body + "x", payload, signed(body), stamp))
        assertEquals(SlackInteractionOutcome.UNAUTHORIZED, interactions(body, payload, null, stamp))
        assertEquals(SlackInteractionOutcome.UNAUTHORIZED, interactions(body, payload, signed(body), null))
        assertEquals(SlackInteractionOutcome.UNAUTHORIZED, interactions(body, payload, signed(body), "soon"))
        val old = (now.epochSeconds - 301).toString()
        assertEquals(SlackInteractionOutcome.UNAUTHORIZED, interactions(body, payload, signed(body, old), old))
        val late = (now.epochSeconds + 300).toString()
        assertEquals(SlackInteractionOutcome.HANDLED, interactions(body, payload, signed(body, late), late))
        assertEquals(SlackInteractionOutcome.BAD_REQUEST, interactions("", null, signed(""), stamp))
        settings.configured = false
        assertEquals(SlackInteractionOutcome.UNAUTHORIZED, interactions(body, payload, signed(body), stamp))
    }

    @Test
    fun aSignedInteractionThatIsNoRejectOfOursChangesNothing() = runTest {
        val stamp = now.epochSeconds.toString()
        val payloads = listOf(
            "not json",
            "[]",
            """{"type":"view_submission"}""",
            """{"type":{},"user":"U0BOB"}""",
            """{"type":"block_actions","user":"U0BOB"}""",
            """{"type":"block_actions","user":{"id":5}}""",
            """{"type":"block_actions","user":{"id":{}}}""",
            """{"type":"block_actions","actions":[]}""",
            """{"type":"block_actions","user":{"id":"U0BOB"}}""",
            // The Approve link's click is only acknowledged: never a yes, nothing read.
            """{"type":"block_actions","user":{"id":"U0BOB"},"actions":[{"action_id":"ostrom_approve",""" +
                """"value":"00000000-0000-0000-0000-000000000001:00000000-0000-0000-0000-000000000002"}]}""",
            """{"type":"block_actions","user":{"id":"U0BOB"},"actions":[{"action_id":"ostrom_reject","value":"x"}]}""",
            """{"type":"block_actions","user":{"id":"U0BOB"},"actions":[{"action_id":"ostrom_reject",""" +
                """"value":"00000000-0000-0000-0000-000000000001:00000000-0000-0000-0000-000000000002"}]}""",
        )
        for (payload in payloads) {
            assertEquals(SlackInteractionOutcome.HANDLED, interactions(payload, payload, signed(payload), stamp))
        }
        assertTrue(answered.isEmpty(), "The Approve link and unknown requests never answer")
    }
}
