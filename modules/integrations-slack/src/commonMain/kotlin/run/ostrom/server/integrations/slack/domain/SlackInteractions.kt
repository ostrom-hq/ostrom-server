// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack.domain

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import run.ostrom.server.gates.domain.models.Answer
import run.ostrom.server.gates.domain.models.AnswerChannel
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.usecases.AnswerGateUseCase
import run.ostrom.server.gates.domain.usecases.GetApprovalUseCase
import run.ostrom.server.kernel.errors.OstromException
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** What a request to `/webhooks/slack` gets. */
enum class SlackInteractionOutcome {
    /** Not signed by the instance's Slack app, too old, or Slack is not configured: 401, nothing read. */
    UNAUTHORIZED,

    /** Signed, but without a payload: 400. */
    BAD_REQUEST,

    /** Signed: handled or ignored, 200. */
    HANDLED,
}

/** Receives one of Slack's interactions (`/webhooks/slack`). */
fun interface ReceiveSlackInteractionUseCase {
    /**
     * @param body The raw body, as received: what the signature covers.
     * @param payload The body's `payload` field, or null.
     * @param signature The `X-Slack-Signature` header.
     * @param timestamp The `X-Slack-Request-Timestamp` header.
     *
     * @return What to answer.
     */
    suspend operator fun invoke(
        body: String,
        payload: String?,
        signature: String?,
        timestamp: String?,
    ): SlackInteractionOutcome
}

/**
 * [ReceiveSlackInteractionUseCase]: Slack's signature (`v0=` and the HMAC-SHA256 of `v0:<timestamp>:<body>` with the
 * signing secret) is checked in constant time, with a timestamp at most 5 minutes away, before anything is read. A
 * Reject answers no for the member who linked the Slack account that clicked it, when that member is the request's
 * approver; the gates refuse anyone else and the click changes nothing. The Approve button is a link to the console:
 * its click is only acknowledged, since a yes needs a fresh proof of who the approver is (S2-54).
 *
 * @param apps The Slack app.
 * @param signer HMAC-SHA256.
 * @param links The members' links.
 * @param people The members.
 * @param approvals Reads a request and what its gate shows.
 * @param answer Answers a request.
 * @param clock The time.
 */
@Suppress("LongParameterList") // One port per owner: settings, crypto, links, people, gates, clock.
class ReceiveSlackInteractionUseCaseImpl(
    private val apps: SlackApps,
    private val signer: SlackSigner,
    private val links: SlackLinksRepository,
    private val people: SlackPeople,
    private val approvals: GetApprovalUseCase,
    private val answer: AnswerGateUseCase,
    private val clock: Clock,
) : ReceiveSlackInteractionUseCase {
    override suspend fun invoke(
        body: String,
        payload: String?,
        signature: String?,
        timestamp: String?,
    ): SlackInteractionOutcome {
        val app = apps.current()
        val seconds = timestamp?.toLongOrNull()
        val fresh = seconds != null && abs(clock.now().epochSeconds - seconds) <= MAX_AGE_SECONDS
        val signed = app != null && signature != null && fresh &&
            sameText(PREFIX + signer.sign(app.signingSecret, "v0:$timestamp:$body"), signature)
        return when {
            !signed -> SlackInteractionOutcome.UNAUTHORIZED
            payload == null -> SlackInteractionOutcome.BAD_REQUEST
            else -> {
                parse(payload)?.let { interaction -> rejects(interaction).forEach { reject(interaction.userId, it) } }
                SlackInteractionOutcome.HANDLED
            }
        }
    }

    private suspend fun reject(slackUserId: String, value: String) {
        val orgId = Uuid.parseOrNull(value.substringBefore(':'))
        val approvalId = Uuid.parseOrNull(value.substringAfter(':', ""))
        val member = if (orgId == null || approvalId == null) {
            null
        } else {
            links.memberOf(orgId, slackUserId)?.let { people.member(orgId, it) }
        }
        if (orgId == null || approvalId == null || member == null) return
        val actor = GateActor.person(member.login, orgId, member.role)
        try {
            // The gates refuse anyone but the request's approver, and a decided gate.
            val view = approvals(actor, approvalId)
            // A button sends no note to the agent.
            answer(actor, view.request.id, Answer.NO, view.gate.shownDigest, AnswerChannel.SLACK, null)
        } catch (_: OstromException) {
            // Another approver, a decided gate, an unknown request: the click changes nothing.
            Unit
        }
    }

    private class Interaction(val userId: String, val actions: List<JsonObject>)

    private fun parse(payload: String): Interaction? {
        val root = try {
            Json.parseToJsonElement(payload) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
        val user = root?.takeIf { it.text("type") == BLOCK_ACTIONS }?.let { (it["user"] as? JsonObject)?.text("id") }
        val actions = (root?.get("actions") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        return user?.let { Interaction(it, actions) }
    }

    private fun rejects(interaction: Interaction): List<String> = interaction.actions
        .filter { it.text("action_id") == SlackTexts.REJECT_ACTION }
        .mapNotNull { it.text("value") }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val PREFIX = "v0="
        const val BLOCK_ACTIONS = "block_actions"
        const val MAX_AGE_SECONDS = 300L
    }
}

/**
 * Compares two texts in a time that does not depend on where they differ.
 *
 * @param expected The expected text.
 * @param got The received text.
 *
 * @return Whether they are equal.
 */
internal fun sameText(expected: String, got: String): Boolean {
    val a = expected.encodeToByteArray()
    val b = got.encodeToByteArray()
    var difference = a.size xor b.size
    for (index in a.indices) {
        difference = difference or (a[index].toInt() xor (b.getOrNull(index)?.toInt() ?: 0))
    }
    return difference == 0
}
