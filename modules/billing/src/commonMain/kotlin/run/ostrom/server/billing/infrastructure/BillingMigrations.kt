// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.billing.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration

/** The row-level policy of an org-keyed table: only the organization set by `ostrom.org_id` in the transaction. */
private fun orgPolicy(table: String) = """
    ALTER TABLE $table ENABLE ROW LEVEL SECURITY;
    ALTER TABLE $table FORCE ROW LEVEL SECURITY;
    CREATE POLICY ${table}_org ON $table
        USING (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID))
        WITH CHECK (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID));
""".trimIndent()

/** What the webhook and the worker do across organizations: functions owned by `ostrom_router`. */
private val ROUTER_FUNCTIONS = listOf(
    "billing_customer_org(TEXT)",
    "billing_claim_due_tasks(TIMESTAMPTZ, TIMESTAMPTZ, INTEGER)",
    "billing_complete_task(UUID, TIMESTAMPTZ)",
    "billing_fail_task(UUID, TIMESTAMPTZ)",
)

/**
 * Version 3: `billing_customers` and `billing_outbox` under forced row-level security like the other tables (RLS
 * audit R2), and what crosses organizations moved into narrow `SECURITY DEFINER` functions owned by `ostrom_router`
 * (`NOLOGIN`, never a member of the serving role), with `pg_temp` last in their `search_path`, executable by the
 * serving role only: the webhook's lookup of an organization by its Stripe customer, and the worker's claim (`FOR
 * UPDATE SKIP LOCKED`), completion and retry of a task, the statements they replace, unchanged.
 */
private val ROUTING_UNDER_ROW_LEVEL_SECURITY_SQL: String =
    orgPolicy("billing_customers") + "\n" + orgPolicy("billing_outbox") + "\n" +
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
        CREATE POLICY billing_customers_router ON billing_customers FOR SELECT TO ostrom_router USING (true);
        GRANT SELECT ON billing_customers TO ostrom_router;
        CREATE POLICY billing_outbox_router ON billing_outbox TO ostrom_router USING (true) WITH CHECK (true);
        GRANT SELECT, UPDATE ON billing_outbox TO ostrom_router;
        CREATE FUNCTION billing_customer_org(p_customer TEXT) RETURNS UUID
            LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
            SELECT c.org_id FROM billing_customers c WHERE c.stripe_customer_id = p_customer;
        $$;
        CREATE FUNCTION billing_claim_due_tasks(p_now TIMESTAMPTZ, p_lease TIMESTAMPTZ, p_limit INTEGER)
            RETURNS TABLE (id UUID, org_id UUID, kind TEXT, identifier TEXT, happened_at TIMESTAMPTZ, attempts INTEGER)
            LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
            UPDATE billing_outbox o SET next_attempt_at = p_lease WHERE o.id IN (
                SELECT d.id FROM billing_outbox d WHERE d.done_at IS NULL AND d.next_attempt_at <= p_now
                ORDER BY d.next_attempt_at LIMIT p_limit FOR UPDATE SKIP LOCKED
            ) RETURNING o.id, o.org_id, o.kind, o.identifier, o.happened_at, o.attempts;
        $$;
        CREATE FUNCTION billing_complete_task(p_id UUID, p_at TIMESTAMPTZ) RETURNS VOID
            LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
            UPDATE billing_outbox SET done_at = p_at WHERE id = p_id;
        $$;
        CREATE FUNCTION billing_fail_task(p_id UUID, p_retry TIMESTAMPTZ) RETURNS VOID
            LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
            UPDATE billing_outbox SET attempts = attempts + 1, next_attempt_at = p_retry WHERE id = p_id;
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
        """.trimIndent()

/**
 * The schema of the billing module; its tables start with `billing_`. Every table that carries an organization is
 * under row-level security, scoped per transaction (S2-01, CONVENTIONS § 16.1); `billing_stripe_events` (event ids,
 * written by the signed webhook) carries none. `billing_customers` (Stripe customer id to organization, read by the
 * webhook before it knows the organization) and `billing_outbox` (the worker's queue) are also used across
 * organizations, only through [ROUTER_FUNCTIONS] (version 3, docs/tenancy.md).
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
        name = "billing_routing_under_row_level_security",
        sql = ROUTING_UNDER_ROW_LEVEL_SECURITY_SQL,
    ),
    // RLS audit R9: the router's policies hold only inside its own functions, as identity's
    // do; the owner, a member of `ostrom_router` to hand them over, no longer passes them.
    SchemaMigration(
        version = 4,
        name = "billing_router_policies_inside_functions_only",
        sql = """
            ALTER POLICY billing_customers_router ON billing_customers USING (current_user = 'ostrom_router');
            ALTER POLICY billing_outbox_router ON billing_outbox USING (current_user = 'ostrom_router')
                WITH CHECK (current_user = 'ostrom_router')
        """.trimIndent(),
    ),
)
