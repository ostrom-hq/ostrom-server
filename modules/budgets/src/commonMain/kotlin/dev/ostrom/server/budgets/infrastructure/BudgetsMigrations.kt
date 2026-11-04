// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.secrets.infrastructure.TENANT_ROLE_SQL
import dev.ostrom.server.secrets.infrastructure.tenantTableSql

/**
 * The schema of the budgets module; its tables start with `budgets_`. `budgets_limits.scope` is `ORG` and
 * `DEFAULT_MEMBER` (member id null) or `MEMBER` (member id set); since version 3 a subject has one row per `period`
 * (`DAY` or `MONTH`), each with its `action` (`BLOCK` or `WARN`). `budgets_alerts` makes each alert unique per limit
 * value, action, threshold and period across replicas: a changed limit alerts again. Both tables stay under the
 * row-level security of version 2: the new columns are read and written only through rows of the caller's org. Since
 * version 4, `WORKFLOW` names its workflow in `workflow_id` (set for it alone), in both tables.
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
                limit_micros BIGINT NOT NULL,
                alerted_at TIMESTAMPTZ NOT NULL
            );
            CREATE UNIQUE INDEX budgets_alerts_once
                ON budgets_alerts (org_id, scope, member_id, period_start, threshold, limit_micros) NULLS NOT DISTINCT
        """.trimIndent(),
    ),
    SchemaMigration(
        version = 2,
        name = "confine_budgets_to_their_org",
        sql = listOf(TENANT_ROLE_SQL, tenantTableSql("budgets_limits"), tenantTableSql("budgets_alerts"))
            .joinToString("\n"),
    ),
    SchemaMigration(
        version = 3,
        name = "budgets_by_the_day_that_stop_or_warn",
        sql = """
            ALTER TABLE budgets_limits
                ADD COLUMN period TEXT NOT NULL DEFAULT 'MONTH' CHECK (period IN ('DAY', 'MONTH')),
                ADD COLUMN action TEXT NOT NULL DEFAULT 'BLOCK' CHECK (action IN ('BLOCK', 'WARN'));
            DROP INDEX budgets_limits_scope;
            CREATE UNIQUE INDEX budgets_limits_scope
                ON budgets_limits (org_id, scope, member_id, period) NULLS NOT DISTINCT;
            ALTER TABLE budgets_alerts
                ADD COLUMN period TEXT NOT NULL DEFAULT 'MONTH' CHECK (period IN ('DAY', 'MONTH')),
                ADD COLUMN action TEXT NOT NULL DEFAULT 'BLOCK' CHECK (action IN ('BLOCK', 'WARN'));
            DROP INDEX budgets_alerts_once;
            CREATE UNIQUE INDEX budgets_alerts_once ON budgets_alerts
                (org_id, scope, member_id, period, action, period_start, threshold, limit_micros) NULLS NOT DISTINCT
        """.trimIndent(),
    ),
    SchemaMigration(
        version = 4,
        name = "budgets_of_a_workflow",
        sql = """
            ALTER TABLE budgets_limits ADD COLUMN workflow_id TEXT,
                ADD CONSTRAINT budgets_limits_workflow CHECK ((scope = 'WORKFLOW') = (workflow_id IS NOT NULL));
            DROP INDEX budgets_limits_scope;
            CREATE UNIQUE INDEX budgets_limits_scope
                ON budgets_limits (org_id, scope, member_id, workflow_id, period) NULLS NOT DISTINCT;
            ALTER TABLE budgets_alerts ADD COLUMN workflow_id TEXT;
            DROP INDEX budgets_alerts_once;
            CREATE UNIQUE INDEX budgets_alerts_once ON budgets_alerts
                (org_id, scope, member_id, workflow_id, period, action, period_start, threshold, limit_micros)
                NULLS NOT DISTINCT
        """.trimIndent(),
    ),
)
