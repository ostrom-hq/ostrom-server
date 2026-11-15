// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.database.TENANT_ROLE
import run.ostrom.server.kernel.database.TENANT_ROLE_SQL

private const val ORG = "CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID)"

private fun confined(table: String) = """
    ALTER TABLE $table ENABLE ROW LEVEL SECURITY;
    ALTER TABLE $table FORCE ROW LEVEL SECURITY;
    CREATE POLICY ${table}_org ON $table USING (org_id = $ORG) WITH CHECK (org_id = $ORG);
""".trimIndent()

/**
 * Version 1: each member's Discord link (`discord_links`, one per member of an organization, a Discord account linked
 * by one member only, with the bot's direct message channel once opened), the unfinished links
 * (`discord_link_attempts`, by the hash of their single-use state), and the yes requests' messages in the approvers'
 * Discord (`discord_approval_messages`, one per request), updated once their gate is decided. All three confined to the
 * organization of the transaction (docs/tenancy.md), read and written by the tenant role the repositories switch to
 * on Exposed (`JdbcDatabase.inOrg`).
 */
val DiscordMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        1,
        "create_discord_tables",
        """
        CREATE TABLE discord_links (
            org_id UUID NOT NULL,
            member_id UUID NOT NULL,
            discord_user_id TEXT NOT NULL,
            discord_username TEXT NOT NULL,
            dm_channel_id TEXT,
            linked_at TIMESTAMPTZ NOT NULL,
            PRIMARY KEY (org_id, member_id),
            UNIQUE (org_id, discord_user_id)
        );
        CREATE TABLE discord_link_attempts (
            org_id UUID NOT NULL,
            state_hash TEXT NOT NULL,
            member_id UUID NOT NULL,
            binding TEXT NOT NULL,
            expires_at TIMESTAMPTZ NOT NULL,
            PRIMARY KEY (org_id, state_hash)
        );
        CREATE INDEX discord_link_attempts_member ON discord_link_attempts (org_id, member_id);
        CREATE INDEX discord_link_attempts_expiry ON discord_link_attempts (org_id, expires_at);
        CREATE TABLE discord_approval_messages (
            org_id UUID NOT NULL,
            approval_id UUID NOT NULL,
            gate_id UUID NOT NULL,
            channel_id TEXT NOT NULL,
            message_id TEXT NOT NULL,
            PRIMARY KEY (org_id, approval_id)
        );
        ${confined("discord_links")}
        ${confined("discord_link_attempts")}
        ${confined("discord_approval_messages")}
        $TENANT_ROLE_SQL
        GRANT SELECT, INSERT, UPDATE, DELETE ON discord_links, discord_link_attempts, discord_approval_messages
            TO $TENANT_ROLE
        """.trimIndent(),
    ),
)
