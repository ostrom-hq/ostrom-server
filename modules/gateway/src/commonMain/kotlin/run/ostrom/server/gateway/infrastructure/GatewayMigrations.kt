// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gateway.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.secrets.infrastructure.TENANT_ROLE_SQL
import run.ostrom.server.secrets.infrastructure.tenantTableSql

/**
 * The schema of the gateway module; its tables start with `gateway_`. Version 2 confines both tables to their org
 * (row-level security, SECURITY-AUDIT S2-01).
 */
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
    SchemaMigration(
        version = 2,
        name = "confine_gateway_to_their_org",
        sql = TENANT_ROLE_SQL + "\n" + tenantTableSql("gateway_settings") + "\n" +
            tenantTableSql("gateway_model_policies"),
    ),
)
