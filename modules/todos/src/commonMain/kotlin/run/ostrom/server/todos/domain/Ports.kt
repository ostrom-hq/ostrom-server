// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.todos.domain

import kotlin.uuid.Uuid

/**
 * Where session todos are stored. The rules stay in [TodoRules]; the repository only makes each change atomic: the
 * list's row is the lock every change takes.
 */
interface TodosRepository {
    /**
     * Reads a session's list with its items in order.
     *
     * @param orgId The organization.
     * @param sessionId The session's root id.
     * @return The list, or null when the session has none.
     */
    suspend fun find(orgId: Uuid, sessionId: String): TodoList?

    /**
     * Stores a new list, unless the session has one (decided in the database, so two replicas cannot both create).
     *
     * @param list The list.
     * @return False when the session already has a list: nothing stored.
     */
    suspend fun create(list: TodoList): Boolean

    /**
     * Reads the session's list under its row lock, applies [change] (which may throw to refuse), and stores what
     * changed in the same transaction. No item is deleted.
     *
     * @param change The new list and a value for the caller.
     * @return The value.
     * @throws TodoException `todo_not_found` when the session has no list.
     */
    suspend fun <T> mutate(orgId: Uuid, sessionId: String, change: (TodoList) -> Pair<TodoList, T>): T

    /**
     * Reads the session's list under its row lock, lets [check] refuse (it throws), then deletes the list and its
     * items in the same transaction: the one place a list is deleted, for a closed one ([TodoRules.requireClosed]).
     *
     * @param check Refuses the reset.
     * @return The list deleted.
     * @throws TodoException `todo_not_found` when the session has no list.
     */
    suspend fun reset(orgId: Uuid, sessionId: String, check: (TodoList) -> Unit): TodoList

    /**
     * Whether the todo Stop hook is on for a member: true unless an administrator turned it off.
     *
     * @return True when on.
     */
    suspend fun stopHookEnabled(orgId: Uuid, memberId: String): Boolean

    /**
     * The members of the organization whose Stop hook is off.
     *
     * @return Their ids.
     */
    suspend fun stopHookDisabled(orgId: Uuid): Set<String>

    /**
     * Turns a member's Stop hook on or off.
     *
     * @param enabled On or off.
     */
    suspend fun setStopHookEnabled(orgId: Uuid, memberId: String, enabled: Boolean)
}

/**
 * A session as the todo sees it.
 *
 * @property rootId Its first id, which keys its todo: a resumed session keeps its list.
 * @property segments Its segments' ids, in order; the last is the one open now, if any.
 * @property owned Whether the member asked about is the session's person.
 */
data class TodoSession(val rootId: String, val segments: List<String>, val owned: Boolean)

/** The sessions module, read by the todo. */
fun interface TodoSessions {
    /**
     * The session one of whose segments is [sessionId].
     *
     * @param orgId The organization.
     * @param sessionId Any of its segment ids.
     * @param memberId The member whose ownership is asked.
     * @return The session, or null when the organization has none with this id.
     */
    suspend fun find(orgId: Uuid, sessionId: String, memberId: String): TodoSession?
}

/**
 * The session's person, for whom the todo asks the owner's waits.
 *
 * @property orgId The organization.
 * @property memberId The person's member id.
 * @property segment The segment the wait is asked in.
 * @property segments Every segment of the session.
 */
data class TodoOwner(val orgId: Uuid, val memberId: String, val segment: String, val segments: List<String>)

/**
 * What a wait is now.
 *
 * @property status `open`, `resolved` or `cancelled`.
 * @property action The action chosen, once resolved.
 * @property sessionId The segment it was asked in.
 */
data class TodoWaitState(val status: String, val action: String?, val sessionId: String?)

/** The waits ("Waiting for you") the todo asks of the session's owner and reads. */
interface TodoWaits {
    /**
     * Asks the owner to agree to drop an item: actions `yes` (drop it) and `no` (keep it).
     *
     * @return The wait's id.
     */
    suspend fun askDrop(owner: TodoOwner, item: TodoItem, reason: String): String

    /**
     * Asks the owner to unblock a session that stopped three times without progress: `no` (Resume), `yes` (Drop this
     * item, only with [dropItem]) and `stop` (Stop here).
     *
     * @return The wait's id.
     */
    suspend fun askBlocked(owner: TodoOwner, title: String, body: String, dropItem: Boolean): String

    /**
     * Withdraws a wait the todo asked, when what it was for could not be stored.
     *
     * @param waitId The wait.
     */
    suspend fun cancel(owner: TodoOwner, waitId: String)

    /**
     * The state of these waits of the organization.
     *
     * @return By id; an unknown one is absent.
     */
    suspend fun states(orgId: Uuid, waitIds: Collection<String>): Map<String, TodoWaitState>

    /**
     * Whether any wait of these segments is open.
     *
     * @return True when one is.
     */
    suspend fun anyOpen(orgId: Uuid, segments: List<String>): Boolean
}

/** The artifacts, read by the server's own `artifact_published` checks. */
fun interface TodoArtifacts {
    /**
     * The latest version of these artifacts of the organization.
     *
     * @return By id; an unknown or deleted one is absent.
     */
    suspend fun versions(orgId: Uuid, artifactIds: Collection<String>): Map<String, Int>
}
