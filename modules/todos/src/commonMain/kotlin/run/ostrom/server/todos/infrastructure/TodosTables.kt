// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.todos.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

/**
 * The tables of [TodosMigrations], as Exposed reads and writes them. The migrations create the schema; these objects
 * only name its columns and their types, and never create anything (no `SchemaUtils`).
 */
internal object SessionTodos : Table("session_todos") {
    val orgId = uuid("org_id")
    val sessionId = text("session_id")
    val id = uuid("id")
    val reference = text("reference")

    /** JSON ([TodoJson.refs]). */
    val refs = text("refs")
    val status = text("status")
    val stall = integer("stall")

    /** JSON ([TodoJson.progress]), null before the first blocked stop. */
    val lastStop = text("last_stop").nullable()

    /** JSON ([TodoJson.events]). */
    val hookEvents = text("hook_events")
    val lastHookAt = instant("last_hook_at").nullable()
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(orgId, sessionId)
}

/** The items of a list, in order ([position]); their JSON columns are written and read by [TodoJson]. */
internal object SessionTodoItems : Table("session_todo_items") {
    val orgId = uuid("org_id")
    val sessionId = text("session_id")
    val id = text("id")
    val position = integer("position")
    val name = text("name")
    val description = text("description")
    val doneWhen = text("done_when")
    val dependsOn = text("depends_on")
    val origin = text("origin")
    val originReason = text("origin_reason").nullable()
    val status = text("status")
    val proof = text("proof").nullable()
    val notes = text("notes")
    val dropReason = text("drop_reason").nullable()
    val dropWaitId = text("drop_wait_id").nullable()
    val links = text("links")

    override val primaryKey = PrimaryKey(orgId, sessionId, id)
}

/** The per-member Stop hook switch: no row is "on". */
internal object MemberTodoSettings : Table("member_todo_settings") {
    val orgId = uuid("org_id")
    val memberId = text("member_id")
    val stopHookEnabled = bool("stop_hook_enabled")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(orgId, memberId)
}
