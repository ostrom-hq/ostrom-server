// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.runners.domain.CloudConversationRules
import run.ostrom.server.runners.domain.CloudEventType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Follows one runner batch to keep `runners_session_subagents` exact (TeamCode's `SubagentTracker`): an agent appears
 * with its `tool.call`, counts its tool uses, ends with the result of its call, and, when the runner reports
 * background tasks, stays `running` while a live task stands for it. A batch costs a bounded number of statements,
 * whatever it carries. The caller holds the session row lock.
 *
 * @param orgId The organization.
 * @param sessionId The session.
 * @param now When the batch is stored.
 */
internal class SubagentTracker(private val orgId: Uuid, private val sessionId: Uuid, private val now: Instant) {
    private class Started(val type: String?, val description: String, val parentId: String?)

    private class Task(
        val id: String,
        val toolUseId: String,
        val type: String,
        val description: String,
        val startedAt: Instant?,
    )

    private val started = LinkedHashMap<String, Started>()
    private val steps = HashMap<String, Int>()
    private val resultsOk = LinkedHashSet<String>()
    private val resultsFailed = LinkedHashSet<String>()
    private var tasks: List<Task>? = null
    private var tasksComplete = false

    /**
     * Reads one event of the batch.
     *
     * @return The feed of [data]: its `parentToolUseId` when it is a plausible tool call id, null for the main agent.
     */
    fun see(type: CloudEventType, data: JsonObject?): String? {
        if (data == null) return null
        val feed = data.text("parentToolUseId")?.takeIf { CloudConversationRules.FEED.matches(it) }
        when (type) {
            CloudEventType.TOOL_CALL -> call(data, feed)
            CloudEventType.TOOL_RESULT -> data.text("id")?.takeIf { CloudConversationRules.FEED.matches(it) }?.let {
                val failed = (data["isError"] as? JsonPrimitive)?.content == "true"
                (if (failed) resultsFailed else resultsOk) += it
            }
            CloudEventType.TASKS -> tasks(data)
            else -> Unit
        }
        return feed
    }

    private fun call(data: JsonObject, feed: String?) {
        if (feed != null) steps[feed] = (steps[feed] ?: 0) + 1
        val id = data.text("id")?.takeIf { CloudConversationRules.FEED.matches(it) } ?: return
        if (data.text("name") !in AGENT_TOOLS) return
        val input = data["input"] as? JsonObject
        started.getOrPut(id) {
            Started(input.text("subagent_type")?.take(TYPE_CHARS), input.text("description").orEmpty().take(TEXT), feed)
        }
    }

    private fun tasks(data: JsonObject) {
        val list = data["tasks"] as? JsonArray ?: return
        tasks = list.mapNotNull { it as? JsonObject }.mapNotNull(::task)
        // A set cut at its limit does not say which tasks ended, so only a whole set closes agents.
        tasksComplete = ((data["count"] as? JsonPrimitive)?.intOrNull ?: list.size) <= list.size
    }

    /** A live background agent; background commands and monitors carry no `subagentType`. */
    private fun task(task: JsonObject): Task? {
        val toolUseId = task.text("toolUseId")?.takeIf { CloudConversationRules.FEED.matches(it) }
        val subagent = task.text("subagentType")
        val id = task.text("id")
        if (toolUseId == null || subagent == null || id == null) return null
        val startedAt = (task["startedAt"] as? JsonPrimitive)?.longOrNull?.let { Instant.fromEpochMilliseconds(it) }
        return Task(id.take(TYPE_CHARS), toolUseId, subagent.take(TYPE_CHARS), task.text("description").orEmpty()
            .take(TEXT), startedAt)
    }

    /** Writes what the batch said. */
    fun flush() {
        val a = RunnersSessionSubagents
        val scope = (a.orgId eq orgId) and (a.sessionId eq sessionId)
        if (started.isNotEmpty()) {
            a.batchInsert(started.entries, ignore = true, shouldReturnGeneratedValues = false) { (id, agent) ->
                row(id, agent.type, agent.description, now, agent.parentId, null)
            }
        }
        for ((id, count) in steps) a.update({ scope and (a.id eq id) }) { it[a.steps] = a.steps + count }
        // A call that returned ends its agent, unless a live task still stands for it (a background agent's call
        // returns at once).
        for ((ids, final) in listOf(resultsOk to COMPLETED, resultsFailed to FAILED)) {
            if (ids.isEmpty()) continue
            a.update({ scope and (a.id inList ids) and a.taskId.isNull() and (a.state eq RUNNING) }) {
                it[state] = final
                it[endedAt] = now
                it[resultError] = final == FAILED
            }
        }
        if (resultsFailed.isNotEmpty()) {
            a.update({ scope and (a.id inList resultsFailed) and a.taskId.isNotNull() }) { it[resultError] = true }
        }
        tasks?.let(::flushTasks)
    }

    private fun flushTasks(live: List<Task>) {
        val a = RunnersSessionSubagents
        val scope = (a.orgId eq orgId) and (a.sessionId eq sessionId)
        val byAgent = live.associateBy { it.toolUseId }
        val existing = a.select(a.id, a.taskId, a.state).where { scope and (a.id inList byAgent.keys) }
            .associate { it[a.id] to (it[a.taskId] to it[a.state]) }
        // An agent whose call this server never saw (it started before, or the call was purged) still shows.
        val missing = live.filter { it.toolUseId !in existing }
        if (missing.isNotEmpty()) {
            a.batchInsert(missing, ignore = true, shouldReturnGeneratedValues = false) { task ->
                row(task.toolUseId, task.type, task.description, task.startedAt ?: now, null, task.id)
            }
        }
        // An agent now standing for another task, or running again.
        val moved = live.filter { task -> existing[task.toolUseId]?.let { it != (task.id to RUNNING) } == true }
        for (task in moved) {
            a.update({ scope and (a.id eq task.toolUseId) }) {
                it[a.taskId] = task.id
                it[a.state] = RUNNING
                it[endedAt] = null
            }
        }
        if (!tasksComplete) return
        // Agents that had a live task and no longer do are over.
        val gone = if (byAgent.isEmpty()) Op.TRUE else (a.id notInList byAgent.keys)
        for (failed in listOf(false, true)) {
            val over = a.taskId.isNotNull() and (a.state eq RUNNING) and (a.resultError eq failed) and gone
            a.update({ scope and over }) {
                it[state] = if (failed) FAILED else COMPLETED
                it[endedAt] = now
            }
        }
    }

    @Suppress("LongParameterList") // The row's columns.
    private fun org.jetbrains.exposed.v1.core.statements.BatchInsertStatement.row(
        id: String,
        type: String?,
        description: String,
        startedAt: Instant,
        parentId: String?,
        taskId: String?,
    ) {
        val a = RunnersSessionSubagents
        this[a.sessionId] = sessionId
        this[a.orgId] = orgId
        this[a.id] = id
        this[a.type] = type
        this[a.description] = description
        this[a.state] = RUNNING
        this[a.startedAt] = startedAt
        this[a.steps] = 0
        this[a.parentId] = parentId
        this[a.taskId] = taskId
        this[a.resultError] = false
    }

    private fun JsonObject?.text(name: String): String? =
        (this?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val RUNNING = "running"
        const val COMPLETED = "completed"
        const val FAILED = "failed"
        const val TYPE_CHARS = 64
        const val TEXT = 200
    }
}

/** `Task` is the earlier name of the `Agent` tool. */
private val AGENT_TOOLS = setOf("Agent", "Task")
