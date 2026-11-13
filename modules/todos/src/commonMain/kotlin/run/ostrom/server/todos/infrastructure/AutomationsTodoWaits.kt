// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.todos.infrastructure

import run.ostrom.server.automations.domain.AnswerWaitUseCase
import run.ostrom.server.automations.domain.AskWait
import run.ostrom.server.automations.domain.AskWaitUseCase
import run.ostrom.server.automations.domain.WaitAction
import run.ostrom.server.automations.domain.WaitCaller
import run.ostrom.server.automations.domain.WaitFilter
import run.ostrom.server.automations.domain.WaitRef
import run.ostrom.server.automations.domain.WaitSpec
import run.ostrom.server.automations.domain.WaitStatus
import run.ostrom.server.automations.domain.WaitsRepository
import run.ostrom.server.todos.domain.TodoItem
import run.ostrom.server.todos.domain.TodoOwner
import run.ostrom.server.todos.domain.TodoVerifier
import run.ostrom.server.todos.domain.TodoWaitState
import run.ostrom.server.todos.domain.TodoWaits
import kotlin.uuid.Uuid

/**
 * [TodoWaits] on the automations module: a drop request and a stall ask the session's person an ordinary wait
 * ("Waiting for you"), asked in the session in their name, so it notifies them and counts within the session's limit
 * of open waits like any other; its answer is read back from the waits' table by id.
 *
 * @param ask Asks.
 * @param answer Cancels.
 * @param repository Reads.
 */
internal class AutomationsTodoWaits(
    private val ask: AskWaitUseCase,
    private val answer: AnswerWaitUseCase,
    private val repository: WaitsRepository,
) : TodoWaits {
    override suspend fun askDrop(owner: TodoOwner, item: TodoItem, reason: String): String = ask(
        owner,
        "Drop this item of the todo?",
        "**${item.name}** (`${item.id}`)\n\n$reason\n\n${item.description}",
        listOf(
            WaitAction(TodoVerifier.DROP_ACTION, "Drop it", style = "danger"),
            WaitAction(TodoVerifier.KEEP_ACTION, "Keep it", style = "primary"),
        ),
    )

    override suspend fun askBlocked(owner: TodoOwner, title: String, body: String, dropItem: Boolean): String = ask(
        owner,
        title,
        body,
        listOfNotNull(
            WaitAction(TodoVerifier.KEEP_ACTION, "Resume", style = "primary"),
            WaitAction(TodoVerifier.DROP_ACTION, "Drop this item", style = "danger").takeIf { dropItem },
            WaitAction(TodoVerifier.STOP_ACTION, "Stop here"),
        ),
    )

    private suspend fun ask(owner: TodoOwner, title: String, body: String, actions: List<WaitAction>): String {
        val question = WaitSpec(
            title = title.take(MAX_TITLE),
            body = body.take(MAX_BODY),
            actions = actions,
            refs = listOf(WaitRef("session", id = owner.segment)),
        )
        return ask.ask(caller(owner), AskWait(question, sessionId = owner.segment), null).wait.id.toString()
    }

    override suspend fun cancel(owner: TodoOwner, waitId: String) {
        answer.cancel(caller(owner), Uuid.parse(waitId))
    }

    override suspend fun states(orgId: Uuid, waitIds: Collection<String>): Map<String, TodoWaitState> =
        waitIds.mapNotNull { id ->
            Uuid.parseOrNull(id)?.let { repository.get(orgId, it) }?.let { wait ->
                id to TodoWaitState(wait.status.wire, wait.answer?.action, wait.sessionId)
            }
        }.toMap()

    override suspend fun anyOpen(orgId: Uuid, segments: List<String>): Boolean =
        repository.list(WaitFilter(orgId, status = WaitStatus.OPEN, sessionIds = segments), null, 1).isNotEmpty()

    /** The session's person, asking in their own name: never an agent, so the wait is theirs to answer. */
    private fun caller(owner: TodoOwner) = WaitCaller(
        orgId = owner.orgId,
        memberId = owner.memberId,
        admin = false,
        interactive = false,
        segments = owner.segments,
    )

    private companion object {
        const val MAX_TITLE = 200
        const val MAX_BODY = 32_000
    }
}
