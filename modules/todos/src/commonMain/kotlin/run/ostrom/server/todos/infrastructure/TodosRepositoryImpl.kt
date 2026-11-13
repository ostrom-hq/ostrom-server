// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.todos.infrastructure

import run.ostrom.server.todos.domain.TodoItem
import run.ostrom.server.todos.domain.TodoItemStatus
import run.ostrom.server.todos.domain.TodoList
import run.ostrom.server.todos.domain.TodoListStatus
import run.ostrom.server.todos.domain.TodoOrigin
import run.ostrom.server.todos.domain.TodoRules
import run.ostrom.server.todos.domain.TodosRepository
import run.ostrom.server.todos.domain.todoNotFound
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * [TodosRepository] on PostgreSQL (tables of [TodosMigrations]), every query confined to the list's organization
 * ([inTenant], S2-01). A list costs two reads (its row, then its items in order); a change locks the row
 * (`FOR UPDATE`), reads the items, and writes only the list row and the items that changed, in one transaction, so
 * two replicas never both create a list nor both finish an item.
 *
 * @param driver The database.
 * @param clock Stamps the Stop hook settings.
 */
@Suppress("TooManyFunctions") // One implementation of the module's one store.
internal class TodosRepositoryImpl(private val driver: Driver, private val clock: Clock) : TodosRepository {
    override suspend fun find(orgId: Uuid, sessionId: String): TodoList? =
        driver.inTenant(orgId) { read(orgId, sessionId, lock = false) }

    override suspend fun create(list: TodoList): Boolean = driver.inTenant(list.orgId) {
        val inserted = execute(
            Statement.create(
                """
                INSERT INTO session_todos ($LIST_COLUMNS) VALUES (:org_id, :session_id, :id, :reference, :refs,
                :status, :stall, :last_stop, :hook_events, :last_hook_at, :created_at, :updated_at)
                ON CONFLICT (org_id, session_id) DO NOTHING
                """,
            ).bindList(list).bind("id", list.id).bind("reference", list.reference)
                .bind("refs", TodoJson.refs(list.refs)).bind("created_at", list.createdAt),
        ).getOrThrow()
        if (inserted > 0) list.items.forEachIndexed { index, item -> insertItem(list, index, item) }
        inserted > 0
    }

    override suspend fun <T> mutate(orgId: Uuid, sessionId: String, change: (TodoList) -> Pair<TodoList, T>): T =
        driver.inTenant(orgId) {
            val before = read(orgId, sessionId, lock = true) ?: throw todoNotFound()
            val (after, value) = change(before)
            write(before, after)
            value
        }

    override suspend fun reset(orgId: Uuid, sessionId: String, check: (TodoList) -> Unit): TodoList =
        driver.inTenant(orgId) {
            val list = read(orgId, sessionId, lock = true) ?: throw todoNotFound()
            check(list)
            // The items go with their list (ON DELETE CASCADE); nothing else refers to them.
            execute(
                Statement.create("DELETE FROM session_todos WHERE org_id = :org_id AND session_id = :session_id")
                    .bind("org_id", orgId).bind("session_id", sessionId),
            ).getOrThrow()
            list
        }

    override suspend fun stopHookEnabled(orgId: Uuid, memberId: String): Boolean = driver.fetchInOrg(
        orgId,
        Statement.create(
            """
            SELECT 1 FROM member_todo_settings WHERE org_id = :org_id AND member_id = :member AND NOT
            stop_hook_enabled
            """,
        ).bind("org_id", orgId).bind("member", memberId),
    ).getOrThrow().firstOrNull() == null

    override suspend fun stopHookDisabled(orgId: Uuid): Set<String> = driver.fetchInOrg(
        orgId,
        Statement.create(
            """
            SELECT member_id FROM member_todo_settings WHERE org_id = :org_id AND NOT stop_hook_enabled
            ORDER BY member_id LIMIT $MAX_SETTINGS
            """,
        ).bind("org_id", orgId),
    ).getOrThrow().map { it.get("member_id").asString() }.toSet()

    override suspend fun setStopHookEnabled(orgId: Uuid, memberId: String, enabled: Boolean) {
        driver.inTenant(orgId) {
            execute(
                Statement.create(
                    """
                    INSERT INTO member_todo_settings (org_id, member_id, stop_hook_enabled, updated_at) VALUES
                    (:org_id, :member, CAST(:enabled AS BOOLEAN), :now) ON CONFLICT (org_id, member_id) DO UPDATE SET
                    stop_hook_enabled = EXCLUDED.stop_hook_enabled, updated_at = EXCLUDED.updated_at
                    """,
                ).bind("org_id", orgId).bind("member", memberId).bind("enabled", enabled).bind("now", clock.now()),
            ).getOrThrow()
        }
    }

    private suspend fun QueryExecutor.read(orgId: Uuid, sessionId: String, lock: Boolean): TodoList? {
        val row = fetchAll(
            Statement.create(
                "SELECT $LIST_COLUMNS FROM session_todos WHERE org_id = :org_id AND session_id = :session_id" +
                    if (lock) " FOR UPDATE" else "",
            ).bind("org_id", orgId).bind("session_id", sessionId),
        ).getOrThrow().firstOrNull() ?: return null
        val items = fetchAll(
            Statement.create(
                """
                SELECT $ITEM_COLUMNS FROM session_todo_items WHERE org_id = :org_id AND session_id = :session_id
                ORDER BY position LIMIT ${TodoRules.MAX_ITEMS}
                """,
            ).bind("org_id", orgId).bind("session_id", sessionId),
        ).getOrThrow().map { it.toItem() }
        return row.toList(items)
    }

    /** Writes the list row when it changed, the new items, and the items whose content or place moved. */
    private suspend fun QueryExecutor.write(before: TodoList, after: TodoList) {
        if (before.copy(items = emptyList()) != after.copy(items = emptyList())) {
            execute(
                Statement.create(
                    """
                    UPDATE session_todos SET status = :status, stall = :stall, last_stop = :last_stop,
                    hook_events = :hook_events, last_hook_at = :last_hook_at, updated_at = :updated_at
                    WHERE org_id = :org_id AND session_id = :session_id
                    """,
                ).bindList(after),
            ).getOrThrow()
        }
        val old = before.items.withIndex().associate { it.value.id to it }
        after.items.forEachIndexed { index, item ->
            val previous = old[item.id]
            when {
                previous == null -> insertItem(after, index, item)
                previous.value != item || previous.index != index -> updateItem(after, index, item)
            }
        }
    }

    private suspend fun QueryExecutor.insertItem(list: TodoList, index: Int, item: TodoItem) {
        execute(
            Statement.create(
                """
                INSERT INTO session_todo_items ($ITEM_COLUMNS, org_id, session_id) VALUES (:id, :position, :name,
                :description, :done_when, :depends_on, :origin, :origin_reason, :status, :proof, :notes,
                :drop_reason, :drop_wait_id, :links, :org_id, :session_id)
                """,
            ).bindItem(list, index, item),
        ).getOrThrow()
    }

    private suspend fun QueryExecutor.updateItem(list: TodoList, index: Int, item: TodoItem) {
        execute(
            Statement.create(
                """
                UPDATE session_todo_items SET position = :position, name = :name, description = :description,
                done_when = :done_when, depends_on = :depends_on, origin = :origin, origin_reason = :origin_reason,
                status = :status, proof = :proof, notes = :notes, drop_reason = :drop_reason,
                drop_wait_id = :drop_wait_id, links = :links
                WHERE org_id = :org_id AND session_id = :session_id AND id = :id
                """,
            ).bindItem(list, index, item),
        ).getOrThrow()
    }

    private fun Statement.bindList(list: TodoList): Statement = bind("org_id", list.orgId)
        .bind("session_id", list.sessionId)
        .bind("status", list.status.wire)
        .bind("stall", list.stall)
        .bind("last_stop", list.lastStop?.let(TodoJson::progress))
        .bind("hook_events", TodoJson.events(list.hookEvents))
        .bind("last_hook_at", list.lastHookAt)
        .bind("updated_at", list.updatedAt)

    private fun Statement.bindItem(list: TodoList, index: Int, item: TodoItem): Statement = bind("org_id", list.orgId)
        .bind("session_id", list.sessionId)
        .bind("id", item.id)
        .bind("position", index)
        .bind("name", item.name)
        .bind("description", item.description)
        .bind("done_when", TodoJson.doneWhen(item.doneWhen))
        .bind("depends_on", TodoJson.strings(item.dependsOn))
        .bind("origin", item.origin.wire)
        .bind("origin_reason", item.originReason)
        .bind("status", item.status.wire)
        .bind("proof", item.proof?.let(TodoJson::proof))
        .bind("notes", TodoJson.notes(item.notes))
        .bind("drop_reason", item.dropReason)
        .bind("drop_wait_id", item.dropWaitId)
        .bind("links", TodoJson.links(item.links))

    private fun ResultSet.Row.toList(items: List<TodoItem>) = TodoList(
        id = get("id").asUuid(),
        orgId = get("org_id").asUuid(),
        sessionId = get("session_id").asString(),
        reference = get("reference").asString(),
        refs = TodoJson.refs(get("refs").asString()),
        status = TodoListStatus.entries.first { it.wire == get("status").asString() },
        stall = get("stall").asInt(),
        lastStop = get("last_stop").asStringOrNull()?.let(TodoJson::progress),
        items = items,
        createdAt = get("created_at").asInstant(),
        updatedAt = get("updated_at").asInstant(),
        hookEvents = TodoJson.events(get("hook_events").asString()),
        lastHookAt = get("last_hook_at").asInstantOrNull(),
    )

    private fun ResultSet.Row.toItem() = TodoItem(
        id = get("id").asString(),
        name = get("name").asString(),
        description = get("description").asString(),
        doneWhen = TodoJson.doneWhen(get("done_when").asString()),
        dependsOn = TodoJson.strings(get("depends_on").asString()),
        origin = TodoOrigin.entries.first { it.wire == get("origin").asString() },
        originReason = get("origin_reason").asStringOrNull(),
        status = TodoItemStatus.entries.first { it.wire == get("status").asString() },
        proof = get("proof").asStringOrNull()?.let(TodoJson::proof),
        notes = TodoJson.notes(get("notes").asString()),
        dropReason = get("drop_reason").asStringOrNull(),
        dropWaitId = get("drop_wait_id").asStringOrNull(),
        links = TodoJson.links(get("links").asString()),
    )

    private companion object {
        /** Bounds the read of a team's switches: one row per member at most. */
        const val MAX_SETTINGS = 10_000

        const val LIST_COLUMNS = """
            org_id, session_id, id, reference, refs, status, stall, last_stop, hook_events, last_hook_at, created_at,
            updated_at
        """
        const val ITEM_COLUMNS = """
            id, position, name, description, done_when, depends_on, origin, origin_reason, status, proof, notes,
            drop_reason, drop_wait_id, links
        """
    }
}
