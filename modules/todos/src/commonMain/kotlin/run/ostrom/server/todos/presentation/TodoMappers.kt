// SPDX-License-Identifier: Apache-2.0
@file:Suppress("TooManyFunctions") // The mappers of one contract, both ways.

package run.ostrom.server.todos.presentation

import run.ostrom.core.api.models.SessionTodo
import run.ostrom.core.api.models.TodoContext
import run.ostrom.core.api.models.TodoDoneWhen as TodoDoneWhenDto
import run.ostrom.core.api.models.TodoHookEventKind
import run.ostrom.core.api.models.TodoItemInput
import run.ostrom.core.api.models.TodoItemOrigin
import run.ostrom.core.api.models.TodoLinkKind
import run.ostrom.core.api.models.TodoStatus
import run.ostrom.core.api.models.WaitRef
import run.ostrom.server.kernel.http.NullsKept
import run.ostrom.server.todos.domain.TodoCheck
import run.ostrom.server.todos.domain.TodoCheckReport
import run.ostrom.server.todos.domain.TodoDoneWhen
import run.ostrom.server.todos.domain.TodoItem
import run.ostrom.server.todos.domain.TodoItemDefinition
import run.ostrom.server.todos.domain.TodoItemStatus
import run.ostrom.server.todos.domain.TodoLink
import run.ostrom.server.todos.domain.TodoList
import run.ostrom.server.todos.domain.TodoOrigin
import run.ostrom.server.todos.domain.TodoRef
import run.ostrom.server.todos.domain.TodoRules
import run.ostrom.server.todos.domain.TodoStopDecision
import run.ostrom.server.todos.domain.TodoStopItemReport
import run.ostrom.server.todos.domain.invalidTodo
import run.ostrom.core.api.models.Todo as TodoDto
import run.ostrom.core.api.models.TodoCheck as TodoCheckDto
import run.ostrom.core.api.models.TodoCheckReport as TodoCheckReportDto
import run.ostrom.core.api.models.TodoHookEvent as TodoHookEventDto
import run.ostrom.core.api.models.TodoItem as TodoItemDto
import run.ostrom.core.api.models.TodoItemStatus as TodoItemStatusDto
import run.ostrom.core.api.models.TodoLink as TodoLinkDto
import run.ostrom.core.api.models.TodoNote as TodoNoteDto
import run.ostrom.core.api.models.TodoProof as TodoProofDto
import run.ostrom.core.api.models.TodoStopAction as TodoStopActionDto
import run.ostrom.core.api.models.TodoStopDecision as TodoStopDecisionDto
import run.ostrom.core.api.models.TodoStopItemReport as TodoStopItemReportDto

/** `SessionTodo` with `todo` written as `null` while the session has none (required and nullable). */
object SessionTodoAnswer : NullsKept<SessionTodo>(SessionTodo.serializer(), "todo")

/** `TodoContext` with `text` written as `null` without an active list (required and nullable). */
object TodoContextAnswer : NullsKept<TodoContext>(TodoContext.serializer(), "text")

/** The contract's form of a list: its items, the next one to do, and what the Stop hook recorded. */
internal fun TodoList.toDto(): TodoDto = TodoDto(
    id = id.toString(),
    sessionId = sessionId,
    reference = reference,
    refs = refs.map { WaitRef(it.kind, it.id, it.url, it.title) },
    status = TodoStatus.entries.first { it.value == status.wire },
    stall = stall,
    items = items.map { it.toDto(createdAt.toString()) },
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    // As the messages show it: the item in progress, else the first ready one; none once every item is closed.
    nextItemId = (items.firstOrNull { it.status == TodoItemStatus.DOING } ?: TodoRules.next(this))?.id,
    hookEvents = hookEvents.map {
        TodoHookEventDto(it.at.toString(), TodoHookEventKind.entries.first { kind -> kind.value == it.kind }, it.text)
    },
)

// A link stored without its date (a PR the Stop hook found) shows the list's creation: a date that never moves.
private fun TodoItem.toDto(listCreatedAt: String): TodoItemDto = TodoItemDto(
    id = id,
    name = name,
    description = description,
    doneWhen = TodoDoneWhenDto(doneWhen.text, doneWhen.checks.map { it.toDto() }),
    dependsOn = dependsOn,
    origin = if (origin == TodoOrigin.PLAN) TodoItemOrigin.PLAN else TodoItemOrigin.DISCOVERED,
    originReason = originReason,
    status = TodoItemStatusDto.entries.first { it.value == status.wire },
    proof = proof?.let { TodoProofDto(it.at.toString(), it.passed, it.text, it.urls) },
    notes = notes.map { TodoNoteDto(it.at.toString(), it.text) },
    dropReason = dropReason,
    dropWaitId = dropWaitId,
    links = links.map { link ->
        TodoLinkDto(
            TodoLinkKind.entries.first { it.value == link.kind },
            link.ref,
            link.number,
            link.sha,
            link.tag,
            if (link.at == null) listCreatedAt else link.at.toString(),
        )
    },
    unverified = unverified,
)

private fun TodoCheck.toDto(): TodoCheckDto = when (this) {
    is TodoCheck.PrMerged -> TodoCheckDto.PrMerged(repo)
    is TodoCheck.PrClosed -> TodoCheckDto.PrClosed(repo, reason)
    is TodoCheck.ReleaseContains -> TodoCheckDto.ReleaseContains(repo, of, commit)
    is TodoCheck.CiGreen -> TodoCheckDto.CiGreen(repo, of, ref, workflow)
    is TodoCheck.Command -> TodoCheckDto.Command(run, cwd, expectExit, timeoutSeconds)
    is TodoCheck.ArtifactPublished -> TodoCheckDto.ArtifactPublished(id, minVersion)
    is TodoCheck.WaitAnswered -> TodoCheckDto.WaitAnswered(action)
    is TodoCheck.Attested -> TodoCheckDto.Attested(what)
}

/**
 * The domain's form of a check.
 *
 * @throws run.ostrom.server.todos.domain.TodoException `invalid_check` for a type this server does not know.
 */
internal fun TodoCheckDto.toDomain(): TodoCheck = when (this) {
    is TodoCheckDto.PrMerged -> TodoCheck.PrMerged(repo)
    is TodoCheckDto.PrClosed -> TodoCheck.PrClosed(repo, reason)
    is TodoCheckDto.ReleaseContains -> TodoCheck.ReleaseContains(repo, of, commit)
    is TodoCheckDto.CiGreen -> TodoCheck.CiGreen(repo, of, ref, workflow)
    is TodoCheckDto.Command -> TodoCheck.Command(run, cwd, expectExit, timeoutSeconds)
    is TodoCheckDto.ArtifactPublished -> TodoCheck.ArtifactPublished(id, minVersion)
    is TodoCheckDto.WaitAnswered -> TodoCheck.WaitAnswered(action)
    is TodoCheckDto.Attested -> TodoCheck.Attested(what)
    is TodoCheckDto.Unknown -> throw invalidTodo(
        "invalid_check",
        "Check type '${type.take(MAX_ECHO)}' is not one this server knows: use pr_merged, pr_closed, " +
            "release_contains, ci_green, command, artifact_published, wait_answered or attested.",
    )
}

/** The domain's form of an item an agent writes. */
internal fun TodoItemInput.toDomain(): TodoItemDefinition {
    val condition = TodoDoneWhen(doneWhen.text, doneWhen.checks.map { it.toDomain() })
    return TodoItemDefinition(id, name, description, condition, dependsOn)
}

/** The domain's form of the plan's references. */
internal fun List<WaitRef>.toRefs(): List<TodoRef> = map { TodoRef(it.kind, it.id, it.url, it.title) }

/**
 * The domain's form of a link kind.
 *
 * @throws run.ostrom.server.todos.domain.TodoException `invalid_link` for a kind this server does not know.
 */
internal fun TodoLinkKind.toDomain(): String {
    if (this == TodoLinkKind.UNKNOWN) {
        throw invalidTodo("invalid_link", "Use a link of kind pr, release, artifact or wait.")
    }
    return value
}

/** The domain's form of what one check observed. */
internal fun TodoCheckReportDto.toDomain(): TodoCheckReport = TodoCheckReport(index, passed, observed, urls)

/** The domain's form of the checks the Stop hook ran for one item. */
internal fun TodoStopItemReportDto.toDomain(): TodoStopItemReport =
    TodoStopItemReport(itemId, checks.map { it.toDomain() })

/** The contract's form of a stop's answer. */
internal fun TodoStopDecision.toDto(): TodoStopDecisionDto = TodoStopDecisionDto(
    action = TodoStopActionDto.entries.first { it.value == action.wire },
    todo = list?.toDto(),
    reason = reason,
    why = why,
    escalated = escalated,
)

/** The domain's form of a link an agent sends. */
internal fun link(kind: TodoLinkKind, ref: String, number: Int?, sha: String?, tag: String?): TodoLink =
    TodoLink(kind.toDomain(), ref, number, sha, tag)

private const val MAX_ECHO = 40
