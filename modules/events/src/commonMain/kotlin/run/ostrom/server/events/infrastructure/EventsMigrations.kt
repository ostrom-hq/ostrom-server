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

/** The webhooks' paths across organizations, owned by `ostrom_router`. */
private val WEBHOOK_ROUTER_FUNCTIONS = listOf(
    "events_webhook_orgs(TIMESTAMPTZ, INTEGER)",
    "events_webhook_maintain(TIMESTAMPTZ, TIMESTAMPTZ, TIMESTAMPTZ, INTEGER)",
)

/**
 * Version 2: TeamCode's `webhook_subscriptions` and `webhook_deliveries`. `events_webhooks` holds the secret sealed
 * with the server's key and the cursor into the journal; `events_webhook_deliveries` is unique per webhook and event,
 * so a replica or a restart never queues an event twice, and its lease lets every replica's worker claim deliveries
 * together. Both are confined to the organization of the transaction; a worker finds the organizations with work
 * (`events_webhook_orgs`) and expires and deletes old deliveries (`events_webhook_maintain`) across organizations only
 * through functions owned by `ostrom_router` (docs/tenancy.md), and does everything else inside each organization.
 */
private val WEBHOOKS_SQL: String =
    """
    CREATE TABLE events_webhooks (
        id UUID PRIMARY KEY,
        org_id UUID NOT NULL,
        url TEXT NOT NULL,
        types TEXT NOT NULL,
        description TEXT NOT NULL,
        enabled BOOLEAN NOT NULL,
        disabled_reason TEXT,
        secret TEXT NOT NULL,
        created_by UUID NOT NULL,
        created_at TIMESTAMPTZ NOT NULL,
        updated_at TIMESTAMPTZ NOT NULL,
        version INTEGER NOT NULL,
        fanout_seq BIGINT NOT NULL,
        failures INTEGER NOT NULL
    );
    CREATE INDEX events_webhooks_org ON events_webhooks (org_id, created_at, id);
    CREATE TABLE events_webhook_deliveries (
        id UUID PRIMARY KEY,
        org_id UUID NOT NULL,
        webhook_id UUID NOT NULL REFERENCES events_webhooks (id) ON DELETE CASCADE,
        event_seq BIGINT,
        event_type TEXT NOT NULL,
        payload TEXT NOT NULL,
        state TEXT NOT NULL,
        attempts INTEGER NOT NULL,
        next_attempt_at TIMESTAMPTZ NOT NULL,
        lease_until TIMESTAMPTZ NOT NULL,
        last_status INTEGER,
        last_error TEXT,
        created_at TIMESTAMPTZ NOT NULL,
        delivered_at TIMESTAMPTZ,
        test BOOLEAN NOT NULL,
        UNIQUE (webhook_id, event_seq)
    );
    CREATE INDEX events_webhook_deliveries_log ON events_webhook_deliveries (webhook_id, created_at, id);
    CREATE INDEX events_webhook_deliveries_due ON events_webhook_deliveries (next_attempt_at)
        WHERE state = 'pending';
    CREATE INDEX events_webhook_deliveries_age ON events_webhook_deliveries (created_at);
    ${tenantTableSql("events_webhooks")}
    ${tenantTableSql("events_webhook_deliveries")}
    DO $$ BEGIN
        CREATE ROLE ostrom_router NOLOGIN NOSUPERUSER NOBYPASSRLS;
    EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL;
    END $$;
    DO $$ BEGIN
        GRANT ostrom_router TO CURRENT_USER;
    EXCEPTION WHEN unique_violation THEN NULL;
    END $$;
    CREATE POLICY events_cursors_router_read ON events_cursors FOR SELECT TO ostrom_router
        USING (current_user = 'ostrom_router');
    CREATE POLICY events_webhooks_router_read ON events_webhooks FOR SELECT TO ostrom_router
        USING (current_user = 'ostrom_router');
    CREATE POLICY events_webhook_deliveries_router_read ON events_webhook_deliveries FOR SELECT TO ostrom_router
        USING (current_user = 'ostrom_router');
    CREATE POLICY events_webhook_deliveries_router_expire ON events_webhook_deliveries FOR UPDATE TO ostrom_router
        USING (current_user = 'ostrom_router') WITH CHECK (current_user = 'ostrom_router');
    CREATE POLICY events_webhook_deliveries_router_purge ON events_webhook_deliveries FOR DELETE TO ostrom_router
        USING (current_user = 'ostrom_router');
    GRANT SELECT ON events_cursors, events_webhooks TO ostrom_router;
    GRANT SELECT, UPDATE, DELETE ON events_webhook_deliveries TO ostrom_router;
    CREATE FUNCTION events_webhook_orgs(p_now TIMESTAMPTZ, p_limit INTEGER) RETURNS TABLE (org_id UUID)
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT work.org_id FROM (
            SELECT w.org_id, p_now AS since FROM events_webhooks w
                JOIN events_cursors c ON c.org_id = w.org_id
                WHERE w.enabled AND w.fanout_seq < c.last_seq
            UNION ALL
            SELECT d.org_id, d.next_attempt_at FROM events_webhook_deliveries d
                JOIN events_webhooks w ON w.id = d.webhook_id
                WHERE d.state = 'pending' AND d.next_attempt_at <= p_now AND d.lease_until < p_now
                    AND (w.enabled OR d.test)
        ) work GROUP BY work.org_id ORDER BY min(work.since), work.org_id LIMIT p_limit;
    $$;
    CREATE FUNCTION events_webhook_maintain(
        p_now TIMESTAMPTZ, p_give_up_before TIMESTAMPTZ, p_delete_before TIMESTAMPTZ, p_limit INTEGER
    ) RETURNS BIGINT
        LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        WITH expired AS (
            UPDATE events_webhook_deliveries SET state = 'failed', last_error = 'expired', lease_until = p_now
            WHERE id IN (
                SELECT e.id FROM events_webhook_deliveries e
                WHERE e.state = 'pending' AND e.created_at < p_give_up_before AND e.lease_until < p_now
                LIMIT p_limit
            ) RETURNING 1
        ), gone AS (
            DELETE FROM events_webhook_deliveries WHERE id IN (
                SELECT e.id FROM events_webhook_deliveries e
                WHERE e.state <> 'pending' AND e.created_at < p_delete_before LIMIT p_limit
            ) RETURNING 1
        ) SELECT (SELECT COUNT(*) FROM expired) + (SELECT COUNT(*) FROM gone);
    $$;
    ${WEBHOOK_ROUTER_FUNCTIONS.joinToString("\n") { "REVOKE EXECUTE ON FUNCTION $it FROM PUBLIC;" }}
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
            ${WEBHOOK_ROUTER_FUNCTIONS.joinToString("\n") { "GRANT EXECUTE ON FUNCTION $it TO ostrom_app;" }}
        END IF;
    END $$;
    GRANT CREATE ON SCHEMA public TO ostrom_router;
    ${WEBHOOK_ROUTER_FUNCTIONS.joinToString("\n") { "ALTER FUNCTION $it OWNER TO ostrom_router;" }}
    REVOKE CREATE ON SCHEMA public FROM ostrom_router
    """
        .trimIndent()

/** Where the readers of the journal start and stand across organizations, owned by `ostrom_router`. */
private val READER_ROUTER_FUNCTIONS = listOf("events_readers_behind(TEXT, INTEGER)")

/**
 * Version 3: the cursors of the journal's readers (the kernel's `OrgEventJournal`, TeamCode's `notification_cursors`):
 * one row per organization and reader, the last sequence the reader turned into its own work, moved in the reader's
 * own transaction. Confined to the organization of the transaction; `events_readers_behind`, owned by `ostrom_router`,
 * starts a reader at the head of every organization it has not seen and lists those it is behind on, across
 * organizations (docs/tenancy.md).
 */
private val READERS_SQL: String =
    """
    CREATE TABLE events_readers (
        org_id UUID NOT NULL,
        reader TEXT NOT NULL,
        seq BIGINT NOT NULL,
        PRIMARY KEY (org_id, reader)
    );
    ${tenantTableSql("events_readers")}
    DO $$ BEGIN
        CREATE ROLE ostrom_router NOLOGIN NOSUPERUSER NOBYPASSRLS;
    EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL;
    END $$;
    DO $$ BEGIN
        GRANT ostrom_router TO CURRENT_USER;
    EXCEPTION WHEN unique_violation THEN NULL;
    END $$;
    CREATE POLICY events_readers_router_read ON events_readers FOR SELECT TO ostrom_router
        USING (current_user = 'ostrom_router');
    CREATE POLICY events_readers_router_start ON events_readers FOR INSERT TO ostrom_router
        WITH CHECK (current_user = 'ostrom_router');
    GRANT SELECT, INSERT ON events_readers TO ostrom_router;
    CREATE FUNCTION events_readers_behind(p_reader TEXT, p_limit INTEGER)
        RETURNS TABLE (org_id UUID, from_seq BIGINT, head BIGINT)
        LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        WITH started AS (
            INSERT INTO events_readers (org_id, reader, seq)
            SELECT c.org_id, p_reader, c.last_seq FROM events_cursors c
            WHERE NOT EXISTS (
                SELECT 1 FROM events_readers r WHERE r.org_id = c.org_id AND r.reader = p_reader
            )
            ON CONFLICT DO NOTHING
            RETURNING 1
        )
        SELECT r.org_id, r.seq, c.last_seq FROM events_readers r
            JOIN events_cursors c ON c.org_id = r.org_id
            WHERE r.reader = p_reader AND r.seq < c.last_seq
            ORDER BY r.org_id LIMIT p_limit;
    $$;
    ${READER_ROUTER_FUNCTIONS.joinToString("\n") { "REVOKE EXECUTE ON FUNCTION $it FROM PUBLIC;" }}
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
            ${READER_ROUTER_FUNCTIONS.joinToString("\n") { "GRANT EXECUTE ON FUNCTION $it TO ostrom_app;" }}
        END IF;
    END $$;
    GRANT CREATE ON SCHEMA public TO ostrom_router;
    ${READER_ROUTER_FUNCTIONS.joinToString("\n") { "ALTER FUNCTION $it OWNER TO ostrom_router;" }}
    REVOKE CREATE ON SCHEMA public FROM ostrom_router
    """
        .trimIndent()

/** The schema of the events module; its tables start with `events_`. */
val EventsMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(1, "create_journal", TENANT_ROLE_SQL + "\n" + JOURNAL_SQL),
    SchemaMigration(2, "create_webhooks", WEBHOOKS_SQL),
    SchemaMigration(3, "create_readers", READERS_SQL),
)
