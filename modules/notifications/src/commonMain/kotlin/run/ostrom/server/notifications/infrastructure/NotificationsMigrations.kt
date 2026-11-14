// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.database.TENANT_ROLE

private const val ORG = "CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID)"

/** The router's paths across organizations, owned by `ostrom_router`. */
private val ROUTER_FUNCTIONS = listOf(
    "notifications_delivery_orgs(TIMESTAMPTZ, INTEGER)",
    "notifications_deliveries_maintain(TIMESTAMPTZ, TIMESTAMPTZ, TIMESTAMPTZ, INTEGER)",
)

/**
 * Version 6: TeamCode's `notification_deliveries`, one row per journal event, member and channel (unique on the
 * four with the organization), so a page of the journal is never queued twice; its lease lets a replica that dies
 * mid-attempt lose nothing. Confined to the organization of the transaction and granted to the tenant role the
 * Exposed queries switch to (`JdbcDatabase.inOrg`). The worker finds the organizations with due deliveries
 * (`notifications_delivery_orgs`) and gives up and deletes old ones (`notifications_deliveries_maintain`) across
 * organizations only through functions owned by `ostrom_router` (docs/tenancy.md).
 */
private val DELIVERIES_SQL: String =
    """
    DO $$ BEGIN
        CREATE ROLE $TENANT_ROLE NOLOGIN;
    EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL;
    END $$;
    DO $$ BEGIN
        GRANT $TENANT_ROLE TO CURRENT_USER;
    EXCEPTION WHEN unique_violation THEN NULL;
    END $$;
    CREATE TABLE notifications_deliveries (
        org_id UUID NOT NULL,
        id UUID NOT NULL,
        event_seq BIGINT NOT NULL,
        member_id UUID NOT NULL,
        channel TEXT NOT NULL,
        kind TEXT NOT NULL,
        title TEXT NOT NULL,
        body TEXT NOT NULL,
        session_id TEXT,
        wait_id TEXT,
        collapse_key TEXT NOT NULL,
        state TEXT NOT NULL,
        attempts INTEGER NOT NULL,
        next_attempt_at TIMESTAMPTZ NOT NULL,
        lease_until TIMESTAMPTZ NOT NULL,
        last_error TEXT,
        created_at TIMESTAMPTZ NOT NULL,
        delivered_at TIMESTAMPTZ,
        PRIMARY KEY (org_id, id),
        UNIQUE (org_id, event_seq, member_id, channel)
    );
    CREATE INDEX notifications_deliveries_due ON notifications_deliveries (next_attempt_at)
        WHERE state = 'pending';
    CREATE INDEX notifications_deliveries_age ON notifications_deliveries (created_at);
    ALTER TABLE notifications_deliveries ENABLE ROW LEVEL SECURITY;
    ALTER TABLE notifications_deliveries FORCE ROW LEVEL SECURITY;
    CREATE POLICY notifications_deliveries_org ON notifications_deliveries
        USING (org_id = $ORG) WITH CHECK (org_id = $ORG);
    GRANT SELECT, INSERT, UPDATE, DELETE ON notifications_deliveries TO $TENANT_ROLE;
    DO $$ BEGIN
        CREATE ROLE ostrom_router NOLOGIN NOSUPERUSER NOBYPASSRLS;
    EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL;
    END $$;
    DO $$ BEGIN
        GRANT ostrom_router TO CURRENT_USER;
    EXCEPTION WHEN unique_violation THEN NULL;
    END $$;
    GRANT USAGE ON SCHEMA public TO ostrom_router;
    CREATE POLICY notifications_deliveries_router_read ON notifications_deliveries FOR SELECT TO ostrom_router
        USING (current_user = 'ostrom_router');
    CREATE POLICY notifications_deliveries_router_expire ON notifications_deliveries FOR UPDATE TO ostrom_router
        USING (current_user = 'ostrom_router') WITH CHECK (current_user = 'ostrom_router');
    CREATE POLICY notifications_deliveries_router_purge ON notifications_deliveries FOR DELETE TO ostrom_router
        USING (current_user = 'ostrom_router');
    GRANT SELECT, UPDATE, DELETE ON notifications_deliveries TO ostrom_router;
    CREATE FUNCTION notifications_delivery_orgs(p_now TIMESTAMPTZ, p_limit INTEGER) RETURNS SETOF UUID
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT d.org_id FROM notifications_deliveries d
            WHERE d.state = 'pending' AND d.next_attempt_at <= p_now AND d.lease_until < p_now
            GROUP BY d.org_id ORDER BY min(d.next_attempt_at), d.org_id LIMIT p_limit;
    $$;
    CREATE FUNCTION notifications_deliveries_maintain(
        p_now TIMESTAMPTZ, p_give_up_before TIMESTAMPTZ, p_delete_before TIMESTAMPTZ, p_limit INTEGER
    ) RETURNS BIGINT
        LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        WITH expired AS (
            UPDATE notifications_deliveries SET state = 'failed', last_error = 'expired', lease_until = p_now
            WHERE (org_id, id) IN (
                SELECT e.org_id, e.id FROM notifications_deliveries e
                WHERE e.state = 'pending' AND e.created_at < p_give_up_before AND e.lease_until < p_now
                LIMIT p_limit
            ) RETURNING 1
        ), gone AS (
            DELETE FROM notifications_deliveries WHERE (org_id, id) IN (
                SELECT e.org_id, e.id FROM notifications_deliveries e
                WHERE e.state <> 'pending' AND e.created_at < p_delete_before LIMIT p_limit
            ) RETURNING 1
        ) SELECT (SELECT COUNT(*) FROM expired) + (SELECT COUNT(*) FROM gone);
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

/**
 * The notifications schema: the members' phones, one row per push token in an organization, under forced row level
 * security (docs/tenancy.md). Version 2 keeps who registered each phone and with which credential (`user_id`,
 * `credential_id`, `browser`): a phone is notified while that credential works, and forgotten when the person signs
 * out with it. A phone of version 1 has none and is notified again once the app registers it anew. Version 3 keeps an
 * `fcm` phone's P-256 public key (`public_key`), which every push to it is encrypted for. Version 4 holds the
 * members' switches of the notification router (TeamCode's `member_notification_settings`): one row per member,
 * channel and kind a member changed, no row meaning on. Version 5 keeps the instance's identity at each push relay
 * (TeamCode's `push_relay_instances`): the instance's, not an organization's, so without `org_id` (docs/tenancy.md),
 * its Ed25519 private key sealed with the secrets key. Version 6 is the router's delivery queue ([DELIVERIES_SQL]).
 * Version 7 lets it hold the requests for a yes: a row of kind `approval` names the request (`approval_id`), its gate
 * (`gate_id`) and the approver's login (`approver`), null on every other row.
 */
val NotificationsMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        1,
        "create_push_devices",
        """
        CREATE TABLE notifications_push_devices (
            org_id UUID NOT NULL,
            id UUID NOT NULL,
            member_id UUID NOT NULL,
            push_token TEXT NOT NULL,
            provider TEXT NOT NULL,
            platform TEXT NOT NULL,
            locale TEXT,
            created_at TIMESTAMPTZ NOT NULL,
            seen_at TIMESTAMPTZ NOT NULL,
            PRIMARY KEY (org_id, id),
            UNIQUE (org_id, push_token)
        );
        CREATE INDEX notifications_push_devices_member ON notifications_push_devices (org_id, member_id, seen_at);
        ALTER TABLE notifications_push_devices ENABLE ROW LEVEL SECURITY;
        ALTER TABLE notifications_push_devices FORCE ROW LEVEL SECURITY;
        CREATE POLICY notifications_push_devices_org ON notifications_push_devices
            USING (org_id = $ORG) WITH CHECK (org_id = $ORG)
        """.trimIndent(),
    ),
    SchemaMigration(
        2,
        "push_devices_credential",
        """
        ALTER TABLE notifications_push_devices ADD COLUMN user_id UUID;
        ALTER TABLE notifications_push_devices ADD COLUMN credential_id UUID;
        ALTER TABLE notifications_push_devices ADD COLUMN browser BOOLEAN NOT NULL DEFAULT FALSE;
        CREATE INDEX notifications_push_devices_credential
            ON notifications_push_devices (org_id, user_id, credential_id)
        """.trimIndent(),
    ),
    SchemaMigration(
        3,
        "push_devices_public_key",
        "ALTER TABLE notifications_push_devices ADD COLUMN public_key TEXT",
    ),
    SchemaMigration(
        4,
        "create_member_settings",
        """
        CREATE TABLE notifications_member_settings (
            org_id UUID NOT NULL,
            member_id UUID NOT NULL,
            channel TEXT NOT NULL,
            kind TEXT NOT NULL,
            enabled BOOLEAN NOT NULL,
            PRIMARY KEY (org_id, member_id, channel, kind)
        );
        ALTER TABLE notifications_member_settings ENABLE ROW LEVEL SECURITY;
        ALTER TABLE notifications_member_settings FORCE ROW LEVEL SECURITY;
        CREATE POLICY notifications_member_settings_org ON notifications_member_settings
            USING (org_id = $ORG) WITH CHECK (org_id = $ORG)
        """.trimIndent(),
    ),
    SchemaMigration(
        5,
        "create_push_relay_instances",
        """
        CREATE TABLE notifications_push_relay_instances (
            relay_url TEXT PRIMARY KEY,
            public_key TEXT NOT NULL,
            private_key TEXT NOT NULL,
            instance_id TEXT,
            created_at TIMESTAMPTZ NOT NULL
        )
        """.trimIndent(),
    ),
    SchemaMigration(6, "create_deliveries", DELIVERIES_SQL),
    SchemaMigration(
        7,
        "deliveries_approvals",
        """
        ALTER TABLE notifications_deliveries ADD COLUMN approval_id UUID;
        ALTER TABLE notifications_deliveries ADD COLUMN gate_id UUID;
        ALTER TABLE notifications_deliveries ADD COLUMN approver TEXT
        """.trimIndent(),
    ),
)
