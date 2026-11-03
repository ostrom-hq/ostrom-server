// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

private const val ORG = "CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID)"

/**
 * The notifications schema: the members' phones, one row per push token in an organization, under forced row level
 * security (docs/tenancy.md).
 */
val NotificationsMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        1,
        "create_push_devices",
        """
        CREATE TABLE notifications_push_devices (
            org_id UUID NOT NULL,
            id UUID NOT NULL,
            member_id UUID NOT NULL,
            push_token TEXT NOT NULL,
            provider TEXT NOT NULL,
            platform TEXT NOT NULL,
            locale TEXT,
            created_at TIMESTAMPTZ NOT NULL,
            seen_at TIMESTAMPTZ NOT NULL,
            PRIMARY KEY (org_id, id),
            UNIQUE (org_id, push_token)
        );
        CREATE INDEX notifications_push_devices_member ON notifications_push_devices (org_id, member_id, seen_at);
        ALTER TABLE notifications_push_devices ENABLE ROW LEVEL SECURITY;
        ALTER TABLE notifications_push_devices FORCE ROW LEVEL SECURITY;
        CREATE POLICY notifications_push_devices_org ON notifications_push_devices
            USING (org_id = $ORG) WITH CHECK (org_id = $ORG)
        """.trimIndent(),
    ),
)
