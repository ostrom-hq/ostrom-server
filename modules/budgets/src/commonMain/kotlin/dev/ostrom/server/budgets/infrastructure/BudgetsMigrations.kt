// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

/**
 * The schema of the budgets module; its tables start with `budgets_`. `budgets_limits.scope` is `ORG` and
 * `DEFAULT_MEMBER` (member id null) or `MEMBER` (member id set). `budgets_alerts` makes each alert unique per limit,
 * threshold and month across replicas.
 */
val BudgetsMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        version = 1,
        name = "create_budgets",
        sql = """
            CREATE TABLE budgets_limits (
                org_id UUID NOT NULL,
                member_id UUID,
                scope TEXT NOT NULL,
                limit_micros BIGINT NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL,
                CHECK ((scope = 'MEMBER') = (member_id IS NOT NULL))
            );
            CREATE UNIQUE INDEX budgets_limits_scope ON budgets_limits (org_id, scope, member_id) NULLS NOT DISTINCT;
            CREATE INDEX budgets_limits_member ON budgets_limits (org_id, member_id);
            CREATE TABLE budgets_alerts (
                org_id UUID NOT NULL,
                member_id UUID,
                scope TEXT NOT NULL,
                period_start TIMESTAMPTZ NOT NULL,
                threshold INTEGER NOT NULL,
                alerted_at TIMESTAMPTZ NOT NULL
            );
            CREATE UNIQUE INDEX budgets_alerts_once ON budgets_alerts (org_id, scope, member_id, period_start, threshold) NULLS NOT DISTINCT
        """.trimIndent(),
    ),
)
