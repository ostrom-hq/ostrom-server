package dev.ostrom.server.billing.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

/** The schema of the billing module; its tables start with `billing_`, and every row is keyed by organization. */
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
)
