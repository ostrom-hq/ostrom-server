// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration

/**
 * The schema of the memory module, shaped as TeamCode's `MemoryTables.kt` (`memories`, `memory_tags`), each table
 * confined by row-level security to the organization of the transaction (S2-01):
 * - `memory_entries`: one row per entry; `owner_id` is the member of a personal entry and empty for the organization's,
 *   so one index (`org_id, scope, owner_id, status, updated_at`) serves both scopes' lists, which never read `content`;
 * - `memory_tags`: an entry's tags, repeating its organization, scope and owner (they never change), so a tag filter is
 *   one covering index range of the caller's own scope.
 */
val MemoryMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        version = 1,
        name = "create_memory",
        sql = """
            CREATE TABLE memory_entries (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                scope TEXT NOT NULL,
                owner_id TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL,
                summary TEXT NOT NULL,
                content TEXT NOT NULL,
                refs TEXT NOT NULL,
                status TEXT NOT NULL,
                origin TEXT NOT NULL,
                author_id TEXT NOT NULL,
                session_id TEXT,
                supersedes_id UUID,
                expires_at TIMESTAMPTZ,
                version INTEGER NOT NULL,
                created_at TIMESTAMPTZ NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL,
                last_read_at TIMESTAMPTZ,
                read_count INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX memory_entries_visible ON memory_entries (org_id, scope, owner_id, status, updated_at);
            CREATE TABLE memory_tags (
                memory_id UUID NOT NULL REFERENCES memory_entries (id) ON DELETE CASCADE,
                tag TEXT NOT NULL,
                org_id UUID NOT NULL,
                scope TEXT NOT NULL,
                owner_id TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (memory_id, tag)
            );
            CREATE INDEX memory_tags_lookup ON memory_tags (org_id, scope, owner_id, tag, memory_id);
            $TENANT_ROLE_SQL
            ${tenantTableSql("memory_entries")}
            ${tenantTableSql("memory_tags")}
        """.trimIndent(),
    ),
)
