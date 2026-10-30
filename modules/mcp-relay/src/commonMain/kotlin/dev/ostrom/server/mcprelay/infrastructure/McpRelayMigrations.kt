package dev.ostrom.server.mcprelay.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

/** The schema of the MCP relay; its tables start with `mcp_relay_`. */
val McpRelayMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        version = 1,
        name = "create_mcp_relay",
        sql = """
            CREATE TABLE mcp_relay_servers (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                name TEXT NOT NULL,
                transport TEXT NOT NULL,
                url TEXT,
                credential_secret_id UUID,
                credential_header TEXT,
                stdio TEXT,
                sharing TEXT NOT NULL,
                version INTEGER NOT NULL,
                created_at TIMESTAMPTZ NOT NULL,
                UNIQUE (org_id, name)
            );
            CREATE TABLE mcp_relay_assignments (
                org_id UUID NOT NULL,
                server_id UUID NOT NULL REFERENCES mcp_relay_servers (id) ON DELETE CASCADE,
                member_id UUID,
                tools TEXT,
                credential_secret_id UUID,
                assigned_at TIMESTAMPTZ NOT NULL
            );
            CREATE UNIQUE INDEX mcp_relay_assignments_member ON mcp_relay_assignments (server_id, member_id) NULLS NOT DISTINCT;
            CREATE INDEX mcp_relay_assignments_org ON mcp_relay_assignments (org_id, member_id);
            CREATE TABLE mcp_relay_policies (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                server_id UUID NOT NULL REFERENCES mcp_relay_servers (id) ON DELETE CASCADE,
                tool TEXT NOT NULL,
                conditions TEXT NOT NULL,
                effect TEXT NOT NULL,
                message TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL
            );
            CREATE INDEX mcp_relay_policies_server ON mcp_relay_policies (org_id, server_id, created_at);
            CREATE TABLE mcp_relay_settings (
                org_id UUID PRIMARY KEY,
                agent_prefix TEXT NOT NULL
            );
            CREATE TABLE mcp_relay_calls (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                member_id UUID NOT NULL,
                server_id UUID NOT NULL,
                server_name TEXT NOT NULL,
                operation TEXT NOT NULL,
                tool TEXT,
                outcome TEXT NOT NULL,
                started_at TIMESTAMPTZ NOT NULL,
                duration_ms BIGINT NOT NULL,
                response_bytes BIGINT NOT NULL,
                policy_id UUID
            );
            CREATE INDEX mcp_relay_calls_org ON mcp_relay_calls (org_id, started_at DESC);
            CREATE INDEX mcp_relay_calls_started ON mcp_relay_calls (started_at)
        """.trimIndent(),
    ),
)
