// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

/*
 * The tables of [McpRelayMigrations], as Exposed reads and writes them. The migrations create the schema; these
 * objects only name its columns. Every one is a tenant table, read and written in `inOrg` (the tenant role with the
 * organization set), each query filtered on `org_id` too. The stdio command, the tool allowlist and the policy
 * conditions are JSON text, written and read by the repository's own serializers.
 */

/** The MCP servers of an organization, a team's (no owner) or a member's own. */
internal object McpRelayServers : Table("mcp_relay_servers") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val name = text("name")
    val transport = text("transport")
    val url = text("url").nullable()
    val credentialSecretId = uuid("credential_secret_id").nullable()
    val credentialHeader = text("credential_header").nullable()
    val stdio = text("stdio").nullable()
    val sharing = text("sharing")
    val version = integer("version")
    val createdAt = instant("created_at")
    val ownerMemberId = uuid("owner_member_id").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** Who may use a server (a member, or everyone when the member is null) and with which tools. */
internal object McpRelayAssignments : Table("mcp_relay_assignments") {
    val orgId = uuid("org_id")
    val serverId = uuid("server_id")
    val memberId = uuid("member_id").nullable()
    val tools = text("tools").nullable()
    val credentialSecretId = uuid("credential_secret_id").nullable()
    val assignedAt = instant("assigned_at")
}

/** The argument policies of a server's tools. */
internal object McpRelayPolicies : Table("mcp_relay_policies") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val serverId = uuid("server_id")
    val tool = text("tool")
    val conditions = text("conditions")
    val effect = text("effect")
    val message = text("message")
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(id)
}

/** An organization's relay settings. */
internal object McpRelaySettings : Table("mcp_relay_settings") {
    val orgId = uuid("org_id")
    val agentPrefix = text("agent_prefix")
    val memberHostedMcp = bool("member_hosted_mcp").default(false)

    override val primaryKey = PrimaryKey(orgId)
}

/** The journal of every call. */
internal object McpRelayCalls : Table("mcp_relay_calls") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val memberId = uuid("member_id")
    val serverId = uuid("server_id")
    val serverName = text("server_name")
    val operation = text("operation")
    val tool = text("tool").nullable()
    val outcome = text("outcome")
    val startedAt = instant("started_at")
    val durationMs = long("duration_ms")
    val responseBytes = long("response_bytes")
    val policyId = uuid("policy_id").nullable()
    val launchId = text("launch_id").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** The running operations, each expiring after its lease. */
internal object McpRelaySlots : Table("mcp_relay_slots") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val serverId = uuid("server_id")
    val memberId = uuid("member_id")
    val expiresAt = instant("expires_at")

    override val primaryKey = PrimaryKey(id)
}

/** The operations started each minute (the database's), by server and member. */
internal object McpRelayMinutes : Table("mcp_relay_minutes") {
    val orgId = uuid("org_id")
    val serverId = uuid("server_id")
    val minute = long("minute")
    val memberId = uuid("member_id")
    val calls = integer("calls")

    override val primaryKey = PrimaryKey(serverId, minute, memberId)
}

/** The state of each hosted process as the minute sweep last saw it. */
internal object McpRelayStdioStates : Table("mcp_relay_stdio_states") {
    val orgId = uuid("org_id")
    val serverId = uuid("server_id")
    val memberId = uuid("member_id").nullable()
    val processKey = text("process_key")
    val state = text("state")
    val failure = text("failure").nullable()
    val changedAt = instant("changed_at")
}
