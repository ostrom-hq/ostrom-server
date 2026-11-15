// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.infrastructure

import run.ostrom.server.todos.domain.TodoListStatus
import run.ostrom.server.todos.domain.TodosRepository
import run.ostrom.server.workflowruns.domain.services.RunTodos
import kotlin.uuid.Uuid

/**
 * [RunTodos] on the todos module (TeamCode's `WorkflowTodoGate` over `TodoService.openItems`): a step whose session
 * keeps an active todo with items neither done nor dropped delivers once they are, the list being what the step
 * promised. A session without a todo, or whose todo is done or abandoned, delivers as it is.
 *
 * @param todos The sessions' todos, keyed on the step's cloud session id; null where the todos module is not loaded.
 */
class TodosRunTodos(private val todos: TodosRepository?) : RunTodos {
    override suspend fun refusal(orgId: Uuid, sessionId: String): String? {
        val list = todos?.find(orgId, sessionId)?.takeIf { it.status == TodoListStatus.ACTIVE }
        val open = list?.items.orEmpty().filter { !it.status.closed }
        if (open.isEmpty()) return null
        val shown = open.take(SHOWN).joinToString { it.id } + if (open.size > SHOWN) ", …" else ""
        val items = if (open.size == 1) "item" else "items"
        return "This step's todo still has ${open.size} open $items ($shown). Finish them, or drop what no longer " +
            "makes sense with todo_drop, then deliver."
    }

    private companion object {
        /** How many open items the refusal names. */
        const val SHOWN = 5
    }
}
