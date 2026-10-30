// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.usage.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

/**
 * The schema of the usage module; its tables start with `usage_`. The spend indexes include `cost_micros` so that the
 * budget sums are index-only scans; `usage_events_at` serves the retention purge.
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
)
