// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.todos.domain

import kotlin.uuid.Uuid

/** Reading a session's todo. */
interface ReadTodoUseCase {
    /**
     * The session's list, with the owner's answers to drop requests applied.
     *
     * @param sessionId The session named (an agent token's is its own, whatever it names).
     * @return The list, or null when the session has none.
     * @throws TodoSessionNotFoundException When the caller may not read it.
     */
    suspend fun get(caller: TodoCaller, sessionId: String?): TodoList?

    /**
     * The block a session is reminded of after a compaction or on resume ([TodoMessages.context]).
     *
     * @return The block, or null without an active list.
     * @throws TodoSessionNotFoundException When the caller may not read it.
     */
    suspend fun context(caller: TodoCaller, sessionId: String?): String?

    /**
     * The block that follows the message waking a session (an answer, a delay, an event), for the waits' wake path
     * to append: the reference, the list with the answers to drop requests applied, and the next item.
     *
     * @param sessionId The session's root id.
     * @return The block, or null when the session has no active list: the wake message is then unchanged.
     */
    suspend fun wakeBlock(orgId: Uuid, sessionId: String): String?
}

/**
 * [ReadTodoUseCase].
 *
 * @param access Finds the session.
 * @param verifier Applies the drop answers.
 */
class ReadTodoUseCaseImpl(private val access: TodoAccess, private val verifier: TodoVerifier) : ReadTodoUseCase {
    override suspend fun get(caller: TodoCaller, sessionId: String?): TodoList? {
        val target = access.reader(caller, sessionId)
        return verifier.reconcile(target.orgId, target.sessionId)
    }

    override suspend fun context(caller: TodoCaller, sessionId: String?): String? {
        val target = access.reader(caller, sessionId)
        return wakeBlock(target.orgId, target.sessionId)
    }

    override suspend fun wakeBlock(orgId: Uuid, sessionId: String): String? =
        verifier.reconcile(orgId, sessionId)?.takeIf { it.status == TodoListStatus.ACTIVE }?.let(TodoMessages::context)
}
