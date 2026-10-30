// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.slack.domain

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.Answer
import dev.ostrom.server.gates.domain.models.AnswerChannel
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.usecases.AnswerGateUseCase
import dev.ostrom.server.gates.domain.usecases.GetApprovalUseCase
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.kernel.logging.Logger

/** What a click on a request's button did. */
enum class SlackClickOutcome {
    /** The click answered the request. */
    ANSWERED,

    /** Nobody of any organization is this Slack member. */
    UNKNOWN_MEMBER,

    /** The gate is answered in Ostrom only (SECURITY-AUDIT S2-31). */
    NOT_ON_SLACK,

    /** The gates refused the answer (not the approver, already decided, changed, self-approval). */
    REFUSED,
}

/** Answers a request from a click on its Slack message (the interaction's signature is checked before). */
interface HandleSlackClickUseCase {
    /**
     * @param click The click.
     * @return What it did; the message on Slack shows it.
     */
    suspend operator fun invoke(click: SlackClick): SlackClickOutcome
}

/**
 * [HandleSlackClickUseCase] through the gates' own answer: the person is the one linked to this Slack member, the
 * request must be theirs (an admin never answers in an approver's place), the yes names the digest the message
 * showed, and the gate, as stored, must be one a yes on Slack may open ([SlackRisk]).
 *
 * @param repository Links and messages.
 * @param approvals Reads the request as its approver.
 * @param answer The gates' answer.
 * @param api Replaces the message after a refused click.
 * @param messages The messages.
 * @param logger Logs refusals (never their message).
 */
class HandleSlackClickUseCaseImpl(
    private val repository: SlackRepository,
    private val approvals: GetApprovalUseCase,
    private val answer: AnswerGateUseCase,
    private val api: SlackApi,
    private val messages: SlackMessages,
    private val logger: Logger,
) : HandleSlackClickUseCase {
    override suspend fun invoke(click: SlackClick): SlackClickOutcome {
        val link = repository.linkOf(click.teamId, click.userId)
            ?: return refuse(click, UNKNOWN, SlackClickOutcome.UNKNOWN_MEMBER)
        val actor = GateActor(link.personId, link.orgId, ActorKind.USER, personId = link.personId)
        return try {
            val approval = approvals(actor, click.requestId)
            if (!SlackRisk.answerable(approval.gate)) return refuse(click, IN_OSTROM, SlackClickOutcome.NOT_ON_SLACK)
            val choice = if (click.yes) Answer.YES else Answer.NO
            // The decision updates every message of the gate (SlackGateChannel.decided).
            answer(actor, click.requestId, choice, click.shownDigest, AnswerChannel.SLACK)
            SlackClickOutcome.ANSWERED
        } catch (refused: OstromException) {
            logger.info("slack answer refused", mapOf("request_id" to click.requestId, "kind" to refused.kind.name))
            val why = "Ostrom did not take this answer (${refused.kind.name.lowercase()})."
            refuse(click, why, SlackClickOutcome.REFUSED)
        }
    }

    // The outcome stands even when Slack cannot show it: the request still waits in the console.
    private suspend fun refuse(click: SlackClick, why: String, outcome: SlackClickOutcome): SlackClickOutcome {
        try {
            api.update(click.channel, click.ts, messages.refused(click.requestId.toString(), why))
        } catch (down: SlackCallException) {
            logger.warn("slack update failed", mapOf("request_id" to click.requestId), down)
        }
        return outcome
    }

    private companion object {
        const val UNKNOWN = "This Slack account is not linked to a member of an organization."
        const val IN_OSTROM = "This gate is answered in Ostrom: a yes on Slack covers a small push only."
    }
}
