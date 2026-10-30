package dev.ostrom.server.mcprelay.infrastructure

import dev.ostrom.server.mcprelay.domain.ArgumentCondition
import dev.ostrom.server.mcprelay.domain.ArgumentPolicy
import dev.ostrom.server.mcprelay.domain.ConditionOperator
import dev.ostrom.server.mcprelay.domain.EffectiveServer
import dev.ostrom.server.mcprelay.domain.McpAssignment
import dev.ostrom.server.mcprelay.domain.McpCall
import dev.ostrom.server.mcprelay.domain.McpOperation
import dev.ostrom.server.mcprelay.domain.McpOutcome
import dev.ostrom.server.mcprelay.domain.McpRelayRepository
import dev.ostrom.server.mcprelay.domain.McpServer
import dev.ostrom.server.mcprelay.domain.McpSettings
import dev.ostrom.server.mcprelay.domain.McpSharing
import dev.ostrom.server.mcprelay.domain.McpTransport
import dev.ostrom.server.mcprelay.domain.McpUsage
import dev.ostrom.server.mcprelay.domain.PolicyEffect
import dev.ostrom.server.mcprelay.domain.StdioLauncher
import dev.ostrom.server.mcprelay.domain.StdioSpec
import dev.ostrom.server.mcprelay.domain.ToolPolicy
import dev.ostrom.server.secrets.infrastructure.TENANT_ROLE
import dev.ostrom.server.secrets.infrastructure.executeInOrg
import dev.ostrom.server.secrets.infrastructure.fetchInOrg
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [McpRelayRepository] on PostgreSQL (tables of [McpRelayMigrations]). A stdio command, an allowlist and policy
 * conditions are stored as JSON text.
 *
 * @param driver The database.
 */
@Suppress("TooManyFunctions") // Implements the one storage port of the module.
class McpRelayRepositoryImpl(private val driver: Driver) : McpRelayRepository {
    override suspend fun createServer(server: McpServer): Boolean = driver.executeInOrg(
        server.orgId,
        Statement.create(
            "INSERT INTO mcp_relay_servers (id, org_id, name, transport, url, credential_secret_id, " +
                "credential_header, stdio, sharing, version, created_at) VALUES (:id, :org_id, :name, :transport, " +
                ":url, :credential, :header, :stdio, :sharing, :version, :created_at) ON CONFLICT (org_id, name) DO " +
                "NOTHING",
        ).bindServer(server),
    ).getOrThrow() == 1L

    override suspend fun updateServer(server: McpServer): McpServer? {
        val updated = driver.executeInOrg(
            server.orgId,
            Statement.create(
                "UPDATE mcp_relay_servers SET name = :name, transport = :transport, url = :url, " +
                    "credential_secret_id = :credential, credential_header = :header, stdio = :stdio, " +
                    "sharing = :sharing, version = :version " +
                    "WHERE org_id = :org_id AND id = :id",
            ).bindServer(server),
        ).getOrThrow()
        return if (updated == 1L) server else null
    }

    override suspend fun deleteServer(orgId: Uuid, id: Uuid): Boolean = driver.executeInOrg(
        orgId,
        Statement.create(
            "DELETE FROM mcp_relay_servers WHERE org_id = :org_id AND id = :id",
        ).bind("org_id", orgId).bind("id", id),
    ).getOrThrow() == 1L

    override suspend fun getServer(orgId: Uuid, id: Uuid): McpServer? = driver.fetchInOrg(
        orgId,
        Statement.create("SELECT $SERVER_COLUMNS FROM mcp_relay_servers s WHERE s.org_id = :org_id AND s.id = :id")
            .bind("org_id", orgId).bind("id", id),
    ).getOrThrow().firstOrNull()?.toServer()

    override suspend fun listServers(orgId: Uuid, limit: Int): List<McpServer> = driver.fetchInOrg(
        orgId,
        Statement.create(
            "SELECT $SERVER_COLUMNS FROM mcp_relay_servers s WHERE s.org_id = :org_id ORDER BY s.name LIMIT :limit",
        )
            .bind("org_id", orgId).bind("limit", limit),
    ).getOrThrow().map { it.toServer() }

    override suspend fun putAssignment(assignment: McpAssignment) {
        driver.executeInOrg(
            assignment.orgId,
            Statement.create(
                "INSERT INTO mcp_relay_assignments (org_id, server_id, member_id, tools, credential_secret_id, " +
                    "assigned_at) VALUES (:org_id, :server_id, :member_id, :tools, :credential, :assigned_at) ON " +
                    "CONFLICT (server_id, member_id) DO UPDATE SET tools = EXCLUDED.tools, credential_secret_id = " +
                    "EXCLUDED.credential_secret_id, assigned_at = EXCLUDED.assigned_at",
            )
                .bind("org_id", assignment.orgId)
                .bind("server_id", assignment.serverId)
                .bindNullable("member_id", assignment.memberId, Uuid::class)
                .bindNullable("tools", assignment.policy.tools?.let { json.encodeToString(TOOLS, it) }, String::class)
                .bindNullable("credential", assignment.credentialSecretId, Uuid::class)
                .bind("assigned_at", assignment.assignedAt),
        ).getOrThrow()
    }

    override suspend fun deleteAssignment(orgId: Uuid, serverId: Uuid, memberId: Uuid?): Boolean {
        val member = if (memberId == null) "member_id IS NULL" else "member_id = :member_id"
        val statement = Statement.create(
            "DELETE FROM mcp_relay_assignments WHERE org_id = :org_id AND server_id = :server_id AND $member",
        )
            .bind("org_id", orgId).bind("server_id", serverId)
        if (memberId != null) statement.bind("member_id", memberId)
        return driver.executeInOrg(orgId, statement).getOrThrow() == 1L
    }

    override suspend fun listAssignments(orgId: Uuid, serverId: Uuid): List<McpAssignment> = driver.fetchInOrg(
        orgId,
        Statement.create(
            "SELECT $ASSIGNMENT_COLUMNS FROM mcp_relay_assignments a WHERE a.org_id = :org_id AND a.server_id = " +
                ":server_id ORDER BY a.member_id NULLS FIRST LIMIT 10000",
        ).bind("org_id", orgId).bind("server_id", serverId),
    ).getOrThrow().map { it.toAssignment() }

    override suspend fun effectiveServers(orgId: Uuid, memberId: Uuid, limit: Int): List<EffectiveServer> =
        driver.fetchInOrg(
            orgId,
            Statement.create(
                "SELECT DISTINCT ON (s.name) $SERVER_COLUMNS, $ASSIGNMENT_COLUMNS FROM mcp_relay_servers s JOIN " +
                    "mcp_relay_assignments a ON a.server_id = s.id AND a.org_id = s.org_id WHERE s.org_id = :org_id " +
                    "AND (a.member_id = :member_id OR a.member_id IS NULL) ORDER BY s.name, a.member_id NULLS LAST " +
                    "LIMIT :limit",
            ).bind("org_id", orgId).bind("member_id", memberId).bind("limit", limit),
        ).getOrThrow().map { EffectiveServer(it.toServer(), it.toAssignment()) }

    override suspend fun effectiveServer(orgId: Uuid, memberId: Uuid, name: String): EffectiveServer? =
        driver.fetchInOrg(
            orgId,
            Statement.create(
                "SELECT $SERVER_COLUMNS, $ASSIGNMENT_COLUMNS FROM mcp_relay_servers s JOIN mcp_relay_assignments a " +
                    "ON a.server_id = s.id AND a.org_id = s.org_id WHERE s.org_id = :org_id AND s.name = :name AND " +
                    "(a.member_id = :member_id OR a.member_id IS NULL) ORDER BY a.member_id NULLS LAST LIMIT 1",
            ).bind("org_id", orgId).bind("name", name).bind("member_id", memberId),
        ).getOrThrow().firstOrNull()?.let { EffectiveServer(it.toServer(), it.toAssignment()) }

    override suspend fun createPolicy(policy: ArgumentPolicy) {
        driver.executeInOrg(
            policy.orgId,
            Statement.create(
                "INSERT INTO mcp_relay_policies (id, org_id, server_id, tool, conditions, effect, message, " +
                    "created_at) VALUES (:id, :org_id, :server_id, :tool, :conditions, :effect, :message, :created_at)",
            )
                .bind("id", policy.id)
                .bind("org_id", policy.orgId)
                .bind("server_id", policy.serverId)
                .bind("tool", policy.tool)
                .bind(
                    "conditions",
                    json.encodeToString(
                        CONDITIONS,
                        policy.conditions.map {
                            StoredCondition(it.path, it.operator.name, it.value)
                        },
                    ),
                )
                .bind("effect", policy.effect.name)
                .bind("message", policy.message)
                .bind("created_at", policy.createdAt),
        ).getOrThrow()
    }

    override suspend fun deletePolicy(orgId: Uuid, serverId: Uuid, id: Uuid): Boolean = driver.executeInOrg(
        orgId,
        Statement.create(
            "DELETE FROM mcp_relay_policies WHERE org_id = :org_id AND server_id = :server_id AND id = :id",
        )
            .bind("org_id", orgId).bind("server_id", serverId).bind("id", id),
    ).getOrThrow() == 1L

    override suspend fun listPolicies(orgId: Uuid, serverId: Uuid): List<ArgumentPolicy> = driver.fetchInOrg(
        orgId,
        Statement.create(
            "SELECT id, org_id, server_id, tool, conditions, effect, message, created_at FROM mcp_relay_policies " +
                "WHERE org_id = :org_id AND server_id = :server_id ORDER BY created_at, id LIMIT 1000",
        ).bind("org_id", orgId).bind("server_id", serverId),
    ).getOrThrow().map { row ->
        ArgumentPolicy(
            id = row.get("id").asUuid(),
            orgId = row.get("org_id").asUuid(),
            serverId = row.get("server_id").asUuid(),
            tool = row.get("tool").asString(),
            conditions = json.decodeFromString(CONDITIONS, row.get("conditions").asString()).map {
                ArgumentCondition(it.path, ConditionOperator.valueOf(it.operator), it.value)
            },
            effect = PolicyEffect.valueOf(row.get("effect").asString()),
            message = row.get("message").asString(),
            createdAt = row.get("created_at").asInstant(),
        )
    }

    override suspend fun settings(orgId: Uuid): McpSettings = driver.fetchInOrg(
        orgId,
        Statement.create("SELECT agent_prefix FROM mcp_relay_settings WHERE org_id = :org_id").bind("org_id", orgId),
    ).getOrThrow().firstOrNull()?.let { McpSettings(it.get("agent_prefix").asString()) }
        ?: McpSettings(McpSettings.DEFAULT_PREFIX)

    override suspend fun putSettings(orgId: Uuid, settings: McpSettings) {
        driver.executeInOrg(
            orgId,
            Statement.create(
                "INSERT INTO mcp_relay_settings (org_id, agent_prefix) VALUES (:org_id, :prefix) ON CONFLICT " +
                    "(org_id) DO UPDATE SET agent_prefix = EXCLUDED.agent_prefix",
            ).bind("org_id", orgId).bind("prefix", settings.agentPrefix),
        ).getOrThrow()
    }

    override suspend fun record(call: McpCall) {
        driver.executeInOrg(
            call.orgId,
            Statement.create(
                "INSERT INTO mcp_relay_calls (id, org_id, member_id, server_id, server_name, operation, tool, " +
                    "outcome, started_at, duration_ms, response_bytes, policy_id) VALUES (:id, :org_id, :member_id, " +
                    ":server_id, :server_name, :operation, :tool, :outcome, :started_at, :duration_ms, " +
                    ":response_bytes, :policy_id)",
            )
                .bind("id", call.id)
                .bind("org_id", call.orgId)
                .bind("member_id", call.memberId)
                .bind("server_id", call.serverId)
                .bind("server_name", call.serverName)
                .bind("operation", call.operation.name)
                .bindNullable("tool", call.tool, String::class)
                .bind("outcome", call.outcome.name)
                .bind("started_at", call.startedAt)
                .bind("duration_ms", call.durationMillis)
                .bind("response_bytes", call.responseBytes)
                .bindNullable("policy_id", call.policyId, Uuid::class),
        ).getOrThrow()
    }

    override suspend fun listCalls(
        orgId: Uuid,
        before: Instant?,
        limit: Int,
        server: String?,
        memberId: Uuid?,
    ): List<McpCall> {
        val filters = (if (before == null) "" else " AND started_at < :before") +
            (if (server == null) "" else " AND server_name = :server") +
            (if (memberId == null) "" else " AND member_id = :member_id")
        val statement = Statement.create(
            "SELECT id, org_id, member_id, server_id, server_name, operation, tool, outcome, started_at, " +
                "duration_ms, response_bytes, policy_id FROM mcp_relay_calls WHERE org_id = :org_id$filters ORDER BY " +
                "started_at DESC, id LIMIT :limit",
        ).bind("org_id", orgId).bind("limit", limit)
        if (before != null) statement.bind("before", before)
        if (server != null) statement.bind("server", server)
        if (memberId != null) statement.bind("member_id", memberId)
        return driver.fetchInOrg(orgId, statement).getOrThrow().map { it.toCall() }
    }

    private fun ResultSet.Row.toCall(): McpCall = let { row ->
        McpCall(
            id = row.get("id").asUuid(),
            orgId = row.get("org_id").asUuid(),
            memberId = row.get("member_id").asUuid(),
            serverId = row.get("server_id").asUuid(),
            serverName = row.get("server_name").asString(),
            operation = McpOperation.valueOf(row.get("operation").asString()),
            tool = row.get("tool").asStringOrNull(),
            outcome = McpOutcome.valueOf(row.get("outcome").asString()),
            startedAt = row.get("started_at").asInstant(),
            durationMillis = row.get("duration_ms").asLong(),
            responseBytes = row.get("response_bytes").asLong(),
            policyId = row.get("policy_id").asUuidOrNull(),
        )
    }

    override suspend fun usage(orgId: Uuid, since: Instant, memberId: Uuid?): List<McpUsage> {
        val member = if (memberId == null) "" else " AND member_id = :member_id"
        val statement = Statement.create(
            "SELECT member_id, server_name, tool, COUNT(*) FILTER (WHERE operation = 'CALL') AS calls, COUNT(*) " +
                "FILTER (WHERE operation = 'LIST') AS lists, COUNT(*) FILTER (WHERE outcome IN ('UPSTREAM_ERROR', " +
                "'TIMEOUT', 'TOO_LARGE')) AS errors, COUNT(*) FILTER (WHERE outcome IN ('DENIED', " +
                "'APPROVAL_REQUIRED')) AS denied, COALESCE(SUM(response_bytes), 0) AS bytes, " +
                "COALESCE(SUM(duration_ms), 0) AS duration FROM mcp_relay_calls WHERE org_id = :org_id AND " +
                "started_at >= :since$member GROUP BY member_id, server_name, tool ORDER BY server_name, member_id, " +
                "tool LIMIT 10000",
        ).bind("org_id", orgId).bind("since", since)
        if (memberId != null) statement.bind("member_id", memberId)
        return driver.fetchInOrg(orgId, statement).getOrThrow().map { row ->
            McpUsage(
                memberId = row.get("member_id").asUuid(),
                serverName = row.get("server_name").asString(),
                calls = row.get("calls").asLong(),
                lists = row.get("lists").asLong(),
                errors = row.get("errors").asLong(),
                denied = row.get("denied").asLong(),
                bytes = row.get("bytes").asLong(),
                durationMillis = row.get("duration").asLong(),
                tool = row.get("tool").asStringOrNull(),
            )
        }
    }

    override suspend fun purgeCalls(before: Instant): Long = driver.transaction {
        // The one operation across orgs, named by its own policy (McpRelayMigrations version 2): old calls only.
        execute("SET LOCAL ROLE $TENANT_ROLE").getOrThrow()
        execute("SELECT set_config('$RETENTION_SETTING', 'on', true)").getOrThrow()
        execute(
            Statement.create("DELETE FROM mcp_relay_calls WHERE started_at < :before").bind("before", before),
        ).getOrThrow()
    }

    private fun Statement.bindServer(server: McpServer): Statement = bind("id", server.id)
        .bind("org_id", server.orgId)
        .bind("name", server.name)
        .bind("transport", server.transport.name)
        .bindNullable("url", server.url, String::class)
        .bindNullable("credential", server.credentialSecretId, Uuid::class)
        .bindNullable("header", server.credentialHeader, String::class)
        .bindNullable(
            "stdio",
            server.stdio?.let {
                json.encodeToString(StoredStdio.serializer(), it.toStored())
            },
            String::class,
        )
        .bind("sharing", server.sharing.name)
        .bind("version", server.version)
        .bind("created_at", server.createdAt)

    private fun Statement.bindNullable(name: String, value: Any?, type: KClass<*>): Statement =
        if (value == null) bindNull(name, type) else bind(name, value)

    private fun ResultSet.Row.toServer() = McpServer(
        id = get("s_id").asUuid(),
        orgId = get("s_org_id").asUuid(),
        name = get("s_name").asString(),
        transport = McpTransport.valueOf(get("s_transport").asString()),
        url = get("s_url").asStringOrNull(),
        credentialSecretId = get("s_credential").asUuidOrNull(),
        credentialHeader = get("s_header").asStringOrNull(),
        stdio = get("s_stdio").asStringOrNull()?.let { json.decodeFromString(StoredStdio.serializer(), it).toSpec() },
        sharing = McpSharing.valueOf(get("s_sharing").asString()),
        version = get("s_version").asInt(),
        createdAt = get("s_created_at").asInstant(),
    )

    private fun ResultSet.Row.toAssignment() = McpAssignment(
        orgId = get("a_org_id").asUuid(),
        serverId = get("a_server_id").asUuid(),
        memberId = get("a_member_id").asUuidOrNull(),
        policy = ToolPolicy(get("a_tools").asStringOrNull()?.let { json.decodeFromString(TOOLS, it) }),
        credentialSecretId = get("a_credential").asUuidOrNull(),
        assignedAt = get("a_assigned_at").asInstant(),
    )

    @Serializable
    private data class StoredStdio(
        val launcher: String,
        val packageName: String,
        val args: List<String>,
        val env: Map<String, String>,
        val secretEnv: Map<String, String>,
    ) {
        fun toSpec() = StdioSpec(
            launcher = StdioLauncher.valueOf(launcher),
            packageName = packageName,
            args = args,
            env = env,
            secretEnv = secretEnv.mapValues { Uuid.parse(it.value) },
        )
    }

    private fun StdioSpec.toStored() = StoredStdio(
        launcher.name,
        packageName,
        args,
        env,
        secretEnv.mapValues {
            it.value.toString()
        },
    )

    @Serializable
    private data class StoredCondition(val path: String, val operator: String, val value: String? = null)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val TOOLS = SetSerializer(String.serializer())
        val CONDITIONS = ListSerializer(StoredCondition.serializer())
        const val SERVER_COLUMNS =
            "s.id AS s_id, s.org_id AS s_org_id, s.name AS s_name, s.transport AS s_transport, s.url AS s_url, " +
                "s.credential_secret_id AS s_credential, s.credential_header AS s_header, s.stdio AS s_stdio, " +
                "s.sharing AS s_sharing, s.version AS s_version, s.created_at AS s_created_at"
        const val ASSIGNMENT_COLUMNS =
            "a.org_id AS a_org_id, a.server_id AS a_server_id, a.member_id AS a_member_id, a.tools AS a_tools, " +
                "a.credential_secret_id AS a_credential, a.assigned_at AS a_assigned_at"
    }
}
