// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.todos.fakes

import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.todos.domain.TodoArtifacts
import run.ostrom.server.todos.domain.TodoCaller
import run.ostrom.server.todos.domain.TodoItem
import run.ostrom.server.todos.domain.TodoList
import run.ostrom.server.todos.domain.TodoOwner
import run.ostrom.server.todos.domain.TodoSession
import run.ostrom.server.todos.domain.TodoSessions
import run.ostrom.server.todos.domain.TodoWaitState
import run.ostrom.server.todos.domain.TodoWaits
import run.ostrom.server.todos.domain.TodosRepository
import run.ostrom.server.todos.domain.todoNotFound
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

val ORG_B: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000bb")
const val ADA = "00000000-0000-0000-0000-0000000000ad"
const val BEN = "00000000-0000-0000-0000-0000000000be"
const val ROOT = "00000000-0000-0000-0000-00000000000f"

/** Ada, the person of session `s1`. */
val ada = TodoCaller(ORG_A, ADA, admin = false)

/** Ada's agent, confined to the open segment of `s1`. */
val adaAgent = TodoCaller(ORG_A, ADA, admin = false, agentSession = "s1~r2")

/** Ben, a member who is not the person of `s1`. */
val ben = TodoCaller(ORG_A, BEN, admin = false)

/** Root, an administrator of org A. */
val root = TodoCaller(ORG_A, ROOT, admin = true)

/** A clock the test moves. */
class TestClock(var now: Instant = Instant.parse("2026-11-13T14:02:00Z")) : Clock {
    override fun now(): Instant = now
}

/** Sessions: `s1` (segments `s1`, `s1~r2`) is Ada's; `s2` is Ben's. */
class FakeSessions : TodoSessions {
    override suspend fun find(orgId: Uuid, sessionId: String, memberId: String): TodoSession? = when {
        orgId != ORG_A -> null
        sessionId == "s1" || sessionId == "s1~r2" -> TodoSession("s1", listOf("s1", "s1~r2"), memberId == ADA)
        sessionId == "s2" -> TodoSession("s2", listOf("s2"), memberId == BEN)
        else -> null
    }
}

/** Todos in memory, with a switch to fail the next change. */
class InMemoryTodos : TodosRepository {
    val lists = mutableMapOf<Pair<Uuid, String>, TodoList>()
    val hookOff = mutableSetOf<Pair<Uuid, String>>()
    var failNextChange = false
    var changes = 0

    /** What another replica changes in a list before the change of that number (0 for the first) reads it. */
    val races = mutableMapOf<Int, (TodoList) -> TodoList>()

    var list: TodoList?
        get() = lists[ORG_A to "s1"]
        set(value) {
            if (value == null) lists.remove(ORG_A to "s1") else lists[ORG_A to "s1"] = value
        }

    override suspend fun find(orgId: Uuid, sessionId: String): TodoList? = lists[orgId to sessionId]

    override suspend fun create(list: TodoList): Boolean {
        val key = list.orgId to list.sessionId
        if (key in lists) return false
        lists[key] = list
        return true
    }

    override suspend fun <T> mutate(orgId: Uuid, sessionId: String, change: (TodoList) -> Pair<TodoList, T>): T {
        if (failNextChange) {
            failNextChange = false
            error("The database is gone.")
        }
        val stored = lists[orgId to sessionId] ?: throw todoNotFound()
        val before = races.remove(changes)?.invoke(stored) ?: stored
        val (after, value) = change(before)
        lists[orgId to sessionId] = after
        changes++
        return value
    }

    override suspend fun stopHookEnabled(orgId: Uuid, memberId: String): Boolean = (orgId to memberId) !in hookOff

    override suspend fun stopHookDisabled(orgId: Uuid): Set<String> =
        hookOff.filter { it.first == orgId }.map { it.second }.toSet()

    override suspend fun setStopHookEnabled(orgId: Uuid, memberId: String, enabled: Boolean) {
        if (enabled) hookOff.remove(orgId to memberId) else hookOff.add(orgId to memberId)
    }
}

/** Waits in memory: what was asked, cancelled, and the states the test sets. */
class FakeWaits : TodoWaits {
    data class Asked(val owner: TodoOwner, val title: String, val body: String, val dropItem: Boolean?)

    val asked = mutableListOf<Asked>()
    val cancelled = mutableListOf<String>()
    val states = mutableMapOf<String, TodoWaitState>()
    var open = false
    var cancelFailure: Throwable? = null

    override suspend fun askDrop(owner: TodoOwner, item: TodoItem, reason: String): String {
        asked += Asked(owner, item.id, reason, null)
        return "drop-${asked.size}"
    }

    override suspend fun askBlocked(owner: TodoOwner, title: String, body: String, dropItem: Boolean): String {
        asked += Asked(owner, title, body, dropItem)
        return "blocked-${asked.size}"
    }

    override suspend fun cancel(owner: TodoOwner, waitId: String) {
        cancelFailure?.let { throw it }
        cancelled += waitId
    }

    override suspend fun states(orgId: Uuid, waitIds: Collection<String>): Map<String, TodoWaitState> =
        waitIds.mapNotNull { id -> states[id]?.let { id to it } }.toMap()

    override suspend fun anyOpen(orgId: Uuid, segments: List<String>): Boolean = open
}

/** Artifacts and their latest version. */
class FakeArtifacts : TodoArtifacts {
    val versions = mutableMapOf<String, Int>()

    override suspend fun versions(orgId: Uuid, artifactIds: Collection<String>): Map<String, Int> =
        artifactIds.mapNotNull { id -> versions[id]?.let { id to it } }.toMap()
}

/** The members of org A: Ada, Ben and Root. */
class FakeMembers : MemberDirectory {
    override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean =
        orgId == ORG_A && memberId in setOf(ADA, BEN, ROOT)
}
