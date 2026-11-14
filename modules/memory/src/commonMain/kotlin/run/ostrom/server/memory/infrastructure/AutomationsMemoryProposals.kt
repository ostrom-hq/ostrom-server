// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.infrastructure

import run.ostrom.server.automations.domain.AnswerWaitUseCase
import run.ostrom.server.automations.domain.AskWait
import run.ostrom.server.automations.domain.AskWaitUseCase
import run.ostrom.server.automations.domain.WaitAction
import run.ostrom.server.automations.domain.WaitAgentResolver
import run.ostrom.server.automations.domain.WaitCaller
import run.ostrom.server.automations.domain.WaitClosedException
import run.ostrom.server.automations.domain.WaitField
import run.ostrom.server.automations.domain.WaitFieldType
import run.ostrom.server.automations.domain.WaitRef
import run.ostrom.server.automations.domain.WaitSpec
import run.ostrom.server.automations.domain.WaitStatus
import run.ostrom.server.automations.domain.WaitsRepository
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.outsideOrganization
import run.ostrom.server.memory.domain.MemoryCaller
import run.ostrom.server.memory.domain.MemoryEntry
import run.ostrom.server.memory.domain.MemoryProposalWaits
import run.ostrom.server.memory.domain.MemoryRules
import run.ostrom.server.memory.domain.MemorySessionNotFoundException
import run.ostrom.server.memory.domain.ProposalSession
import run.ostrom.server.memory.domain.ProposalWait
import run.ostrom.server.sessions.domain.repositories.SessionsRepository
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.Uuid

/**
 * [MemoryProposalWaits] on the automations module: a proposal asks the session's person an ordinary wait ("Waiting
 * for you"), asked in the session in their name, so it notifies them and wakes the agent's session like any answer;
 * its answer is read back from the waits' table by id. An agent token's session is its launch's (one reading of the
 * token, the gates' and the waits'); a person's relayed agent names its session, which must be theirs.
 *
 * @param ask Asks.
 * @param answer Cancels.
 * @param repository Reads.
 * @param agents Resolves an agent token inside its session.
 * @param sessions The sessions module's segments.
 * @param logins The members' logins, whose a session keeps (`dev`).
 */
@Suppress("LongParameterList") // The waits' three use cases and the two readings of a session.
internal class AutomationsMemoryProposals(
    private val ask: AskWaitUseCase,
    private val answer: AnswerWaitUseCase,
    private val repository: WaitsRepository,
    private val agents: WaitAgentResolver,
    private val sessions: SessionsRepository,
    private val logins: MemberLogins,
) : MemoryProposalWaits {
    override suspend fun session(caller: MemoryCaller, requested: String): ProposalSession {
        val principal = caller.principal
        if (principal.kind == PrincipalKind.AGENT) {
            val agent = agents.resolve(principal, requested) ?: throw principal.outsideOrganization()
            return ProposalSession(agent.sessionId, agent.segments)
        }
        val segments = sessions.segmentsOf(caller.orgId, requested)
        val first = segments.firstOrNull() ?: throw MemorySessionNotFoundException()
        // A session whose person is no member any more is nobody's here.
        val owner = logins.idsOf(caller.orgId, listOf(first.dev))[first.dev].toString()
        if (owner != caller.memberId) throw MemorySessionNotFoundException()
        return ProposalSession(segments.last().id, segments.map { it.id })
    }

    override suspend fun open(caller: MemoryCaller, session: ProposalSession, entry: MemoryEntry): Uuid {
        val question = WaitSpec(
            title = "Add to the team memory: ${entry.title}".take(MAX_TITLE),
            body = bodyOf(entry).take(MAX_BODY),
            fields = listOf(
                WaitField("title", WaitFieldType.TEXT, "Title", default = JsonPrimitive(entry.title),
                    maxLength = MemoryRules.TITLE_LENGTH),
                WaitField("summary", WaitFieldType.TEXT, "Summary", default = JsonPrimitive(entry.summary),
                    maxLength = MemoryRules.SUMMARY_LENGTH),
                WaitField("content", WaitFieldType.TEXT, "Content", default = JsonPrimitive(entry.content),
                    maxLength = MemoryRules.CONTENT_LENGTH),
            ),
            actions = listOf(
                WaitAction(ACCEPT, "Add to the team memory", style = "primary"),
                WaitAction(REJECT, "Do not add", style = "danger"),
            ),
            refs = listOf(WaitRef("session", id = session.segment)),
        )
        val asker = WaitCaller(caller.orgId, caller.memberId, admin = false, interactive = false,
            segments = session.segments)
        return ask.ask(asker, AskWait(question, sessionId = session.segment), null).wait.id
    }

    override suspend fun cancel(orgId: Uuid, memberId: String, waitId: Uuid) {
        try {
            answer.cancel(WaitCaller(orgId, memberId, admin = false, interactive = false), waitId)
        } catch (_: WaitClosedException) {
            // Answered or cancelled meanwhile: nothing left to close.
        }
    }

    override suspend fun states(orgId: Uuid, waitIds: Collection<Uuid>): Map<Uuid, ProposalWait> =
        waitIds.mapNotNull { id ->
            repository.get(orgId, id)?.let { wait ->
                // The answer's fields are the three text fields, strings by the waits' rules.
                val values = wait.answer?.values.orEmpty().mapValues { it.value.jsonPrimitive.content }
                id to ProposalWait(id, wait.status == WaitStatus.OPEN, wait.answer?.action, values, wait.answer?.author)
            }
        }.toMap()

    /** The whole proposal, a warning when it may come from a third party, and what happens unanswered. */
    private fun bodyOf(entry: MemoryEntry): String = buildString {
        append("An agent of your session proposes this note for the **team's memory**: every member's agents will ")
        append("read its title and summary, and the whole entry on demand.\n\n")
        if (EXTERNAL in entry.tags) {
            append("> Tagged `external`: it may come from a web page, a ticket or a third party. ")
            append("Check it before accepting.\n\n")
        }
        append("**${entry.title}**\n\n${entry.summary}\n\n${entry.content}")
        if (entry.tags.isNotEmpty()) append("\n\nTags: ${entry.tags.joinToString(", ")}")
        append("\n\nEdit the text below before accepting if it needs fixing. ")
        append("Unanswered, it is dropped after ${MemoryRules.PROPOSAL_DAYS} days.")
    }

    private companion object {
        const val ACCEPT = "accept"
        const val REJECT = "reject"
        const val EXTERNAL = "external"
        const val MAX_TITLE = 200
        const val MAX_BODY = 32_000
    }
}
