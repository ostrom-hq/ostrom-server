// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.secrets.infrastructure.TENANT_ROLE_SQL
import run.ostrom.server.secrets.infrastructure.tenantTableSql

/**
 * The schema of the gateway module; its tables start with `gateway_`. Version 2 confines both tables to their org
 * (row-level security, SECURITY-AUDIT S2-01); version 3 adds the admission queue, version 4 the cancellation markers.
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
)
