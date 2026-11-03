// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.instance.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

/** The schema of the instance module; its tables start with `instance_`. */
val InstanceMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        version = 1,
        name = "create_instance",
        sql = """
            CREATE TABLE instance_info (
                singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
                id UUID NOT NULL,
                created_at TIMESTAMPTZ NOT NULL
            );
            CREATE TABLE instance_signing_keys (
                key_id TEXT PRIMARY KEY,
                algorithm TEXT NOT NULL,
                public_key_pem TEXT NOT NULL,
                first_seen_at TIMESTAMPTZ NOT NULL
            );
        """.trimIndent(),
    ),
    // A new key handed over by the one it replaces (docs/receipt-key-rotation.md): the previous key's id and its
    // signature, served with the new key by `GET /receipts/keys`.
    SchemaMigration(
        version = 2,
        name = "signing_key_rotation",
        sql = """
            ALTER TABLE instance_signing_keys ADD COLUMN rotation_key_id TEXT;
            ALTER TABLE instance_signing_keys ADD COLUMN rotation_signature TEXT
        """.trimIndent(),
    ),
)
