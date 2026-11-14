// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.governance.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.database.TENANT_SETTING

/** The sweep's read across organizations, a function owned by `ostrom_router` (docs/tenancy.md). */
private const val ROUTER_FUNCTION = "governance_expired_organizations(TIMESTAMPTZ, INTEGER)"

/**
 * The governance schema. Rows are isolated by organization in the database itself: row level security is forced (the
 * owner included) and every query runs in a transaction that first sets `ostrom.org_id` (`withOrganization`). The
 * sweep of expired confirmations only learns which organizations have some, through a function owned by
 * `ostrom_router`, then expires them within each organization.
 */
val GovernanceMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        1,
        "create_governance_confirmations",
        """
        CREATE TABLE governance_confirmations (
            org_id UUID NOT NULL,
            id TEXT NOT NULL,
            kind TEXT NOT NULL,
            scope TEXT NOT NULL,
            method TEXT NOT NULL,
            path TEXT NOT NULL,
            payload TEXT NOT NULL,
            summary TEXT NOT NULL,
            before_json TEXT NOT NULL,
            after_json TEXT NOT NULL,
            requested_by_user TEXT NOT NULL,
            requested_by_member UUID NOT NULL,
            credential TEXT NOT NULL,
            token_id TEXT,
            status TEXT NOT NULL,
            created_at TIMESTAMPTZ NOT NULL,
            expires_at TIMESTAMPTZ NOT NULL,
            answered_by_user TEXT,
            answered_by_member UUID,
            answered_at TIMESTAMPTZ,
            result_json TEXT,
            error_type TEXT,
            error_message TEXT,
            PRIMARY KEY (org_id, id)
        );
        CREATE INDEX governance_confirmations_waiting
            ON governance_confirmations (org_id, requested_by_user, created_at) WHERE status = 'pending';
        CREATE INDEX governance_confirmations_expiry
            ON governance_confirmations (expires_at) WHERE status = 'pending';
        ALTER TABLE governance_confirmations ENABLE ROW LEVEL SECURITY;
        ALTER TABLE governance_confirmations FORCE ROW LEVEL SECURITY;
        CREATE POLICY governance_confirmations_org ON governance_confirmations
            USING (org_id = CAST(NULLIF(current_setting('$TENANT_SETTING', TRUE), '') AS UUID))
            WITH CHECK (org_id = CAST(NULLIF(current_setting('$TENANT_SETTING', TRUE), '') AS UUID));
        DO $$ BEGIN
            CREATE ROLE ostrom_router NOLOGIN NOSUPERUSER NOBYPASSRLS;
        EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL;
        END $$;
        DO $$ BEGIN
            GRANT ostrom_router TO CURRENT_USER;
        EXCEPTION WHEN unique_violation THEN NULL;
        END $$;
        GRANT USAGE ON SCHEMA public TO ostrom_router;
        CREATE POLICY governance_confirmations_router ON governance_confirmations TO ostrom_router
            USING (current_user = 'ostrom_router') WITH CHECK (false);
        GRANT SELECT ON governance_confirmations TO ostrom_router;
        CREATE FUNCTION governance_expired_organizations(p_now TIMESTAMPTZ, p_limit INTEGER) RETURNS SETOF UUID
            LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
            SELECT DISTINCT c.org_id FROM governance_confirmations c
            WHERE c.status = 'pending' AND c.expires_at <= p_now LIMIT p_limit;
        $$;
        REVOKE EXECUTE ON FUNCTION $ROUTER_FUNCTION FROM PUBLIC;
        DO $$ BEGIN
            IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
                GRANT EXECUTE ON FUNCTION $ROUTER_FUNCTION TO ostrom_app;
            END IF;
        END $$;
        GRANT CREATE ON SCHEMA public TO ostrom_router;
        ALTER FUNCTION $ROUTER_FUNCTION OWNER TO ostrom_router;
        REVOKE CREATE ON SCHEMA public FROM ostrom_router
        """.trimIndent(),
    ),
    // The name of the personal token that asked, as it was then (audit 2, F1 bis): null for any other token.
    SchemaMigration(
        version = 2,
        name = "confirmation_token_name",
        sql = "ALTER TABLE governance_confirmations ADD COLUMN token_name TEXT",
    ),
    // A confirmed write whose result was never written (its server stopped between the answer and the result) is
    // failed by the sweep, `result_lost`, 5 minutes past its expiry (ConfirmationRules.RESULT_GRACE, decision of
    // 14/11/2026): the sweep's function finds those organizations too.
    SchemaMigration(
        version = 3,
        name = "confirmation_result_lost",
        sql = """
        CREATE INDEX governance_confirmations_unsettled ON governance_confirmations (expires_at)
            WHERE status = 'confirmed' AND result_json IS NULL;
        CREATE OR REPLACE FUNCTION governance_expired_organizations(p_now TIMESTAMPTZ, p_limit INTEGER)
            RETURNS SETOF UUID
            LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
            SELECT DISTINCT o.org_id FROM (
                (SELECT c.org_id FROM governance_confirmations c
                    WHERE c.status = 'pending' AND c.expires_at <= p_now LIMIT p_limit)
                UNION ALL
                (SELECT c.org_id FROM governance_confirmations c
                    WHERE c.status = 'confirmed' AND c.result_json IS NULL
                    AND c.expires_at <= p_now - INTERVAL '5 minutes' LIMIT p_limit)
            ) o LIMIT p_limit;
        $$
        """.trimIndent(),
    ),
)
