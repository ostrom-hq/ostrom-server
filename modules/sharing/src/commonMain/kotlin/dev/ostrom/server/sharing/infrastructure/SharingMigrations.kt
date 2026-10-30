package dev.ostrom.server.sharing.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

/** The schema of the sharing module; its tables start with `sharing_`. */
val SharingMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        version = 1,
        name = "create_sharing",
        sql = """
            CREATE TABLE sharing_accounts (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                provider TEXT NOT NULL,
                label TEXT NOT NULL,
                email TEXT,
                provider_account_id TEXT NOT NULL,
                owner_member_id UUID,
                priority INTEGER NOT NULL,
                status TEXT NOT NULL,
                max_concurrent INTEGER NOT NULL,
                utilization_ceiling DOUBLE PRECISION NOT NULL,
                monthly_budget_micros BIGINT,
                allowed_members TEXT,
                cooldown_until TIMESTAMPTZ,
                envelope TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL,
                UNIQUE (org_id, provider, provider_account_id)
            );
            CREATE INDEX sharing_accounts_ready ON sharing_accounts (org_id, provider, status);
            CREATE TABLE sharing_windows (
                org_id UUID NOT NULL,
                account_id UUID NOT NULL REFERENCES sharing_accounts (id) ON DELETE CASCADE,
                name TEXT NOT NULL,
                utilization DOUBLE PRECISION NOT NULL,
                resets_at TIMESTAMPTZ,
                observed_at TIMESTAMPTZ NOT NULL,
                PRIMARY KEY (account_id, name)
            );
            CREATE INDEX sharing_windows_org ON sharing_windows (org_id)
        """.trimIndent(),
    ),
)
