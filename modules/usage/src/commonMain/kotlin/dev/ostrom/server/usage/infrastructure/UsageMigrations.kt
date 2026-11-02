// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.usage.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.secrets.infrastructure.TENANT_ROLE_SQL
import dev.ostrom.server.secrets.infrastructure.tenantTableSql

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
                "USING (current_setting('$RETENTION_SETTING', true) = 'on');",
            "CREATE POLICY usage_events_retention ON usage_events FOR DELETE " +
                "USING (current_setting('$RETENTION_SETTING', true) = 'on');",
        ).joinToString("\n"),
    ),
    SchemaMigration(
        version = 3,
        name = "usage_events_name_the_agent_launch",
        // The launch whose agent token sent the event (server#108), null for a person's own credential.
        sql = "ALTER TABLE usage_events ADD COLUMN launch_id TEXT",
    ),
    SchemaMigration(
        version = 4,
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
            CREATE INDEX usage_holds_org_member ON usage_holds (org_id, member_id) INCLUDE (micros, expires_at);
            """.trimIndent(),
            tenantTableSql("usage_holds"),
        ).joinToString("\n"),
    ),
)

/** The transaction-local setting that opens the retention policies of `usage_events`. */
const val RETENTION_SETTING: String = "ostrom.usage_retention"
