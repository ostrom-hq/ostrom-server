// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.mcprelay.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.secrets.infrastructure.TENANT_ROLE_SQL
import run.ostrom.server.secrets.infrastructure.tenantTableSql

/** The setting that opened the retention policies of version 2, which version 4 drops: kept for version 2's SQL. */
private const val RETENTION_SETTING_V2: String = "ostrom.mcp_relay_retention"

/** The retention purge, the one operation across organizations: a function owned by `ostrom_router`. */
private val ROUTER_FUNCTIONS = listOf("mcp_relay_purge(TIMESTAMPTZ)")

/**
 * Version 4: the retention policies, which any transaction could open by setting the retention setting (SELECT and
 * DELETE on every organization's calls, RLS audit R4), give way to a narrow `SECURITY DEFINER` function owned by
 * `ostrom_router` (`NOLOGIN`, never a member of the serving role), with `pg_temp` last in its `search_path` and
 * executable by the serving role only: it deletes the old calls, the query it replaces, unchanged.
 */
private val RETENTION_THROUGH_THE_ROUTER_SQL: String =
    """
    DO $$ BEGIN
        CREATE ROLE ostrom_router NOLOGIN NOSUPERUSER NOBYPASSRLS;
    EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL;
    END $$;
    DO $$ BEGIN
        GRANT ostrom_router TO CURRENT_USER;
    EXCEPTION WHEN unique_violation THEN NULL;
    END $$;
    GRANT USAGE ON SCHEMA public TO ostrom_router;
    DROP POLICY mcp_relay_calls_retention_read ON mcp_relay_calls;
    DROP POLICY mcp_relay_calls_retention ON mcp_relay_calls;
    CREATE POLICY mcp_relay_calls_router_read ON mcp_relay_calls FOR SELECT TO ostrom_router USING (true);
    CREATE POLICY mcp_relay_calls_router_purge ON mcp_relay_calls FOR DELETE TO ostrom_router USING (true);
    GRANT SELECT, DELETE ON mcp_relay_calls TO ostrom_router;
    CREATE FUNCTION mcp_relay_purge(p_before TIMESTAMPTZ) RETURNS BIGINT
        LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        WITH gone AS (DELETE FROM mcp_relay_calls WHERE started_at < p_before RETURNING 1) SELECT COUNT(*) FROM gone;
    $$;
    ${ROUTER_FUNCTIONS.joinToString("\n") { "REVOKE EXECUTE ON FUNCTION $it FROM PUBLIC;" }}
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
            ${ROUTER_FUNCTIONS.joinToString("\n") { "GRANT EXECUTE ON FUNCTION $it TO ostrom_app;" }}
        END IF;
    END $$;
    GRANT CREATE ON SCHEMA public TO ostrom_router;
    ${ROUTER_FUNCTIONS.joinToString("\n") { "ALTER FUNCTION $it OWNER TO ostrom_router;" }}
    REVOKE CREATE ON SCHEMA public FROM ostrom_router;
    """
        .trimIndent()

/** The schema of the MCP relay; its tables start with `mcp_relay_`, each confined to its org by row-level security. */
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
            CREATE INDEX mcp_relay_calls_org ON mcp_relay_calls (org_id, started_at DESC, id DESC);
            CREATE INDEX mcp_relay_calls_started ON mcp_relay_calls (started_at)
        """.trimIndent(),
    ),
    SchemaMigration(
        version = 2,
        name = "confine_mcp_relay_to_their_org",
        sql = listOf(
            TENANT_ROLE_SQL,
            tenantTableSql("mcp_relay_servers"),
            tenantTableSql("mcp_relay_assignments"),
            tenantTableSql("mcp_relay_policies"),
            tenantTableSql("mcp_relay_settings"),
            tenantTableSql("mcp_relay_calls"),
            // Retention deletes old calls of every org, and nothing else (McpRelayRepositoryImpl.purgeCalls); a DELETE
            // with a WHERE also needs the rows to pass a SELECT policy.
            "CREATE POLICY mcp_relay_calls_retention_read ON mcp_relay_calls FOR SELECT " +
                "USING (current_setting('$RETENTION_SETTING_V2', true) = 'on');",
            "CREATE POLICY mcp_relay_calls_retention ON mcp_relay_calls FOR DELETE " +
                "USING (current_setting('$RETENTION_SETTING_V2', true) = 'on');",
        ).joinToString("\n"),
    ),
    SchemaMigration(
        version = 3,
        name = "mcp_relay_calls_name_the_agent_launch",
        // The launch whose agent token made the call (server#108), null for a member's own credential.
        sql = "ALTER TABLE mcp_relay_calls ADD COLUMN launch_id TEXT",
    ),
    SchemaMigration(version = 4, name = "retention_through_the_router", sql = RETENTION_THROUGH_THE_ROUTER_SQL),
    // RLS audit R9: the router's policies hold only inside its own functions, as identity's
    // do; the owner, a member of `ostrom_router` to hand them over, no longer passes them.
    SchemaMigration(
        version = 5,
        name = "mcp_relay_router_policies_inside_functions_only",
        sql = """
            ALTER POLICY mcp_relay_calls_router_read ON mcp_relay_calls USING (current_user = 'ostrom_router');
            ALTER POLICY mcp_relay_calls_router_purge ON mcp_relay_calls USING (current_user = 'ostrom_router')
        """.trimIndent(),
    ),
    // The relay's limits, shared by every replica instead of each one's memory: the running operations (a slot frees
    // itself after its lease when its replica died) and the operations started each minute, by the database's clock.
    SchemaMigration(
        version = 6,
        name = "mcp_relay_slots_and_minutes",
        sql = """
            CREATE TABLE mcp_relay_slots (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                server_id UUID NOT NULL REFERENCES mcp_relay_servers (id) ON DELETE CASCADE,
                member_id UUID NOT NULL,
                expires_at TIMESTAMPTZ NOT NULL
            );
            CREATE INDEX mcp_relay_slots_server ON mcp_relay_slots (server_id, member_id, expires_at);
            CREATE TABLE mcp_relay_minutes (
                org_id UUID NOT NULL,
                server_id UUID NOT NULL REFERENCES mcp_relay_servers (id) ON DELETE CASCADE,
                minute BIGINT NOT NULL,
                member_id UUID NOT NULL,
                calls INTEGER NOT NULL,
                PRIMARY KEY (server_id, minute, member_id)
            )
        """.trimIndent() + ";\n" + tenantTableSql("mcp_relay_slots") + "\n" + tenantTableSql("mcp_relay_minutes"),
    ),
)
