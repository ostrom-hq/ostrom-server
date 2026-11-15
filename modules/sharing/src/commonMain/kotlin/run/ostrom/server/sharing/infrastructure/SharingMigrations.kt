// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.secrets.infrastructure.TENANT_ROLE_SQL
import run.ostrom.server.secrets.infrastructure.tenantTableSql

/** The prune of every organization's old readings, owned by `ostrom_router`. */
private const val PURGE_FUNCTION = "sharing_quota_history_purge(TIMESTAMPTZ, INTEGER)"

/**
 * Version 11: TeamCode's `quota_history`, the readings of the accounts' windows over time, one per minute and window
 * (`sample_minute`, the newest of the minute kept), confined to its org like the other sharing tables. Its `recent`
 * index serves the forecast's read (an account's windows over the last hour); its `age` index the prune. The prune
 * crosses organizations only through `sharing_quota_history_purge`, a `SECURITY DEFINER` function owned by
 * `ostrom_router` (docs/tenancy.md), which refuses a date it cannot bound and never deletes a reading younger than the
 * retention (seven days, `QuotaHistoryRepository.QUOTA_HISTORY_RETENTION`), at most 10,000 per call.
 */
private val QUOTA_HISTORY_SQL: String =
    """
    CREATE TABLE sharing_quota_history (
        org_id UUID NOT NULL,
        account_id UUID NOT NULL REFERENCES sharing_accounts (id) ON DELETE CASCADE,
        name TEXT NOT NULL,
        utilization DOUBLE PRECISION NOT NULL,
        resets_at TIMESTAMPTZ,
        observed_at TIMESTAMPTZ NOT NULL,
        sample_minute BIGINT NOT NULL,
        PRIMARY KEY (account_id, name, sample_minute)
    );
    CREATE INDEX sharing_quota_history_recent ON sharing_quota_history (account_id, name, observed_at);
    CREATE INDEX sharing_quota_history_age ON sharing_quota_history (observed_at);
    """.trimIndent() + "\n" + tenantTableSql("sharing_quota_history") + "\n" +
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
        CREATE POLICY sharing_quota_history_router_read ON sharing_quota_history FOR SELECT TO ostrom_router
            USING (current_user = 'ostrom_router');
        CREATE POLICY sharing_quota_history_router_purge ON sharing_quota_history FOR DELETE TO ostrom_router
            USING (current_user = 'ostrom_router');
        GRANT SELECT, DELETE ON sharing_quota_history TO ostrom_router;
        CREATE FUNCTION sharing_quota_history_purge(p_before TIMESTAMPTZ, p_limit INTEGER) RETURNS BIGINT
            LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
            DECLARE
                gone BIGINT;
            BEGIN
                IF p_before IS NULL OR NOT isfinite(p_before) OR p_before > now() THEN
                    RAISE EXCEPTION 'purge date refused: no date, an infinite one or one in the future'
                        USING ERRCODE = '22023';
                END IF;
                IF p_limit IS NULL OR p_limit < 1 OR p_limit > 10000 THEN
                    RAISE EXCEPTION 'purge limit refused: 1 to 10000' USING ERRCODE = '22023';
                END IF;
                DELETE FROM sharing_quota_history WHERE (account_id, name, sample_minute) IN (
                    SELECT h.account_id, h.name, h.sample_minute FROM sharing_quota_history h
                    WHERE h.observed_at < LEAST(p_before, now() - INTERVAL '7 days') LIMIT p_limit
                );
                GET DIAGNOSTICS gone = ROW_COUNT;
                RETURN gone;
            END
        $$;
        REVOKE EXECUTE ON FUNCTION $PURGE_FUNCTION FROM PUBLIC;
        DO $$ BEGIN
            IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
                GRANT EXECUTE ON FUNCTION $PURGE_FUNCTION TO ostrom_app;
            END IF;
        END $$;
        GRANT CREATE ON SCHEMA public TO ostrom_router;
        ALTER FUNCTION $PURGE_FUNCTION OWNER TO ostrom_router;
        REVOKE CREATE ON SCHEMA public FROM ostrom_router
        """.trimIndent()

private const val ORGS_FUNCTION = "sharing_orgs_with_accounts()"

private val PACING_READS_SQL: String =
    """
    CREATE INDEX sharing_quota_history_org ON sharing_quota_history (org_id, observed_at);
    CREATE POLICY sharing_accounts_router_orgs ON sharing_accounts FOR SELECT TO ostrom_router
        USING (current_user = 'ostrom_router');
    GRANT SELECT (org_id) ON sharing_accounts TO ostrom_router;
    CREATE FUNCTION $ORGS_FUNCTION RETURNS SETOF UUID
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT DISTINCT org_id FROM sharing_accounts ORDER BY org_id LIMIT 100000
    $$;
    REVOKE EXECUTE ON FUNCTION $ORGS_FUNCTION FROM PUBLIC;
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
            GRANT EXECUTE ON FUNCTION $ORGS_FUNCTION TO ostrom_app;
        END IF;
    END $$;
    GRANT CREATE ON SCHEMA public TO ostrom_router;
    ALTER FUNCTION $ORGS_FUNCTION OWNER TO ostrom_router;
    REVOKE CREATE ON SCHEMA public FROM ostrom_router
    """.trimIndent()

/** The schema of the sharing module; its tables start with `sharing_`, each confined to its org (S2-01, S2-21). */
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
    SchemaMigration(
        version = 2,
        name = "confine_sharing_to_their_org",
        sql = TENANT_ROLE_SQL + "\n" + tenantTableSql("sharing_accounts") + "\n" + tenantTableSql("sharing_windows"),
    ),
    SchemaMigration(
        version = 3,
        name = "version_sharing_accounts",
        sql = "ALTER TABLE sharing_accounts ADD COLUMN version BIGINT NOT NULL DEFAULT 1",
    ),
    // The contract's daily allocation (`dailyAllocationUsd`), in USD millionths like the monthly budget.
    SchemaMigration(
        version = 4,
        name = "daily_budget_of_sharing_accounts",
        sql = "ALTER TABLE sharing_accounts ADD COLUMN daily_budget_micros BIGINT",
    ),
    // The contract's groups (`/api/v1/groups`): members of an organization and the accounts lent to them. Each table
    // is confined to its organization; the foreign keys cascade a deleted group or account.
    SchemaMigration(
        version = 5,
        name = "create_sharing_groups",
        sql = """
            CREATE TABLE sharing_groups (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                name TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL
            );
            CREATE UNIQUE INDEX sharing_groups_name ON sharing_groups (org_id, lower(name));
            CREATE TABLE sharing_group_members (
                org_id UUID NOT NULL,
                group_id UUID NOT NULL REFERENCES sharing_groups (id) ON DELETE CASCADE,
                member_id UUID NOT NULL,
                paused_until TIMESTAMPTZ,
                PRIMARY KEY (group_id, member_id)
            );
            CREATE INDEX sharing_group_members_org ON sharing_group_members (org_id, group_id);
            CREATE TABLE sharing_group_accounts (
                org_id UUID NOT NULL,
                group_id UUID NOT NULL REFERENCES sharing_groups (id) ON DELETE CASCADE,
                account_id UUID NOT NULL REFERENCES sharing_accounts (id) ON DELETE CASCADE,
                PRIMARY KEY (group_id, account_id)
            );
            CREATE INDEX sharing_group_accounts_org ON sharing_group_accounts (org_id, group_id);
            CREATE INDEX sharing_group_accounts_account ON sharing_group_accounts (account_id)
        """.trimIndent() + ";\n" + tenantTableSql("sharing_groups") + "\n" +
            tenantTableSql("sharing_group_members") + "\n" + tenantTableSql("sharing_group_accounts"),
    ),
    // An account lent to groups (`sharedWith: group:<id>`): a lease reads the groups of the member who asks.
    SchemaMigration(
        version = 6,
        name = "sharing_group_members_by_member",
        sql = "CREATE INDEX sharing_group_members_member ON sharing_group_members (org_id, member_id)",
    ),
    // The refresh intent every replica sees (TeamCode's `refresh_pending`): committed under the row's lock before the
    // provider is called, so two replicas or a killed pod never spend a rotating refresh token twice.
    // `credential_version` changes with the tokens only (a refresh, a share), unlike `version`, which an edit bumps.
    SchemaMigration(
        version = 7,
        name = "sharing_refresh_intent",
        sql = """
            ALTER TABLE sharing_accounts ADD COLUMN refresh_pending BOOLEAN NOT NULL DEFAULT FALSE;
            ALTER TABLE sharing_accounts ADD COLUMN refresh_started_at TIMESTAMPTZ;
            ALTER TABLE sharing_accounts ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 1
        """.trimIndent(),
    ),
    // The requests in flight on each account and the account each session used, shared by every replica (TeamCode's
    // reservations and `conversation_affinities`): a slot expires by itself after its lease, so a killed replica's
    // slots free up; an affinity is a digest of the session id, never the id.
    SchemaMigration(
        version = 8,
        name = "sharing_slots_and_affinities",
        sql = """
            CREATE TABLE sharing_slots (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                account_id UUID NOT NULL REFERENCES sharing_accounts (id) ON DELETE CASCADE,
                expires_at TIMESTAMPTZ NOT NULL
            );
            CREATE INDEX sharing_slots_account ON sharing_slots (account_id, expires_at);
            CREATE INDEX sharing_slots_org ON sharing_slots (org_id, expires_at);
            CREATE TABLE sharing_affinities (
                org_id UUID NOT NULL,
                member_id UUID NOT NULL,
                session_hash TEXT NOT NULL,
                account_id UUID NOT NULL REFERENCES sharing_accounts (id) ON DELETE CASCADE,
                expires_at TIMESTAMPTZ NOT NULL,
                PRIMARY KEY (org_id, member_id, session_hash)
            );
            CREATE INDEX sharing_affinities_account ON sharing_affinities (account_id);
            CREATE INDEX sharing_affinities_expiry ON sharing_affinities (org_id, expires_at)
        """.trimIndent() + ";\n" + tenantTableSql("sharing_slots") + "\n" + tenantTableSql("sharing_affinities"),
    ),
    // Who renews an account's tokens (TeamCode's refresh ownership): OSTROM, PENDING_HANDOVER (imported, its source may
    // still renew them) or ACCESS_ONLY. Every account shared so far is Ostrom's.
    SchemaMigration(
        version = 9,
        name = "sharing_refresh_ownership",
        sql = "ALTER TABLE sharing_accounts ADD COLUMN ownership TEXT NOT NULL DEFAULT 'OSTROM'",
    ),
    // No concurrency limit by default, as TeamCode (0 is unlimited): the accounts shared with the former default of 8,
    // or any limit, no longer make a request wait on a slot nobody chose.
    SchemaMigration(
        version = 10,
        name = "sharing_unlimited_concurrency",
        sql = "UPDATE sharing_accounts SET max_concurrent = 0",
    ),
    SchemaMigration(version = 11, name = "sharing_quota_history", sql = QUOTA_HISTORY_SQL),
    // The capacity controller's reads: an org's readings of the last hour by time, and the orgs that share accounts,
    // listed across organizations by a function that sees only `org_id` (TeamCode's single team needs neither).
    SchemaMigration(version = 12, name = "sharing_pacing_reads", sql = PACING_READS_SQL),
)
