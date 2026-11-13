// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.todos.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration

/**
 * The schema of the todos module, each table confined by row-level security to the organization of the transaction
 * (S2-01):
 * - `session_todos`, one row per session (keyed on its first id), also the lock every change of its list takes;
 * - `session_todo_items`, its items in order (`position`), the condition, dependencies, proof, notes and links as
 *   JSON: they are bounded and always read with the item. Nothing deletes an item;
 * - `member_todo_settings`, the per-member Stop hook switch: no row is "on".
 */
val TodosMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        version = 1,
        name = "create_session_todos",
        sql = """
            CREATE TABLE session_todos (
                org_id UUID NOT NULL,
                session_id TEXT NOT NULL,
                id UUID NOT NULL UNIQUE,
                reference TEXT NOT NULL,
                refs TEXT NOT NULL,
                status TEXT NOT NULL,
                stall INTEGER NOT NULL,
                last_stop TEXT,
                hook_events TEXT NOT NULL,
                last_hook_at TIMESTAMPTZ,
                created_at TIMESTAMPTZ NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL,
                PRIMARY KEY (org_id, session_id)
            );
            CREATE TABLE session_todo_items (
                org_id UUID NOT NULL,
                session_id TEXT NOT NULL,
                id TEXT NOT NULL,
                position INTEGER NOT NULL,
                name TEXT NOT NULL,
                description TEXT NOT NULL,
                done_when TEXT NOT NULL,
                depends_on TEXT NOT NULL,
                origin TEXT NOT NULL,
                origin_reason TEXT,
                status TEXT NOT NULL,
                proof TEXT,
                notes TEXT NOT NULL,
                drop_reason TEXT,
                drop_wait_id TEXT,
                links TEXT NOT NULL,
                PRIMARY KEY (org_id, session_id, id),
                FOREIGN KEY (org_id, session_id) REFERENCES session_todos (org_id, session_id) ON DELETE CASCADE
            );
            CREATE INDEX session_todo_items_position ON session_todo_items (org_id, session_id, position);
            CREATE TABLE member_todo_settings (
                org_id UUID NOT NULL,
                member_id TEXT NOT NULL,
                stop_hook_enabled BOOLEAN NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL,
                PRIMARY KEY (org_id, member_id)
            );
            CREATE INDEX member_todo_settings_off ON member_todo_settings (org_id, member_id)
                WHERE NOT stop_hook_enabled;
            $TENANT_ROLE_SQL
            ${tenantTableSql("session_todos")}
            ${tenantTableSql("session_todo_items")}
            ${tenantTableSql("member_todo_settings")}
        """.trimIndent(),
    ),
)
