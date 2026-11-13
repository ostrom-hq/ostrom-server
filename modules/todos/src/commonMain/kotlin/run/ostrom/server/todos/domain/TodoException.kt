// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.todos.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/**
 * A refusal of a todo rule, with the stable key clients switch on (`todo_exists`, `not_done`…: TeamCode's codes).
 *
 * @param kind The kind of failure, which decides the status.
 * @param type The stable key.
 * @param message What to do.
 */
class TodoException(kind: ErrorKind, type: String, message: String) : OstromException(kind, message, type)

/**
 * A malformed todo, item, check, link or proof: 400 with [type].
 *
 * @param type The stable key.
 * @param message What to fix.
 * @return The refusal.
 */
fun invalidTodo(type: String, message: String): TodoException = TodoException(ErrorKind.INVALID_REQUEST, type, message)

/**
 * The list's or item's state does not allow the change: 409 with [type].
 *
 * @param type The stable key.
 * @param message Why.
 * @return The refusal.
 */
fun todoConflict(type: String, message: String): TodoException = TodoException(ErrorKind.CONFLICT, type, message)

/**
 * No item [id] in the list.
 *
 * @param id The item.
 * @return The refusal.
 */
fun unknownItem(id: String): TodoException =
    TodoException(ErrorKind.NOT_FOUND, "unknown_item", "No item '${id.take(MAX_ECHO)}' in this todo.")

/** How much of a caller's value a message repeats. */
internal const val MAX_ECHO: Int = 80

/**
 * Throws what [error] makes when [refused]: one rule, one line.
 *
 * @param refused Whether the rule refuses.
 * @param error The refusal.
 * @throws TodoException When [refused].
 */
internal fun refuse(refused: Boolean, error: () -> TodoException) {
    if (refused) throw error()
}
