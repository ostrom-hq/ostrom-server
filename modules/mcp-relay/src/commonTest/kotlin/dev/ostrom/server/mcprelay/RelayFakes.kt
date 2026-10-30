package dev.ostrom.server.mcprelay

import dev.ostrom.server.mcprelay.domain.ArgumentPolicy
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
import dev.ostrom.server.mcprelay.domain.McpUpstream
import dev.ostrom.server.mcprelay.domain.McpUsage
import dev.ostrom.server.mcprelay.domain.ToolPolicy
import dev.ostrom.server.mcprelay.domain.UpstreamResult
import dev.ostrom.server.mcprelay.domain.UpstreamTarget
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.MemberRole
import dev.ostrom.server.secrets.domain.RevealSecretsUseCase
import dev.ostrom.server.secrets.domain.SecretValue
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Ids and callers shared by the relay tests. */
object Relay {
    val orgA: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    val orgB: Uuid = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    val adminA = Caller(orgA, Uuid.parse("0000000a-0000-0000-0000-0000000000a1"), MemberRole.ADMIN)
    val memberA = Caller(orgA, Uuid.parse("0000000a-0000-0000-0000-0000000000b1"), MemberRole.MEMBER)
    val otherA = Caller(orgA, Uuid.parse("0000000a-0000-0000-0000-0000000000c1"), MemberRole.MEMBER)
    val adminB = Caller(orgB, Uuid.parse("0000000b-0000-0000-0000-0000000000a1"), MemberRole.OWNER)
    val epoch: Instant = Instant.parse("2026-10-30T00:00:00Z")

    fun server(
        name: String = "grafana",
        org: Uuid = orgA,
        transport: McpTransport = McpTransport.HTTP,
        credential: Uuid? = null,
        sharing: McpSharing = McpSharing.SHARED,
    ) = McpServer(
        id = Uuid.random(),
        orgId = org,
        name = name,
        transport = transport,
        url = if (transport == McpTransport.HTTP) "https://mcp.example.com/$name" else null,
        credentialSecretId = credential,
        credentialHeader = null,
        stdio = null,
        sharing = sharing,
        version = 1,
        createdAt = epoch,
    )
}

/** A clock that moves only when told. */
class TestClock(var now: Instant = Relay.epoch) : Clock {
    override fun now(): Instant = now

    fun advance(by: Duration = 5.milliseconds) {
        now += by
    }
}

/** Secrets by id, revealed as is. */
class FakeReveal(val values: MutableMap<Uuid, String> = mutableMapOf()) : RevealSecretsUseCase {
    var failing = false

    override suspend fun invoke(orgId: Uuid, ids: Collection<Uuid>): Map<Uuid, SecretValue> {
        if (failing) error("key changed")
        return ids.mapNotNull { id -> values[id]?.let { id to SecretValue(it) } }.toMap()
    }

    override suspend fun byName(orgId: Uuid, names: Collection<String>): Map<String, SecretValue> = emptyMap()
}

/** An upstream answering what the test sets, recording what it received. */
class FakeUpstream : McpUpstream {
    var tools: UpstreamResult<List<JsonObject>> = UpstreamResult.Ok(emptyList(), 0)
    var call: UpstreamResult<JsonObject> = UpstreamResult.Ok(JsonObject(emptyMap()), 0)
    var onCall: suspend () -> Unit = {}
    val targets = mutableListOf<UpstreamTarget>()
    val calls = mutableListOf<Pair<String, JsonObject>>()

    override suspend fun listTools(target: UpstreamTarget, deadline: Duration): UpstreamResult<List<JsonObject>> {
        targets += target
        return tools
    }

    override suspend fun callTool(target: UpstreamTarget, tool: String, arguments: JsonObject, deadline: Duration): UpstreamResult<JsonObject> {
        targets += target
        calls += tool to arguments
        onCall()
        return call
    }
}

/** [McpRelayRepository] in memory, with the same visibility rules as the SQL one. */
class InMemoryRelayRepository : McpRelayRepository {
    val servers = mutableListOf<McpServer>()
    val assignments = mutableListOf<McpAssignment>()
    val policies = mutableListOf<ArgumentPolicy>()
    val settings = mutableMapOf<Uuid, McpSettings>()
    val journal = mutableListOf<McpCall>()
    var failJournal = false

    override suspend fun createServer(server: McpServer): Boolean {
        if (servers.any { it.orgId == server.orgId && it.name == server.name }) return false
        servers += server
        return true
    }

    override suspend fun updateServer(server: McpServer): McpServer? {
        val index = servers.indexOfFirst { it.orgId == server.orgId && it.id == server.id }
        if (index < 0) return null
        servers[index] = server
        return server
    }

    override suspend fun deleteServer(orgId: Uuid, id: Uuid): Boolean {
        assignments.removeAll { it.serverId == id }
        return servers.removeAll { it.orgId == orgId && it.id == id }
    }

    override suspend fun getServer(orgId: Uuid, id: Uuid): McpServer? = servers.firstOrNull { it.orgId == orgId && it.id == id }

    override suspend fun listServers(orgId: Uuid, limit: Int): List<McpServer> = servers.filter { it.orgId == orgId }.sortedBy { it.name }.take(limit)

    override suspend fun putAssignment(assignment: McpAssignment) {
        assignments.removeAll { it.serverId == assignment.serverId && it.memberId == assignment.memberId }
        assignments += assignment
    }

    override suspend fun deleteAssignment(orgId: Uuid, serverId: Uuid, memberId: Uuid?): Boolean =
        assignments.removeAll { it.orgId == orgId && it.serverId == serverId && it.memberId == memberId }

    override suspend fun listAssignments(orgId: Uuid, serverId: Uuid): List<McpAssignment> =
        assignments.filter { it.orgId == orgId && it.serverId == serverId }

    override suspend fun effectiveServers(orgId: Uuid, memberId: Uuid, limit: Int): List<EffectiveServer> =
        servers.filter { it.orgId == orgId }.sortedBy { it.name }.mapNotNull { effective(it, memberId) }.take(limit)

    override suspend fun effectiveServer(orgId: Uuid, memberId: Uuid, name: String): EffectiveServer? =
        servers.firstOrNull { it.orgId == orgId && it.name == name }?.let { effective(it, memberId) }

    private fun effective(server: McpServer, memberId: Uuid): EffectiveServer? {
        val own = assignments.firstOrNull { it.serverId == server.id && it.memberId == memberId }
        val all = assignments.firstOrNull { it.serverId == server.id && it.memberId == null }
        return (own ?: all)?.let { EffectiveServer(server, it) }
    }

    override suspend fun createPolicy(policy: ArgumentPolicy) {
        policies += policy
    }

    override suspend fun deletePolicy(orgId: Uuid, serverId: Uuid, id: Uuid): Boolean =
        policies.removeAll { it.orgId == orgId && it.serverId == serverId && it.id == id }

    override suspend fun listPolicies(orgId: Uuid, serverId: Uuid): List<ArgumentPolicy> =
        policies.filter { it.orgId == orgId && it.serverId == serverId }

    override suspend fun settings(orgId: Uuid): McpSettings = settings[orgId] ?: McpSettings(McpSettings.DEFAULT_PREFIX)

    override suspend fun putSettings(orgId: Uuid, settings: McpSettings) {
        this.settings[orgId] = settings
    }

    override suspend fun record(call: McpCall) {
        if (failJournal) error("journal down")
        journal += call
    }

    override suspend fun listCalls(orgId: Uuid, before: Instant?, limit: Int, server: String?, memberId: Uuid?): List<McpCall> =
        journal.filter {
            it.orgId == orgId && (before == null || it.startedAt < before) && (server == null || it.serverName == server) &&
                (memberId == null || it.memberId == memberId)
        }.sortedByDescending { it.startedAt }.take(limit)

    override suspend fun usage(orgId: Uuid, since: Instant, memberId: Uuid?): List<McpUsage> =
        journal.filter { it.orgId == orgId && it.startedAt >= since && (memberId == null || it.memberId == memberId) }
            .groupBy { Triple(it.memberId, it.serverName, it.tool) }
            .map { (key, calls) ->
                McpUsage(
                    memberId = key.first,
                    serverName = key.second,
                    tool = key.third,
                    calls = calls.count { it.operation == McpOperation.CALL }.toLong(),
                    lists = calls.count { it.operation == McpOperation.LIST }.toLong(),
                    errors = calls.count { it.outcome in setOf(McpOutcome.UPSTREAM_ERROR, McpOutcome.TIMEOUT, McpOutcome.TOO_LARGE) }.toLong(),
                    denied = calls.count { it.outcome in setOf(McpOutcome.DENIED, McpOutcome.APPROVAL_REQUIRED) }.toLong(),
                    bytes = calls.sumOf { it.responseBytes },
                    durationMillis = calls.sumOf { it.durationMillis },
                )
            }

    override suspend fun purgeCalls(before: Instant): Long {
        val count = journal.count { it.startedAt < before }
        journal.removeAll { it.startedAt < before }
        return count.toLong()
    }

    /** Assigns [server] to [member] (null: the whole org). */
    fun assign(server: McpServer, member: Uuid?, tools: Set<String>? = null, credential: Uuid? = null) {
        assignments += McpAssignment(server.orgId, server.id, member, ToolPolicy(tools), credential, Relay.epoch)
    }
}
