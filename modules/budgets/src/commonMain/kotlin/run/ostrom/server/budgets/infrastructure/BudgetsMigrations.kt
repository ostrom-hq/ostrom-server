// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.secrets.infrastructure.TENANT_ROLE_SQL
import run.ostrom.server.secrets.infrastructure.tenantTableSql

/**
 * The schema of the budgets module; its tables start with `budgets_`. `budgets_limits.scope` is `ORG` and
 * `DEFAULT_MEMBER` (member id null) or `MEMBER` (member id set); since version 3 a subject has one row per `period`
 * (`DAY` or `MONTH`), each with its `action` (`BLOCK` or `WARN`). `budgets_alerts` makes each alert unique per limit
 * value, action, threshold and period across replicas: a changed limit alerts again. Both tables stay under the
 * row-level security of version 2: the new columns are read and written only through rows of the caller's org. Since
 * version 4, `WORKFLOW` names its workflow in `workflow_id` (set for it alone), in both tables.
 * Version 5 adds `budgets_overrides`, a member's temporary daily budgets, kept after they end. Version 6 adds
 * `budgets_automatic_daily`, the org's automatic daily budget sized from its shared accounts.
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
    // Temporary daily budgets (TeamCode's `budget_overrides`): kept after they end, read through Exposed. The one in
    // force is found by the partial index (at most one row a member is unrevoked and unexpired), the history by
    // (created_at, id) descending.
    SchemaMigration(
        version = 5,
        name = "create_budgets_overrides",
        sql = """
            CREATE TABLE budgets_overrides (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                member_id UUID NOT NULL,
                micros BIGINT NOT NULL CHECK (micros >= 0),
                created_by UUID NOT NULL,
                reason TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL,
                expires_at TIMESTAMPTZ NOT NULL,
                replaces_id UUID,
                revoked_at TIMESTAMPTZ,
                revoked_by UUID,
                revocation_reason TEXT
            );
            CREATE INDEX budgets_overrides_active
                ON budgets_overrides (org_id, member_id, expires_at) WHERE revoked_at IS NULL;
            CREATE INDEX budgets_overrides_page ON budgets_overrides (org_id, member_id, created_at DESC, id DESC)
        """.trimIndent() + ";\n" + tenantTableSql("budgets_overrides"),
    ),
    // The automatic pool size (TeamCode's `pool_budgets.auto_daily_units` and `auto_computed_at`): one row an org,
    // read by its primary key with every read of a call's limits, written every ten minutes under the org's lock.
    SchemaMigration(
        version = 6,
        name = "create_budgets_automatic_daily",
        sql = """
            CREATE TABLE budgets_automatic_daily (
                org_id UUID PRIMARY KEY,
                micros BIGINT NOT NULL CHECK (micros >= 0),
                computed_at TIMESTAMPTZ NOT NULL
            )
        """.trimIndent() + ";\n" + tenantTableSql("budgets_automatic_daily"),
    ),
)
