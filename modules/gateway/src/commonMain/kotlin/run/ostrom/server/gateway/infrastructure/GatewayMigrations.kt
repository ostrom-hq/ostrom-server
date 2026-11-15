// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.secrets.infrastructure.TENANT_ROLE_SQL
import run.ostrom.server.secrets.infrastructure.tenantTableSql

/**
 * The schema of the gateway module; its tables start with `gateway_`. Version 2 confines both tables to their org
 * (row-level security, SECURITY-AUDIT S2-01); version 3 adds the admission queue, version 4 the cancellation markers,
 * version 6 the capacity controller.
 */
val GatewayMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        version = 1,
        name = "create_gateway_settings",
        sql = """
            CREATE TABLE gateway_settings (
                org_id UUID PRIMARY KEY,
                anthropic_key_secret TEXT,
                openai_key_secret TEXT,
                openrouter_key_secret TEXT,
                use_shared_accounts BOOLEAN NOT NULL
            );
            CREATE TABLE gateway_model_policies (
                org_id UUID PRIMARY KEY,
                allowed TEXT NOT NULL,
                default_model TEXT,
                by_member TEXT NOT NULL
            );
        """.trimIndent(),
    ),
    SchemaMigration(
        version = 2,
        name = "confine_gateway_to_their_org",
        sql = TENANT_ROLE_SQL + "\n" + tenantTableSql("gateway_settings") + "\n" +
            tenantTableSql("gateway_model_policies"),
    ),
    // The admission queue shared by every replica (TeamCode's `admission_waiters` and `admission_turns`): one row per
    // call from its arrival to its end, `running` once admitted, and each member's last turn per scope. Metadata only:
    // no body, prompt or credential. The members' concurrency limits live with the settings.
    SchemaMigration(
        version = 3,
        name = "create_gateway_admissions",
        sql = """
            CREATE TABLE gateway_admissions (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                member_id UUID NOT NULL,
                scope TEXT NOT NULL,
                workload TEXT NOT NULL,
                running BOOLEAN NOT NULL,
                created_at TIMESTAMPTZ NOT NULL,
                ready_at TIMESTAMPTZ NOT NULL,
                polled_at TIMESTAMPTZ NOT NULL,
                expires_at TIMESTAMPTZ NOT NULL,
                turn_taken BIGINT,
                turn_before BIGINT
            );
            CREATE INDEX gateway_admissions_waiting ON gateway_admissions (org_id, scope, running, ready_at);
            CREATE INDEX gateway_admissions_member ON gateway_admissions (org_id, member_id, running);
            CREATE INDEX gateway_admissions_expiry ON gateway_admissions (org_id, expires_at);
            CREATE TABLE gateway_admission_turns (
                org_id UUID NOT NULL,
                scope TEXT NOT NULL,
                member_id UUID NOT NULL,
                turn BIGINT NOT NULL,
                PRIMARY KEY (org_id, scope, member_id)
            );
            ALTER TABLE gateway_settings ADD COLUMN member_concurrency TEXT NOT NULL DEFAULT '{}'
        """.trimIndent() + ";\n" + tenantTableSql("gateway_admissions") + "\n" +
            tenantTableSql("gateway_admission_turns"),
    ),
    // The cancellation markers of inference calls (TeamCode's `request_cancellations`): a member's own ids, kept ten
    // minutes, read by every replica that runs one of their calls.
    SchemaMigration(
        version = 4,
        name = "create_gateway_cancellations",
        sql = """
            CREATE TABLE gateway_cancellations (
                org_id UUID NOT NULL,
                member_id UUID NOT NULL,
                request_id UUID NOT NULL,
                expires_at TIMESTAMPTZ NOT NULL,
                PRIMARY KEY (org_id, member_id, request_id)
            );
            CREATE INDEX gateway_cancellations_expiry ON gateway_cancellations (org_id, expires_at)
        """.trimIndent() + ";\n" + tenantTableSql("gateway_cancellations"),
    ),
    // Every member's `maxConcurrent` back to 0, unlimited, the default (as TeamCode): no request waits on a
    // concurrency slot an administrator did not choose under the former 1-to-32 rule.
    SchemaMigration(
        version = 5,
        name = "gateway_unlimited_member_concurrency",
        sql = "UPDATE gateway_settings SET member_concurrency = '{}'",
    ),
    // A launch's `maxConcurrent` (`launches.limits.v1`): a call names its launch, and the calls it runs are counted.
    SchemaMigration(
        version = 6,
        name = "gateway_admissions_name_the_launch",
        sql = """
            ALTER TABLE gateway_admissions ADD COLUMN launch_id TEXT;
            CREATE INDEX gateway_admissions_launch ON gateway_admissions (org_id, launch_id, running)
                WHERE launch_id IS NOT NULL
        """.trimIndent(),
    ),
    // The org's API price table (TeamCode's `api_prices`, ADR 0002 step 1): empty means the built-in prices apply. At
    // most 200 rows an org, read whole by the primary key's prefix.
    SchemaMigration(
        version = 7,
        name = "create_gateway_api_prices",
        sql = """
            CREATE TABLE gateway_api_prices (
                org_id UUID NOT NULL,
                provider TEXT NOT NULL,
                model TEXT NOT NULL,
                effective_from_day BIGINT NOT NULL,
                input_micros BIGINT NOT NULL,
                cache_write_micros BIGINT NOT NULL,
                cache_read_micros BIGINT NOT NULL,
                output_micros BIGINT NOT NULL,
                PRIMARY KEY (org_id, provider, model, effective_from_day)
            )
        """.trimIndent() + ";\n" + tenantTableSql("gateway_api_prices"),
    ),
    // The capacity controller (TeamCode's `pacing_settings`, `directives` and `session_directives` in one table keyed
    // by scope, `directive_deliveries`, `directive_receipts`): its settings, its advice per member ('' scope) and
    // session (the SHA-256 digest of its id), with when the scope's last call was admitted, which the admission
    // spacing reads, and the deliveries of the advice to the agents' hooks.
    SchemaMigration(
        version = 8,
        name = "create_gateway_pacing",
        sql = """
            CREATE TABLE gateway_pacing_settings (
                org_id UUID PRIMARY KEY,
                observation_only BOOLEAN NOT NULL DEFAULT FALSE,
                auto_pool_size BOOLEAN NOT NULL DEFAULT FALSE
            );
            CREATE TABLE gateway_directives (
                org_id UUID NOT NULL,
                member_id UUID NOT NULL,
                scope_key TEXT NOT NULL,
                id TEXT NOT NULL,
                pace TEXT NOT NULL,
                max_concurrent INTEGER NOT NULL,
                min_interval_ms BIGINT NOT NULL,
                message TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL,
                expires_at TIMESTAMPTZ NOT NULL,
                context_key TEXT NOT NULL,
                recovery_pace TEXT,
                recovery_started_at TIMESTAMPTZ,
                recovery_min_interval_ms BIGINT,
                last_admitted_at TIMESTAMPTZ,
                PRIMARY KEY (org_id, member_id, scope_key)
            );
            CREATE INDEX gateway_directives_expiry ON gateway_directives (org_id, expires_at);
            CREATE TABLE gateway_directive_deliveries (
                org_id UUID NOT NULL,
                member_id UUID NOT NULL,
                session_key TEXT NOT NULL,
                stage TEXT NOT NULL,
                directive_id TEXT NOT NULL,
                claim_id TEXT NOT NULL,
                token_id TEXT NOT NULL,
                claimed_until TIMESTAMPTZ NOT NULL,
                emitted_at TIMESTAMPTZ,
                updated_at TIMESTAMPTZ NOT NULL,
                PRIMARY KEY (org_id, member_id, session_key, stage)
            );
            CREATE INDEX gateway_directive_deliveries_retention ON gateway_directive_deliveries (org_id, updated_at);
            CREATE TABLE gateway_directive_receipts (
                org_id UUID NOT NULL,
                directive_id TEXT NOT NULL,
                token_id TEXT NOT NULL,
                stage TEXT NOT NULL,
                received_at TIMESTAMPTZ NOT NULL,
                PRIMARY KEY (org_id, directive_id, token_id, stage)
            );
            CREATE INDEX gateway_directive_receipts_retention ON gateway_directive_receipts (org_id, received_at)
        """.trimIndent() + ";\n" + tenantTableSql("gateway_pacing_settings") + "\n" +
            tenantTableSql("gateway_directives") + "\n" + tenantTableSql("gateway_directive_deliveries") + "\n" +
            tenantTableSql("gateway_directive_receipts"),
    ),
)
