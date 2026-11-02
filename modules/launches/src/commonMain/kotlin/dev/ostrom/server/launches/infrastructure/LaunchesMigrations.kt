// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

/**
 * The launches schema. Rows are isolated by organization in the database itself: row level security is forced (the
 * owner included) and every query runs in a transaction that first sets `ostrom.org_id` (`withOrganization`). An agent
 * token names its organization, so even resolving it reads within that organization only.
 */
val LaunchesMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        1,
        "create_launches",
        """
        CREATE TABLE launches_launches (
            org_id UUID NOT NULL,
            id TEXT NOT NULL,
            user_id UUID NOT NULL,
            dev TEXT NOT NULL,
            repo TEXT,
            agent TEXT NOT NULL,
            model TEXT,
            created_at TIMESTAMPTZ NOT NULL,
            expires_at TIMESTAMPTZ NOT NULL,
            PRIMARY KEY (org_id, id)
        );
        ALTER TABLE launches_launches ENABLE ROW LEVEL SECURITY;
        ALTER TABLE launches_launches FORCE ROW LEVEL SECURITY;
        CREATE POLICY launches_launches_org ON launches_launches
            USING (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID))
            WITH CHECK (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID))
        """.trimIndent(),
    ),
    // A launch opened by the runner of a cloud session names it: the runner reports that launch's checks.
    SchemaMigration(
        2,
        "launch_cloud_session",
        "ALTER TABLE launches_launches ADD COLUMN cloud_session_id UUID",
    ),
    // The launch limit counts a person's, then the organization's, launches of the last window (AUDIT-SECU-2 B9).
    SchemaMigration(
        3,
        "launch_limit_indexes",
        """
        CREATE INDEX launches_launches_person ON launches_launches (org_id, user_id, created_at);
        CREATE INDEX launches_launches_created ON launches_launches (org_id, created_at)
        """.trimIndent(),
    ),
)
