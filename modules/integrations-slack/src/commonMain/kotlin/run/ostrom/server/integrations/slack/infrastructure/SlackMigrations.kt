// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.database.TENANT_ROLE
import run.ostrom.server.kernel.database.TENANT_ROLE_SQL

private const val ORG = "CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID)"

/**
 * Version 1: each member's Slack link (`slack_links`, one per member of an organization, the Slack user found by
 * their proven e-mail address, and a Slack account linked by one member only). Version 2: the yes requests' messages
 * in the approvers' Slack (`slack_approval_messages`, one per request), updated once their gate is decided. Both
 * confined to the organization of the transaction (docs/tenancy.md). Version 3: the tenant role the repositories
 * switch to on Exposed (`JdbcDatabase.inOrg`) reads and writes both.
 */
val SlackMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        1,
        "create_slack_links",
        """
        CREATE TABLE slack_links (
            org_id UUID NOT NULL,
            member_id UUID NOT NULL,
            slack_user_id TEXT NOT NULL,
            linked_at TIMESTAMPTZ NOT NULL,
            PRIMARY KEY (org_id, member_id),
            UNIQUE (org_id, slack_user_id)
        );
        ALTER TABLE slack_links ENABLE ROW LEVEL SECURITY;
        ALTER TABLE slack_links FORCE ROW LEVEL SECURITY;
        CREATE POLICY slack_links_org ON slack_links USING (org_id = $ORG) WITH CHECK (org_id = $ORG)
        """.trimIndent(),
    ),
    SchemaMigration(
        2,
        "create_slack_approval_messages",
        """
        CREATE TABLE slack_approval_messages (
            org_id UUID NOT NULL,
            approval_id UUID NOT NULL,
            gate_id UUID NOT NULL,
            slack_channel TEXT NOT NULL,
            ts TEXT NOT NULL,
            PRIMARY KEY (org_id, approval_id)
        );
        CREATE INDEX slack_approval_messages_gate ON slack_approval_messages (org_id, gate_id);
        ALTER TABLE slack_approval_messages ENABLE ROW LEVEL SECURITY;
        ALTER TABLE slack_approval_messages FORCE ROW LEVEL SECURITY;
        CREATE POLICY slack_approval_messages_org ON slack_approval_messages
            USING (org_id = $ORG) WITH CHECK (org_id = $ORG)
        """.trimIndent(),
    ),
    SchemaMigration(
        3,
        "grant_slack_tables_to_tenant",
        TENANT_ROLE_SQL + "\n" +
            "GRANT SELECT, INSERT, UPDATE, DELETE ON slack_links, slack_approval_messages TO $TENANT_ROLE",
    ),
)
