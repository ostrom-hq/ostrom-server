// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.todos.fakes

import run.ostrom.server.todos.domain.TodoCheck
import run.ostrom.server.todos.domain.TodoDoneWhen
import run.ostrom.server.todos.domain.TodoItemDefinition
import kotlin.uuid.Uuid

val ORG_A: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000aa")

/** A description long enough for an item. */
const val LONG = "Do this item completely: what, where, why and what is out of scope, " +
    "enough for a new agent to do it alone."

/** A check of a PR of `acme/x`. */
val PR: TodoCheck = TodoCheck.PrMerged("acme/x")

/**
 * An item.
 *
 * @return Its definition.
 */
fun item(id: String, vararg checks: TodoCheck, deps: List<String> = emptyList()): TodoItemDefinition =
    TodoItemDefinition(id, "Item $id", LONG, TodoDoneWhen("done", checks.toList().ifEmpty { listOf(PR) }), deps)
