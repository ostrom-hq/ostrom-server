// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.todos.domain

import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * One check's outcome as the process that ran it reports it.
 *
 * @property index The position in the item's stored checks.
 * @property passed True, false, or null for unknown.
 * @property observed What was seen.
 * @property urls Where.
 */
data class TodoCheckReport(
    val index: Int,
    val passed: Boolean?,
    val observed: String,
    val urls: List<String> = emptyList(),
)

/**
 * What the server sees by itself, and the owner's answers to drop requests: `artifact_published` and
 * `wait_answered` are verified here, through what the item is linked to, never from the caller's report; a drop
 * request follows its wait's answer.
 *
 * @param repository The todos.
 * @param waits The waits.
 * @param artifacts The artifacts.
 * @param clock The time.
 */
class TodoVerifier(
    private val repository: TodosRepository,
    private val waits: TodoWaits,
    private val artifacts: TodoArtifacts,
    private val clock: Clock,
) {
    /** Now, to the millisecond as stored. */
    fun now(): Instant = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())

    /**
     * The outcome of the checks the server verifies, for several items, with one read of the artifacts and one of
     * the waits however many items there are.
     *
     * @param segments The session's segments: a wait asked elsewhere is not this session's.
     * @return By item id, by check.
     */
    suspend fun serverChecks(
        orgId: Uuid,
        segments: List<String>,
        items: List<TodoItem>,
    ): Map<String, Map<TodoCheck, TodoCheckResult>> {
        val artifactIds = items.flatMap { item ->
            item.doneWhen.checks.filterIsInstance<TodoCheck.ArtifactPublished>().mapNotNull { artifactOf(item, it) }
        }.distinct()
        val waitIds = items.filter { item -> item.doneWhen.checks.any { it is TodoCheck.WaitAnswered } }
            .mapNotNull { linked(it, "wait") }.distinct()
        val versions = if (artifactIds.isEmpty()) emptyMap() else artifacts.versions(orgId, artifactIds)
        val states = if (waitIds.isEmpty()) emptyMap() else waits.states(orgId, waitIds)
        return items.associate { item ->
            item.id to item.doneWhen.checks.mapNotNull { check ->
                when (check) {
                    is TodoCheck.ArtifactPublished -> check to artifact(check, artifactOf(item, check), versions)
                    is TodoCheck.WaitAnswered -> check to wait(check, linked(item, "wait"), states, segments)
                    else -> null
                }
            }.toMap()
        }
    }

    private fun linked(item: TodoItem, kind: String): String? = item.links.firstOrNull { it.kind == kind }?.ref

    private fun artifactOf(item: TodoItem, check: TodoCheck.ArtifactPublished): String? =
        check.id ?: linked(item, "artifact")

    private fun artifact(check: TodoCheck.ArtifactPublished, id: String?, versions: Map<String, Int>): TodoCheckResult {
        val version = id?.let { versions[it] }
        val min = check.minVersion ?: 1
        return when {
            id == null -> TodoCheckResult(
                check,
                false,
                "No artifact is linked to this item: publish it, then link it with todo_link.",
            )
            version == null -> TodoCheckResult(check, false, "Artifact $id is not published.")
            version < min ->
                TodoCheckResult(check, false, "Artifact $id is at version $version, version $min is needed.")
            else -> TodoCheckResult(check, true, "Artifact $id is published (version $version).")
        }
    }

    private fun wait(
        check: TodoCheck.WaitAnswered,
        id: String?,
        states: Map<String, TodoWaitState>,
        segments: List<String>,
    ): TodoCheckResult {
        val state = id?.let { states[it] }?.takeIf { it.sessionId in segments }
        return when {
            id == null -> TodoCheckResult(
                check,
                false,
                "No wait is linked to this item: ask with ask_human, then link the wait with todo_link.",
            )
            state == null -> TodoCheckResult(check, false, "Wait $id is not one of this session's.")
            state.status != RESOLVED ->
                TodoCheckResult(check, false, "Wait $id is ${state.status}: nobody answered it yet.")
            check.action != null && state.action != check.action ->
                TodoCheckResult(check, false, "Wait $id was answered with '${state.action}', not '${check.action}'.")
            else -> TodoCheckResult(check, true, "Wait $id was answered with '${state.action}'.")
        }
    }

    /**
     * The results of one item: what the server saw for its own checks, what the caller reported (by position) for
     * the others. A report on a check the server verifies is ignored, even a lie.
     *
     * @return The results.
     * @throws TodoException `invalid_proof` when a report is out of range, repeated or too long.
     */
    fun results(
        item: TodoItem,
        reports: List<TodoCheckReport>,
        observed: Map<TodoCheck, TodoCheckResult>,
    ): List<TodoCheckResult> {
        val checks = item.doneWhen.checks
        if (reports.any { it.index !in checks.indices } || reports.map { it.index }.distinct().size != reports.size) {
            throw invalidTodo(
                "invalid_proof",
                "Report each check of '${item.id}' once, by its position 0 to ${checks.size - 1}.",
            )
        }
        val reported = reports.filter { checks[it.index] !in observed.keys }.map { report ->
            val badUrl = report.urls.any { !it.startsWith("https://") || it.length > TodoRules.MAX_LINK }
            if (report.observed.length > TodoRules.MAX_PROOF || report.urls.size > TodoRules.MAX_PROOF_URLS || badUrl) {
                throw invalidTodo(
                    "invalid_proof",
                    "Keep observed under ${TodoRules.MAX_PROOF} characters and give at most " +
                        "${TodoRules.MAX_PROOF_URLS} https URLs.",
                )
            }
            TodoCheckResult(checks[report.index], report.passed, report.observed, report.urls)
        }
        return reported + observed.values
    }

    /**
     * The session's list with the owner's answers to its drop requests applied, in one read of the waits for all of
     * them: `yes` drops the item, `no`, another action or a cancelled wait puts it back to `todo`.
     *
     * @return The list, or null when the session has none.
     */
    suspend fun reconcile(orgId: Uuid, sessionId: String): TodoList? {
        val list = repository.find(orgId, sessionId) ?: return null
        val pending = list.items.filter { it.status == TodoItemStatus.DROP_REQUESTED }.mapNotNull { it.dropWaitId }
        val states = if (pending.isEmpty()) emptyMap() else waits.states(orgId, pending)
        val answered = states.filterValues { it.status != OPEN }
        return if (answered.isEmpty()) list else applyAnswers(orgId, sessionId, answered)
    }

    private suspend fun applyAnswers(orgId: Uuid, sessionId: String, answered: Map<String, TodoWaitState>): TodoList {
        val now = now()
        return repository.mutate(orgId, sessionId) { current ->
            var changed = current
            current.items.filter { it.status == TodoItemStatus.DROP_REQUESTED }.forEach { item ->
                item.dropWaitId?.let(answered::get)?.let { state ->
                    val approved = state.status == RESOLVED && state.action == DROP_ACTION
                    changed = TodoRules.resolveDrop(changed, item.id, approved)
                }
            }
            changed.copy(updatedAt = now).let { it to it }
        }
    }

    /** The waits' states and the drop wait's actions. */
    companion object {
        /** An open wait. */
        const val OPEN: String = "open"

        /** An answered wait. */
        const val RESOLVED: String = "resolved"

        /** The action that drops an item. */
        const val DROP_ACTION: String = "yes"

        /** The action that keeps it (Resume). */
        const val KEEP_ACTION: String = "no"

        /** The action that tells the agent to stop. */
        const val STOP_ACTION: String = "stop"
    }
}
