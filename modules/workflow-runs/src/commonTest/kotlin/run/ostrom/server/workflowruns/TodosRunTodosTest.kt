// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns

import run.ostrom.server.todos.domain.TodoCheck
import run.ostrom.server.todos.domain.TodoDoneWhen
import run.ostrom.server.todos.domain.TodoItemDefinition
import run.ostrom.server.todos.domain.TodoItemStatus
import run.ostrom.server.todos.domain.TodoList
import run.ostrom.server.todos.domain.TodoListStatus
import run.ostrom.server.todos.domain.TodoRules
import run.ostrom.server.todos.domain.TodosRepository
import run.ostrom.server.workflowruns.infrastructure.TodosRunTodos
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A step delivers once its session's todo is done (TeamCode's `WorkflowTodoGate`, `409 todo_open`). */
class TodosRunTodosTest {
    private val org = Uuid.random()

    /** The sessions' todos, read only. */
    private class Todos(var list: TodoList? = null) : TodosRepository {
        val asked = mutableListOf<Pair<Uuid, String>>()

        override suspend fun find(orgId: Uuid, sessionId: String): TodoList? {
            asked += orgId to sessionId
            return list
        }

        override suspend fun create(list: TodoList): Boolean = error("unused")

        override suspend fun <T> mutate(orgId: Uuid, sessionId: String, change: (TodoList) -> Pair<TodoList, T>): T =
            error("unused")

        override suspend fun reset(orgId: Uuid, sessionId: String, check: (TodoList) -> Unit): TodoList =
            error("unused")

        override suspend fun stopHookEnabled(orgId: Uuid, memberId: String): Boolean = error("unused")

        override suspend fun stopHookDisabled(orgId: Uuid): Set<String> = error("unused")

        override suspend fun setStopHookEnabled(orgId: Uuid, memberId: String, enabled: Boolean) = error("unused")
    }

    private fun list(vararg ids: String) = TodoRules.create(
        Uuid.random(),
        org,
        "s1",
        "Ship the export.",
        emptyList(),
        ids.map {
            TodoItemDefinition(it, "Item $it", DESCRIPTION, TodoDoneWhen("done", listOf(TodoCheck.Attested("it"))))
        },
        Instant.parse("2026-11-15T10:00:00Z"),
    )

    private fun TodoList.closing(vararg ids: String, status: TodoItemStatus = TodoItemStatus.DONE) =
        copy(items = items.map { if (it.id in ids) it.copy(status = status) else it })

    private companion object {
        const val DESCRIPTION = "Do this item completely: what, where, why and what is out of scope, enough for a " +
            "new agent to do it alone."
    }

    @Test
    fun aSessionWithoutATodoOrWithADoneOrAbandonedOneDelivers() = runTest {
        val todos = Todos()
        val gate = TodosRunTodos(todos)
        assertNull(gate.refusal(org, "s1"))
        assertEquals(listOf(org to "s1"), todos.asked, "The step's own session's todo is read")
        todos.list = list("a").copy(status = TodoListStatus.ABANDONED)
        assertNull(gate.refusal(org, "s1"))
        todos.list = list("a", "b").closing("a").closing("b", status = TodoItemStatus.DROPPED)
        assertNull(gate.refusal(org, "s1"), "Every item done or dropped")
        assertNull(TodosRunTodos(null).refusal(org, "s1"), "Without the todos module")
    }

    @Test
    fun anOpenTodoNamesItsOpenItemsAndWhatToDo() = runTest {
        val todos = Todos(list("a", "b").closing("a").closing("b", status = TodoItemStatus.DROP_REQUESTED))
        val gate = TodosRunTodos(todos)
        assertEquals(
            "This step's todo still has 1 open item (b). Finish them, or drop what no longer makes sense with " +
                "todo_drop, then deliver.",
            gate.refusal(org, "s1"),
        )
        todos.list = list("a", "b", "c", "d", "e", "f", "g").closing("g")
        assertEquals(
            "This step's todo still has 6 open items (a, b, c, d, e, …). Finish them, or drop what no longer makes " +
                "sense with todo_drop, then deliver.",
            gate.refusal(org, "s1"),
        )
        todos.list = list("a", "b")
        assertEquals(
            "This step's todo still has 2 open items (a, b). Finish them, or drop what no longer makes sense with " +
                "todo_drop, then deliver.",
            gate.refusal(org, "s1"),
        )
    }
}
