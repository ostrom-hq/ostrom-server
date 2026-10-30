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
)
