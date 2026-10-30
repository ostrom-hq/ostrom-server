package dev.ostrom.server.gateway.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

/** The schema of the gateway module; its tables start with `gateway_`. */
val GatewayMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        version = 1,
        name = "create_gateway_settings",
        sql = """
            CREATE TABLE gateway_settings (
                org_id UUID PRIMARY KEY,
                anthropic_key_secret TEXT,
                openai_key_secret TEXT,
                openrouter_key_secret TEXT,
                use_shared_accounts BOOLEAN NOT NULL
            );
            CREATE TABLE gateway_model_policies (
                org_id UUID PRIMARY KEY,
                allowed TEXT NOT NULL,
                default_model TEXT,
                by_member TEXT NOT NULL
            );
        """.trimIndent(),
    ),
)
