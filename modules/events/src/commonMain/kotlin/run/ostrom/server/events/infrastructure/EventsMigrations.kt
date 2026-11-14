// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.secrets.infrastructure.TENANT_ROLE_SQL
import run.ostrom.server.secrets.infrastructure.tenantTableSql

/** The purge of every organization's old events, the one function of version 1 owned by `ostrom_router`. */
private val ROUTER_FUNCTIONS = listOf("events_purge(TIMESTAMPTZ, INTEGER)")

/**
 * Version 1: the journal of TeamCode's `team_events` and its cursors. `events_cursors` holds the last sequence of each
 * organization; its row lock serializes the publishers of an organization, so commit order is sequence order.
 * `events_journal` is keyed by `(org_id, seq)`; `events_journal_member` serves a member's own events,
 * `events_journal_retention` the purge. Both tables are confined to the organization of the transaction; the purge
 * reads and deletes across organizations only through `events_purge`, owned by `ostrom_router`, whose policies hold
 * only inside it (docs/tenancy.md).
 */
private val JOURNAL_SQL: String =
    """
    CREATE TABLE events_cursors (
        org_id UUID PRIMARY KEY,
        last_seq BIGINT NOT NULL
    );
    CREATE TABLE events_journal (
        org_id UUID NOT NULL,
        seq BIGINT NOT NULL,
        type TEXT NOT NULL,
        subject TEXT NOT NULL,
        subject_id TEXT NOT NULL,
        member_id UUID,
        at TIMESTAMPTZ NOT NULL,
        data TEXT NOT NULL,
        PRIMARY KEY (org_id, seq)
    );
    CREATE INDEX events_journal_member ON events_journal (org_id, member_id, seq);
    CREATE INDEX events_journal_retention ON events_journal (at);
    ${tenantTableSql("events_cursors")}
    ${tenantTableSql("events_journal")}
    DO $$ BEGIN
        CREATE ROLE ostrom_router NOLOGIN NOSUPERUSER NOBYPASSRLS;
    EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL;
    END $$;
    DO $$ BEGIN
        GRANT ostrom_router TO CURRENT_USER;
    EXCEPTION WHEN unique_violation THEN NULL;
    END $$;
    GRANT USAGE ON SCHEMA public TO ostrom_router;
    CREATE POLICY events_journal_router_read ON events_journal FOR SELECT TO ostrom_router
        USING (current_user = 'ostrom_router');
    CREATE POLICY events_journal_router_purge ON events_journal FOR DELETE TO ostrom_router
        USING (current_user = 'ostrom_router');
    GRANT SELECT, DELETE ON events_journal TO ostrom_router;
    CREATE FUNCTION events_purge(p_before TIMESTAMPTZ, p_limit INTEGER) RETURNS BIGINT
        LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        WITH gone AS (
            DELETE FROM events_journal WHERE (org_id, seq) IN (
                SELECT e.org_id, e.seq FROM events_journal e WHERE e.at < p_before LIMIT p_limit
            ) RETURNING 1
        ) SELECT COUNT(*) FROM gone;
    $$;
    ${ROUTER_FUNCTIONS.joinToString("\n") { "REVOKE EXECUTE ON FUNCTION $it FROM PUBLIC;" }}
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
            ${ROUTER_FUNCTIONS.joinToString("\n") { "GRANT EXECUTE ON FUNCTION $it TO ostrom_app;" }}
        END IF;
    END $$;
    GRANT CREATE ON SCHEMA public TO ostrom_router;
    ${ROUTER_FUNCTIONS.joinToString("\n") { "ALTER FUNCTION $it OWNER TO ostrom_router;" }}
    REVOKE CREATE ON SCHEMA public FROM ostrom_router
    """
        .trimIndent()

/** The schema of the events module; its tables start with `events_`. */
val EventsMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(1, "create_journal", TENANT_ROLE_SQL + "\n" + JOURNAL_SQL),
)
