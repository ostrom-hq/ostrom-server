// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.usage.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.secrets.infrastructure.TENANT_ROLE_SQL
import dev.ostrom.server.secrets.infrastructure.tenantTableSql

/** The setting that opened the retention policies of version 2, which version 4 drops: kept for version 2's SQL. */
private const val RETENTION_SETTING_V2: String = "ostrom.usage_retention"

/** The retention purge, the one operation across organizations: a function owned by `ostrom_router`. */
private val ROUTER_FUNCTIONS = listOf("usage_purge(TIMESTAMPTZ, INTEGER)")

/**
 * Version 4: the retention policies, which any transaction could open by setting the retention setting (SELECT and
 * DELETE on every organization's events, RLS audit R4), give way to a narrow `SECURITY DEFINER` function owned by
 * `ostrom_router` (`NOLOGIN`, never a member of the serving role), with `pg_temp` last in its `search_path` and
 * executable by the serving role only: it deletes one batch of old events, the query it replaces, unchanged.
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
    DROP POLICY usage_events_retention_read ON usage_events;
    DROP POLICY usage_events_retention ON usage_events;
    CREATE POLICY usage_events_router_read ON usage_events FOR SELECT TO ostrom_router USING (true);
    CREATE POLICY usage_events_router_purge ON usage_events FOR DELETE TO ostrom_router USING (true);
    GRANT SELECT, DELETE ON usage_events TO ostrom_router;
    CREATE FUNCTION usage_purge(p_before TIMESTAMPTZ, p_limit INTEGER) RETURNS BIGINT
        LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        WITH gone AS (
            DELETE FROM usage_events WHERE id IN (SELECT e.id FROM usage_events e WHERE e.at < p_before LIMIT p_limit)
            RETURNING 1
        ) SELECT COUNT(*) FROM gone;
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

/**
 * The schema of the usage module; its tables start with `usage_`. The spend indexes include `cost_micros` so that the
 * budget sums are index-only scans; `usage_events_at` serves the retention purge. `usage_holds` keeps the budget held
 * by the model calls in flight.
 */
val UsageMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        version = 1,
        name = "create_usage_events",
        sql = """
            CREATE TABLE usage_events (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                member_id UUID,
                kind TEXT NOT NULL,
                name TEXT NOT NULL,
                provider TEXT,
                account_id UUID,
                session_id TEXT,
                input_tokens BIGINT NOT NULL,
                output_tokens BIGINT NOT NULL,
                cache_read_tokens BIGINT NOT NULL,
                cache_write_tokens BIGINT NOT NULL,
                cost_micros BIGINT NOT NULL,
                duration_ms BIGINT NOT NULL,
                outcome TEXT NOT NULL,
                at TIMESTAMPTZ NOT NULL
            );
            CREATE INDEX usage_events_org_at ON usage_events (org_id, at, id) INCLUDE (cost_micros);
            CREATE INDEX usage_events_member_at ON usage_events (org_id, member_id, at) INCLUDE (cost_micros);
            CREATE INDEX usage_events_account_at ON usage_events (account_id, at) INCLUDE (cost_micros) WHERE account_id IS NOT NULL;
            CREATE INDEX usage_events_at ON usage_events (at)
        """.trimIndent(),
    ),
    SchemaMigration(
        version = 2,
        name = "confine_usage_events_to_their_org",
        sql = listOf(
            TENANT_ROLE_SQL,
            tenantTableSql("usage_events"),
            // Retention deletes old events of every org, and nothing else (UsageRepositoryImpl.purge); a DELETE with a
            // WHERE also needs the rows to pass a SELECT policy.
            "CREATE POLICY usage_events_retention_read ON usage_events FOR SELECT " +
                "USING (current_setting('$RETENTION_SETTING_V2', true) = 'on');",
            "CREATE POLICY usage_events_retention ON usage_events FOR DELETE " +
                "USING (current_setting('$RETENTION_SETTING_V2', true) = 'on');",
        ).joinToString("\n"),
    ),
    SchemaMigration(
        version = 3,
        name = "usage_events_name_the_agent_launch",
        // The launch whose agent token sent the event (server#108), null for a person's own credential.
        sql = "ALTER TABLE usage_events ADD COLUMN launch_id TEXT",
    ),
    SchemaMigration(version = 4, name = "retention_through_the_router", sql = RETENTION_THROUGH_THE_ROUTER_SQL),
    SchemaMigration(
        version = 5,
        name = "create_usage_holds",
        // What the model calls in flight may still cost (UsageRepository.holdWithin): each hold lives until its call is
        // recorded (settle), or until it expires when a replica is lost before.
        sql = listOf(
            """
            CREATE TABLE usage_holds (
                call_id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                member_id UUID NOT NULL,
                micros BIGINT NOT NULL,
                expires_at TIMESTAMPTZ NOT NULL
            );
            CREATE INDEX usage_holds_org_member ON usage_holds (org_id, member_id)
                INCLUDE (micros, expires_at, call_id);
            """.trimIndent(),
            tenantTableSql("usage_holds"),
        ).joinToString("\n"),
    ),
    // RLS audit R9: the router's policies hold only inside its own functions, as identity's
    // do; the owner, a member of `ostrom_router` to hand them over, no longer passes them.
    SchemaMigration(
        version = 6,
        name = "usage_router_policies_inside_functions_only",
        sql = """
            ALTER POLICY usage_events_router_read ON usage_events USING (current_user = 'ostrom_router');
            ALTER POLICY usage_events_router_purge ON usage_events USING (current_user = 'ostrom_router')
        """.trimIndent(),
    ),
)
