// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration

private const val ORG = "CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID)"

/**
 * The notifications schema: the members' phones, one row per push token in an organization, under forced row level
 * security (docs/tenancy.md). Version 2 keeps who registered each phone and with which credential (`user_id`,
 * `credential_id`, `browser`): a phone is notified while that credential works, and forgotten when the person signs
 * out with it. A phone of version 1 has none and is notified again once the app registers it anew. Version 3 keeps an
 * `fcm` phone's P-256 public key (`public_key`), which every push to it is encrypted for. Version 4 holds the
 * members' switches of the notification router (TeamCode's `member_notification_settings`): one row per member,
 * channel and kind a member changed, no row meaning on. Version 5 keeps the instance's identity at each push relay
 * (TeamCode's `push_relay_instances`): the instance's, not an organization's, so without `org_id` (docs/tenancy.md),
 * its Ed25519 private key sealed with the secrets key.
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
    SchemaMigration(
        2,
        "push_devices_credential",
        """
        ALTER TABLE notifications_push_devices ADD COLUMN user_id UUID;
        ALTER TABLE notifications_push_devices ADD COLUMN credential_id UUID;
        ALTER TABLE notifications_push_devices ADD COLUMN browser BOOLEAN NOT NULL DEFAULT FALSE;
        CREATE INDEX notifications_push_devices_credential
            ON notifications_push_devices (org_id, user_id, credential_id)
        """.trimIndent(),
    ),
    SchemaMigration(
        3,
        "push_devices_public_key",
        "ALTER TABLE notifications_push_devices ADD COLUMN public_key TEXT",
    ),
    SchemaMigration(
        4,
        "create_member_settings",
        """
        CREATE TABLE notifications_member_settings (
            org_id UUID NOT NULL,
            member_id UUID NOT NULL,
            channel TEXT NOT NULL,
            kind TEXT NOT NULL,
            enabled BOOLEAN NOT NULL,
            PRIMARY KEY (org_id, member_id, channel, kind)
        );
        ALTER TABLE notifications_member_settings ENABLE ROW LEVEL SECURITY;
        ALTER TABLE notifications_member_settings FORCE ROW LEVEL SECURITY;
        CREATE POLICY notifications_member_settings_org ON notifications_member_settings
            USING (org_id = $ORG) WITH CHECK (org_id = $ORG)
        """.trimIndent(),
    ),
    SchemaMigration(
        5,
        "create_push_relay_instances",
        """
        CREATE TABLE notifications_push_relay_instances (
            relay_url TEXT PRIMARY KEY,
            public_key TEXT NOT NULL,
            private_key TEXT NOT NULL,
            instance_id TEXT,
            created_at TIMESTAMPTZ NOT NULL
        )
        """.trimIndent(),
    ),
)
