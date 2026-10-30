// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.slack.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

private const val ORG = "CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID)"

// A click names a Slack member before any organization is known: its transaction sets `ostrom.slack_member` to
// `<team>:<user>` (only after the interaction's signature is checked) and reads that one link, never another.
private const val MEMBER = "team_id || ':' || user_id = NULLIF(current_setting('ostrom.slack_member', TRUE), '')"

/**
 * The Slack integration's schema: the members reached on Slack (one link per member, one member per Slack account)
 * and the direct messages sent per request, both under forced row level security.
 */
val SlackMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        1,
        "create_slack",
        """
        CREATE TABLE slack_links (
            org_id UUID NOT NULL,
            person_id TEXT NOT NULL,
            team_id TEXT NOT NULL,
            user_id TEXT NOT NULL,
            linked_by TEXT NOT NULL,
            linked_at TIMESTAMPTZ NOT NULL,
            PRIMARY KEY (org_id, person_id),
            UNIQUE (team_id, user_id)
        );
        CREATE TABLE slack_messages (
            org_id UUID NOT NULL,
            request_id UUID NOT NULL,
            gate_id UUID NOT NULL,
            channel TEXT NOT NULL,
            ts TEXT NOT NULL,
            PRIMARY KEY (org_id, request_id)
        );
        CREATE INDEX slack_messages_gate ON slack_messages (org_id, gate_id);
        ALTER TABLE slack_links ENABLE ROW LEVEL SECURITY;
        ALTER TABLE slack_links FORCE ROW LEVEL SECURITY;
        CREATE POLICY slack_links_read ON slack_links FOR SELECT USING (org_id = $ORG OR $MEMBER);
        CREATE POLICY slack_links_insert ON slack_links FOR INSERT WITH CHECK (org_id = $ORG);
        CREATE POLICY slack_links_update ON slack_links FOR UPDATE USING (org_id = $ORG) WITH CHECK (org_id = $ORG);
        CREATE POLICY slack_links_delete ON slack_links FOR DELETE USING (org_id = $ORG);
        ALTER TABLE slack_messages ENABLE ROW LEVEL SECURITY;
        ALTER TABLE slack_messages FORCE ROW LEVEL SECURITY;
        CREATE POLICY slack_messages_org ON slack_messages USING (org_id = $ORG) WITH CHECK (org_id = $ORG)
        """.trimIndent(),
    ),
)
