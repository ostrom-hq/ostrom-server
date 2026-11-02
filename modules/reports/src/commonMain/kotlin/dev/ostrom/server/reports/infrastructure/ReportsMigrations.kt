// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.reports.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

/**
 * The reports own no table: they read the sessions, gates and usage tables in one transaction scoped to the
 * organization. They own the indexes their keyset reads need on those tables, created here so that the source
 * modules carry nothing for a reader they do not know (the module comes after them in `serverModules()`).
 */
val ReportsMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        1,
        "activity_indexes",
        """
        CREATE INDEX reports_sessions_ended ON sessions_sessions (org_id, ended_at DESC, id DESC)
            WHERE ended_at IS NOT NULL;
        CREATE INDEX reports_gates_created ON gates_gates (org_id, created_at DESC, id DESC);
        CREATE INDEX reports_gates_decided ON gates_gates (org_id, decided_at DESC, id DESC)
            WHERE decided_at IS NOT NULL
        """.trimIndent(),
    ),
)
