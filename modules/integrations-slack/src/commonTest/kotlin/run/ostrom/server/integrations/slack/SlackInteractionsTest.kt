// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack

import kotlinx.coroutines.test.runTest
import run.ostrom.server.gates.domain.models.Answer
import run.ostrom.server.gates.domain.models.AnswerChannel
import run.ostrom.server.gates.domain.models.ApprovalView
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.models.GateView
import run.ostrom.server.gates.domain.usecases.AnswerGateUseCase
import run.ostrom.server.gates.domain.usecases.GetApprovalUseCase
import run.ostrom.server.integrations.slack.domain.ReceiveSlackInteractionUseCaseImpl
import run.ostrom.server.integrations.slack.domain.SlackApps
import run.ostrom.server.integrations.slack.domain.SlackInteractionOutcome
import run.ostrom.server.integrations.slack.infrastructure.HmacSlackSigner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** Slack's signature is checked before anything is read; nothing here reaches the gates. */
class SlackInteractionsTest {
    private val now = Instant.parse("2026-11-15T09:00:00Z")
    private val settings = SlackInstanceSettings()
    private val signer = HmacSlackSigner()
    private val answered = mutableListOf<Answer>()

    private val interactions = ReceiveSlackInteractionUseCaseImpl(
        SlackApps(settings),
        signer,
        InMemorySlackLinks(),
        MapSlackPeople(emptyMap()),
        object : GetApprovalUseCase {
            override suspend fun invoke(actor: GateActor, approvalId: kotlin.uuid.Uuid): ApprovalView =
                error("Never read")
        },
        object : AnswerGateUseCase {
            override suspend fun invoke(
                actor: GateActor,
                requestId: kotlin.uuid.Uuid,
                answer: Answer,
                shownDigest: String,
                channel: AnswerChannel,
            ): GateView = error("Never answered").also { answered += answer }
        },
        object : Clock {
            override fun now(): Instant = now
        },
    )

    private suspend fun signed(body: String, timestamp: String = now.epochSeconds.toString()) =
        "v0=" + signer.sign(SIGNING_SECRET, "v0:$timestamp:$body")

    @Test
    fun theSignatureIsSlacksHmacOfTheTimestampAndTheBody() = runTest {
        // Slack's own example (api.slack.com/authentication/verifying-requests-from-slack).
        val body = "token=xyzz0WbapA4vBCDEFasx0q6G&team_id=T1DC2JH3J&team_domain=testteamnow&channel_id=G8PSS9T3V" +
            "&channel_name=foobar&user_id=U2CERLKJA&user_name=roadrunner&command=%2Fwebhook-collect&text=" +
            "&response_url=https%3A%2F%2Fhooks.slack.com%2Fcommands%2FT1DC2JH3J%2F397700885554%2F96rGlfmibIGlgcZRskXaIFfN" +
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
            """{"type":"view_submission"}""",
            """{"type":"block_actions","actions":[]}""",
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
        assertTrue(answered.isEmpty())
    }
}
