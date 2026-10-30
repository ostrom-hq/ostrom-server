// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.billing.infrastructure

import dev.ostrom.server.kernel.database.ROUTER_ROLE
import dev.ostrom.server.kernel.database.ROUTER_ROLE_SQL
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.database.routedTableSql
import dev.ostrom.server.kernel.database.routerFunctionsSql

/** Hands the cross-organization functions of version 3 over to [ROUTER_ROLE]. */
private val ROUTER_FUNCTIONS_OWNER = routerFunctionsSql(
    "billing_org_of_customer(TEXT)",
    "billing_claim_outbox(TIMESTAMPTZ, TIMESTAMPTZ, INTEGER)",
    "billing_complete_outbox(UUID, TIMESTAMPTZ)",
    "billing_fail_outbox(UUID, TIMESTAMPTZ)",
)

/** The row-level policy of an org-keyed table: only the organization set by `ostrom.org_id` in the transaction. */
private fun orgPolicy(table: String) = """
    ALTER TABLE $table ENABLE ROW LEVEL SECURITY;
    ALTER TABLE $table FORCE ROW LEVEL SECURITY;
    CREATE POLICY ${table}_org ON $table
        USING (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID))
        WITH CHECK (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID));
""".trimIndent()

/**
 * The schema of the billing module; its tables start with `billing_`. Every table with an `org_id` is under row-level
 * security, scoped per transaction (S2-01, S2-51, CONVENTIONS § 16.1). `billing_customers` (Stripe customer id to
 * organization) and `billing_outbox` (the worker's queue) are also reached across organizations, only through the
 * narrow `SECURITY DEFINER` functions of version 3, owned by [ROUTER_ROLE]: the signed webhook finds an organization
 * by its Stripe customer, the worker claims, completes and retries due tasks. `billing_stripe_events` holds event ids
 * only, no organization.
 */
val BillingMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        version = 1,
        name = "create_billing",
        sql = """
            CREATE TABLE billing_accounts (
                org_id UUID PRIMARY KEY,
                stripe_customer_id TEXT UNIQUE,
                stripe_subscription_id TEXT,
                subscription_item_id TEXT,
                plan TEXT NOT NULL,
                billing_interval TEXT,
                status TEXT,
                current_period_start TIMESTAMPTZ,
                current_period_end TIMESTAMPTZ,
                cancel_at_period_end BOOLEAN NOT NULL,
                committed_people INTEGER NOT NULL,
                payment_issue_kind TEXT,
                payment_issue_url TEXT,
                payment_issue_at TIMESTAMPTZ,
                updated_at TIMESTAMPTZ NOT NULL
            );
            CREATE TABLE billing_stripe_events (
                event_id TEXT PRIMARY KEY,
                type TEXT NOT NULL,
                processed_at TIMESTAMPTZ NOT NULL
            );
            CREATE TABLE billing_active_people (
                org_id UUID NOT NULL,
                period_start TIMESTAMPTZ NOT NULL,
                user_id TEXT NOT NULL,
                first_active_at TIMESTAMPTZ NOT NULL,
                PRIMARY KEY (org_id, period_start, user_id)
            );
            CREATE TABLE billing_usage (
                org_id UUID NOT NULL,
                period_start TIMESTAMPTZ NOT NULL,
                agent_seconds BIGINT NOT NULL,
                sessions BIGINT NOT NULL,
                PRIMARY KEY (org_id, period_start)
            );
            CREATE TABLE billing_alerts (
                org_id UUID NOT NULL,
                period_start TIMESTAMPTZ NOT NULL,
                percent INTEGER NOT NULL,
                raised_at TIMESTAMPTZ NOT NULL,
                PRIMARY KEY (org_id, period_start, percent)
            );
            CREATE TABLE billing_outbox (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                kind TEXT NOT NULL,
                identifier TEXT NOT NULL UNIQUE,
                happened_at TIMESTAMPTZ NOT NULL,
                attempts INTEGER NOT NULL,
                next_attempt_at TIMESTAMPTZ NOT NULL,
                done_at TIMESTAMPTZ
            );
            CREATE INDEX billing_outbox_due ON billing_outbox (next_attempt_at) WHERE done_at IS NULL;
        """.trimIndent(),
    ),
    SchemaMigration(
        version = 2,
        name = "billing_row_level_security",
        sql = listOf(
            """
            CREATE TABLE billing_customers (
                stripe_customer_id TEXT PRIMARY KEY,
                org_id UUID NOT NULL
            );
            INSERT INTO billing_customers (stripe_customer_id, org_id)
                SELECT stripe_customer_id, org_id FROM billing_accounts WHERE stripe_customer_id IS NOT NULL;
            """.trimIndent(),
            orgPolicy("billing_accounts"),
            orgPolicy("billing_active_people"),
            orgPolicy("billing_usage"),
            orgPolicy("billing_alerts"),
        ).joinToString("\n"),
    ),
    SchemaMigration(
        version = 3,
        name = "confine_customers_and_outbox",
        sql = """
            $ROUTER_ROLE_SQL
            ${routedTableSql("billing_customers", "SELECT")}
            ${routedTableSql("billing_outbox", "SELECT, UPDATE")}
            CREATE FUNCTION billing_org_of_customer(p_customer TEXT) RETURNS TABLE (org_id UUID)
                LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
                AS $$ SELECT c.org_id FROM billing_customers c WHERE c.stripe_customer_id = p_customer $$;
            CREATE FUNCTION billing_claim_outbox(p_now TIMESTAMPTZ, p_lease TIMESTAMPTZ, p_limit INTEGER)
                RETURNS TABLE (
                    id UUID, org_id UUID, kind TEXT, identifier TEXT, happened_at TIMESTAMPTZ, attempts INTEGER
                )
                LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
                AS $$
                UPDATE billing_outbox o SET next_attempt_at = p_lease WHERE o.id IN (
                    SELECT d.id FROM billing_outbox d WHERE d.done_at IS NULL AND d.next_attempt_at <= p_now
                    ORDER BY d.next_attempt_at LIMIT p_limit FOR UPDATE SKIP LOCKED
                )
                RETURNING o.id, o.org_id, o.kind, o.identifier, o.happened_at, o.attempts
                $$;
            CREATE FUNCTION billing_complete_outbox(p_id UUID, p_at TIMESTAMPTZ) RETURNS VOID
                LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
                AS $$ UPDATE billing_outbox o SET done_at = p_at WHERE o.id = p_id $$;
            CREATE FUNCTION billing_fail_outbox(p_id UUID, p_retry TIMESTAMPTZ) RETURNS VOID
                LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
                AS $$
                UPDATE billing_outbox o SET attempts = o.attempts + 1, next_attempt_at = p_retry WHERE o.id = p_id
                $$;
            $ROUTER_FUNCTIONS_OWNER
        """.trimIndent(),
    ),
)
