// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.todos.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.todos.domain.TodoItem
import run.ostrom.server.todos.domain.TodoItemStatus
import run.ostrom.server.todos.domain.TodoList
import run.ostrom.server.todos.domain.TodoListStatus
import run.ostrom.server.todos.domain.TodoOrigin
import run.ostrom.server.todos.domain.TodoRules
import run.ostrom.server.todos.domain.TodosRepository
import run.ostrom.server.todos.domain.todoNotFound
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * [TodosRepository] on PostgreSQL through Exposed ([SessionTodos], [SessionTodoItems], [MemberTodoSettings], schema of
 * [TodosMigrations]). Every query names its organization (`org_id = …`) and runs in [JdbcDatabase.inOrg], whose
 * row-level security is the second wall (S2-01). A list costs two reads (its row, then its items in order); a change
 * locks the row (`FOR UPDATE`), reads the items, and writes only the list row and the items that changed, in one
 * transaction, so two replicas never both create a list nor both finish an item.
 *
 * @param database The JDBC pool.
 * @param clock Stamps the Stop hook settings.
 */
@Suppress("TooManyFunctions") // One implementation of the module's one store.
internal class TodosRepositoryImpl(private val database: JdbcDatabase, private val clock: Clock) : TodosRepository {
    override suspend fun find(orgId: Uuid, sessionId: String): TodoList? =
        database.inOrg(orgId) { read(orgId, sessionId, lock = false) }

    override suspend fun create(list: TodoList): Boolean = database.inOrg(list.orgId) {
        // ON CONFLICT DO NOTHING on the primary key: of two replicas, the second inserts nothing.
        val inserted = SessionTodos.insertIgnore {
            it[orgId] = list.orgId
            it[sessionId] = list.sessionId
            it[id] = list.id
            it[reference] = list.reference
            it[refs] = TodoJson.refs(list.refs)
            it[createdAt] = list.createdAt
            fillList(it, list)
        }.insertedCount > 0
        if (inserted) list.items.forEachIndexed { index, item -> insertItem(list, index, item) }
        inserted
    }

    override suspend fun <T> mutate(orgId: Uuid, sessionId: String, change: (TodoList) -> Pair<TodoList, T>): T =
        database.inOrg(orgId) {
            val before = read(orgId, sessionId, lock = true) ?: throw todoNotFound()
            val (after, value) = change(before)
            write(before, after)
            value
        }

    override suspend fun reset(orgId: Uuid, sessionId: String, check: (TodoList) -> Unit): TodoList =
        database.inOrg(orgId) {
            val list = read(orgId, sessionId, lock = true) ?: throw todoNotFound()
            check(list)
            // The items go with their list (ON DELETE CASCADE); nothing else refers to them.
            SessionTodos.deleteWhere { (SessionTodos.orgId eq orgId) and (SessionTodos.sessionId eq sessionId) }
            list
        }

    override suspend fun stopHookEnabled(orgId: Uuid, memberId: String): Boolean = database.inOrg(orgId) {
        MemberTodoSettings.select(MemberTodoSettings.memberId)
            .where {
                (MemberTodoSettings.orgId eq orgId) and (MemberTodoSettings.memberId eq memberId) and
                    (MemberTodoSettings.stopHookEnabled eq false)
            }
            .limit(1)
            .empty()
    }

    override suspend fun stopHookDisabled(orgId: Uuid): Set<String> = database.inOrg(orgId) {
        MemberTodoSettings.select(MemberTodoSettings.memberId)
            .where { (MemberTodoSettings.orgId eq orgId) and (MemberTodoSettings.stopHookEnabled eq false) }
            .orderBy(MemberTodoSettings.memberId)
            .limit(MAX_SETTINGS)
            .map { it[MemberTodoSettings.memberId] }
            .toSet()
    }

    override suspend fun setStopHookEnabled(orgId: Uuid, memberId: String, enabled: Boolean) {
        val now = clock.now()
        database.inOrg(orgId) {
            MemberTodoSettings.upsert {
                it[MemberTodoSettings.orgId] = orgId
                it[MemberTodoSettings.memberId] = memberId
                it[stopHookEnabled] = enabled
                it[updatedAt] = now
            }
        }
    }

    private fun JdbcTransaction.read(orgId: Uuid, sessionId: String, lock: Boolean): TodoList? {
        val query = SessionTodos.select(SessionTodos.columns)
            .where { (SessionTodos.orgId eq orgId) and (SessionTodos.sessionId eq sessionId) }
        val row = (if (lock) query.forUpdate() else query).singleOrNull() ?: return null
        val items = SessionTodoItems.select(SessionTodoItems.columns)
            .where { (SessionTodoItems.orgId eq orgId) and (SessionTodoItems.sessionId eq sessionId) }
            .orderBy(SessionTodoItems.position)
            .limit(TodoRules.MAX_ITEMS)
            .map { it.toItem() }
        return row.toList(items)
    }

    /** Writes the list row when it changed, the new items, and the items whose content or place moved. */
    private fun JdbcTransaction.write(before: TodoList, after: TodoList) {
        if (before.copy(items = emptyList()) != after.copy(items = emptyList())) {
            val list = (SessionTodos.orgId eq after.orgId) and (SessionTodos.sessionId eq after.sessionId)
            SessionTodos.update({ list }) { fillList(it, after) }
        }
        val old = before.items.withIndex().associate { it.value.id to it }
        after.items.forEachIndexed { index, item ->
            val previous = old[item.id]
            when {
                previous == null -> insertItem(after, index, item)
                previous.value != item || previous.index != index -> SessionTodoItems.update({
                    (SessionTodoItems.orgId eq after.orgId) and (SessionTodoItems.sessionId eq after.sessionId) and
                        (SessionTodoItems.id eq item.id)
                }) { fillItem(it, index, item) }
            }
        }
    }

    private fun JdbcTransaction.insertItem(list: TodoList, index: Int, item: TodoItem) {
        SessionTodoItems.insert {
            it[orgId] = list.orgId
            it[sessionId] = list.sessionId
            it[id] = item.id
            fillItem(it, index, item)
        }
    }

    /** The columns of a list that a change may write. */
    private fun fillList(row: UpdateBuilder<*>, list: TodoList) {
        row[SessionTodos.status] = list.status.wire
        row[SessionTodos.stall] = list.stall
        row[SessionTodos.lastStop] = list.lastStop?.let(TodoJson::progress)
        row[SessionTodos.hookEvents] = TodoJson.events(list.hookEvents)
        row[SessionTodos.lastHookAt] = list.lastHookAt
        row[SessionTodos.updatedAt] = list.updatedAt
    }

    /** The columns of an item that a change may write. */
    private fun fillItem(row: UpdateBuilder<*>, index: Int, item: TodoItem) {
        row[SessionTodoItems.position] = index
        row[SessionTodoItems.name] = item.name
        row[SessionTodoItems.description] = item.description
        row[SessionTodoItems.doneWhen] = TodoJson.doneWhen(item.doneWhen)
        row[SessionTodoItems.dependsOn] = TodoJson.strings(item.dependsOn)
        row[SessionTodoItems.origin] = item.origin.wire
        row[SessionTodoItems.originReason] = item.originReason
        row[SessionTodoItems.status] = item.status.wire
        row[SessionTodoItems.proof] = item.proof?.let(TodoJson::proof)
        row[SessionTodoItems.notes] = TodoJson.notes(item.notes)
        row[SessionTodoItems.dropReason] = item.dropReason
        row[SessionTodoItems.dropWaitId] = item.dropWaitId
        row[SessionTodoItems.links] = TodoJson.links(item.links)
    }

    private fun ResultRow.toList(items: List<TodoItem>) = TodoList(
        id = this[SessionTodos.id],
        orgId = this[SessionTodos.orgId],
        sessionId = this[SessionTodos.sessionId],
        reference = this[SessionTodos.reference],
        refs = TodoJson.refs(this[SessionTodos.refs]),
        status = TodoListStatus.entries.first { it.wire == this[SessionTodos.status] },
        stall = this[SessionTodos.stall],
        lastStop = this[SessionTodos.lastStop]?.let(TodoJson::progress),
        items = items,
        createdAt = this[SessionTodos.createdAt],
        updatedAt = this[SessionTodos.updatedAt],
        hookEvents = TodoJson.events(this[SessionTodos.hookEvents]),
        lastHookAt = this[SessionTodos.lastHookAt],
    )

    private fun ResultRow.toItem() = TodoItem(
        id = this[SessionTodoItems.id],
        name = this[SessionTodoItems.name],
        description = this[SessionTodoItems.description],
        doneWhen = TodoJson.doneWhen(this[SessionTodoItems.doneWhen]),
        dependsOn = TodoJson.strings(this[SessionTodoItems.dependsOn]),
        origin = TodoOrigin.entries.first { it.wire == this[SessionTodoItems.origin] },
        originReason = this[SessionTodoItems.originReason],
        status = TodoItemStatus.entries.first { it.wire == this[SessionTodoItems.status] },
        proof = this[SessionTodoItems.proof]?.let(TodoJson::proof),
        notes = TodoJson.notes(this[SessionTodoItems.notes]),
        dropReason = this[SessionTodoItems.dropReason],
        dropWaitId = this[SessionTodoItems.dropWaitId],
        links = TodoJson.links(this[SessionTodoItems.links]),
    )

    private companion object {
        /** Bounds the read of a team's switches: one row per member at most. */
        const val MAX_SETTINGS = 10_000
    }
}
