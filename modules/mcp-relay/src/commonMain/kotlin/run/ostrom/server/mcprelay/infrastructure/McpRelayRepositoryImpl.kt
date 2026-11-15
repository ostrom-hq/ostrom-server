// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay.infrastructure

import run.ostrom.server.kernel.database.InstantColumnType
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.asBigint
import run.ostrom.server.kernel.database.countWhere
import run.ostrom.server.kernel.database.lockKeyUntilCommit
import run.ostrom.server.kernel.database.rowLess
import run.ostrom.server.kernel.events.OrgEventDraft
import run.ostrom.server.kernel.events.OrgEventPublisher
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.mcprelay.domain.ArgumentCondition
import run.ostrom.server.mcprelay.domain.ArgumentPolicy
import run.ostrom.server.mcprelay.domain.ConditionOperator
import run.ostrom.server.mcprelay.domain.EffectiveServer
import run.ostrom.server.mcprelay.domain.HostedAssignment
import run.ostrom.server.mcprelay.domain.HostedProcessChange
import run.ostrom.server.mcprelay.domain.HostedProcessEvent
import run.ostrom.server.mcprelay.domain.HostedProcessRecord
import run.ostrom.server.mcprelay.domain.McpAssignment
import run.ostrom.server.mcprelay.domain.McpCall
import run.ostrom.server.mcprelay.domain.McpCallCursor
import run.ostrom.server.mcprelay.domain.McpOperation
import run.ostrom.server.mcprelay.domain.McpOutcome
import run.ostrom.server.mcprelay.domain.McpRelayRepository
import run.ostrom.server.mcprelay.domain.McpServer
import run.ostrom.server.mcprelay.domain.McpServerLimitException
import run.ostrom.server.mcprelay.domain.McpSettings
import run.ostrom.server.mcprelay.domain.McpSharing
import run.ostrom.server.mcprelay.domain.McpTransport
import run.ostrom.server.mcprelay.domain.McpUsage
import run.ostrom.server.mcprelay.domain.PersonalServerOutcome
import run.ostrom.server.mcprelay.domain.PolicyEffect
import run.ostrom.server.mcprelay.domain.StdioLauncher
import run.ostrom.server.mcprelay.domain.StdioSpec
import run.ostrom.server.mcprelay.domain.ToolPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.compoundAnd
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.longLiteral
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import org.jetbrains.exposed.v1.jdbc.upsert
import org.jetbrains.exposed.v1.jdbc.upsertReturning
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [McpRelayRepository] on PostgreSQL through Exposed (tables of [McpRelayMigrations]), every statement in a
 * transaction scoped to one organization (`inOrg`: the tenant role, `ostrom.org_id` set) with its own `org_id`
 * filter, except the two reads across organizations, which go through the functions of `ostrom_router` as the serving
 * role. A stdio command, an allowlist and policy conditions are stored as JSON text. A change of a hosted process's
 * state publishes its event in the transaction that wrote it.
 *
 * @param database The serving role's pool.
 * @param events The organization's event feed.
 */
@Suppress("TooManyFunctions") // Implements the one storage port of the module.
class McpRelayRepositoryImpl(
    private val database: JdbcDatabase,
    private val events: OrgEventPublisher,
) : McpRelayRepository {
    // `ON CONFLICT (org_id, name) DO UPDATE … WHERE FALSE`: the sqlx4k `DO NOTHING` on that target alone, so any other
    // conflict (the id) still fails loudly. A server left alone returns no row.
    private fun JdbcTransaction.insertServer(server: McpServer, owner: Uuid?): Boolean {
        val s = McpRelayServers
        val stdio = server.stdio
        return s.upsertReturning(
            s.orgId, s.name,
            returning = listOf(s.id),
            onUpdate = { it[s.id] = s.id },
            where = { Op.FALSE },
        ) {
            it[s.id] = server.id
            it[s.orgId] = server.orgId
            it[s.name] = server.name
            it[s.transport] = server.transport.name
            it[s.url] = server.url
            it[s.credentialSecretId] = server.credentialSecretId
            it[s.credentialHeader] = server.credentialHeader
            it[s.stdio] = if (stdio == null) null else json.encodeToString(StoredStdio.serializer(), stdio.toStored())
            it[s.sharing] = server.sharing.name
            it[s.version] = server.version
            it[s.createdAt] = server.createdAt
            it[s.ownerMemberId] = owner
        }.any()
    }

    override suspend fun createServer(server: McpServer): Boolean =
        database.inOrg(server.orgId) { insertServer(server, null) }

    override suspend fun createPersonalServer(server: McpServer, limit: Int): PersonalServerOutcome {
        val owner = requireNotNull(server.ownerMemberId) { "A personal server has an owner." }
        return database.inOrg(server.orgId) {
            lockKeyUntilCommit("mcp-personal:${server.orgId}:$owner")
            val s = McpRelayServers
            val owned = s.id.count()
            val ownedCount = s.select(owned).where { (s.orgId eq server.orgId) and (s.ownerMemberId eq owner) }
                .single()[owned]
            when {
                ownedCount >= limit -> PersonalServerOutcome.LIMIT
                !insertServer(server, owner) -> PersonalServerOutcome.NAME_TAKEN
                else -> {
                    val a = McpRelayAssignments
                    a.insert {
                        it[a.orgId] = server.orgId
                        it[a.serverId] = server.id
                        it[a.memberId] = owner
                        it[a.tools] = null
                        it[a.credentialSecretId] = null
                        it[a.assignedAt] = server.createdAt
                    }
                    PersonalServerOutcome.CREATED
                }
            }
        }
    }

    override suspend fun updateServer(server: McpServer): McpServer? {
        val updated = database.inOrg(server.orgId) {
            val s = McpRelayServers
            val stdio = server.stdio
            val owned = s.ownerMemberId.isOrNull(server.ownerMemberId)
            s.update({ (s.orgId eq server.orgId) and (s.id eq server.id) and owned }) {
                it[s.name] = server.name
                it[s.transport] = server.transport.name
                it[s.url] = server.url
                it[s.credentialSecretId] = server.credentialSecretId
                it[s.credentialHeader] = server.credentialHeader
                it[s.stdio] =
                    if (stdio == null) null else json.encodeToString(StoredStdio.serializer(), stdio.toStored())
                it[s.sharing] = server.sharing.name
                it[s.version] = server.version
            }
        }
        return if (updated == 1) server else null
    }

    override suspend fun deleteServer(orgId: Uuid, id: Uuid, ownerMemberId: Uuid?): Boolean =
        database.inOrg(orgId) {
            val s = McpRelayServers
            // A team's server is deleted whoever owns it; a member's own by its owner only.
            val owned = if (ownerMemberId == null) Op.TRUE else s.ownerMemberId eq ownerMemberId
            s.deleteWhere { (s.orgId eq orgId) and (s.id eq id) and owned }
        } == 1

    override suspend fun getServerByName(orgId: Uuid, name: String): McpServer? = database.inOrg(orgId) {
        val s = McpRelayServers
        val row = s.select(s.columns).where { (s.orgId eq orgId) and (s.name eq name) }.firstOrNull()
        if (row == null) null else row.toServer()
    }

    override suspend fun listPersonalServers(orgId: Uuid, ownerMemberId: Uuid?, limit: Int): List<McpServer> =
        database.inOrg(orgId) {
            val s = McpRelayServers
            val owner = if (ownerMemberId == null) s.ownerMemberId.isNotNull() else s.ownerMemberId eq ownerMemberId
            s.select(s.columns).where { (s.orgId eq orgId) and owner }
                .orderBy(s.ownerMemberId to SortOrder.ASC, s.name to SortOrder.ASC)
                .limit(limit)
                .map { it.toServer() }
        }

    override suspend fun promoteServer(orgId: Uuid, id: Uuid, version: Int, limit: Int): McpServer? =
        database.inOrg(orgId) {
            lockKeyUntilCommit("mcp-team:$orgId")
            val s = McpRelayServers
            val teams = s.id.count()
            val team = s.select(teams).where { (s.orgId eq orgId) and s.ownerMemberId.isNull() }.single()[teams]
            if (team >= limit) throw McpServerLimitException("An organization has at most $limit team MCP servers.")
            val promoted = s.updateReturning(
                s.columns,
                where = {
                    (s.orgId eq orgId) and (s.id eq id) and (s.version eq version) and s.ownerMemberId.isNotNull()
                },
            ) {
                it[s.ownerMemberId] = null
                it[s.version] = s.version + 1
            }.firstOrNull()
            if (promoted == null) null else promoted.toServer()
        }

    override suspend fun getServer(orgId: Uuid, id: Uuid): McpServer? = database.inOrg(orgId) {
        val s = McpRelayServers
        val row = s.select(s.columns).where { (s.orgId eq orgId) and (s.id eq id) }.firstOrNull()
        if (row == null) null else row.toServer()
    }

    override suspend fun listServers(orgId: Uuid, limit: Int): List<McpServer> = database.inOrg(orgId) {
        val s = McpRelayServers
        s.select(s.columns).where { (s.orgId eq orgId) and s.ownerMemberId.isNull() }
            .orderBy(s.name to SortOrder.ASC)
            .limit(limit)
            .map { it.toServer() }
    }

    override suspend fun putAssignment(assignment: McpAssignment) {
        database.inOrg(assignment.orgId) {
            val a = McpRelayAssignments
            val tools = assignment.policy.tools
            // `ON CONFLICT (server_id, member_id)`: the unique index that treats a null member as one value.
            a.upsert(
                a.serverId, a.memberId,
                onUpdate = {
                    it[a.tools] = insertValue(a.tools)
                    it[a.credentialSecretId] = insertValue(a.credentialSecretId)
                    it[a.assignedAt] = insertValue(a.assignedAt)
                },
            ) {
                it[a.orgId] = assignment.orgId
                it[a.serverId] = assignment.serverId
                it[a.memberId] = assignment.memberId
                it[a.tools] = if (tools == null) null else json.encodeToString(toolSet, tools)
                it[a.credentialSecretId] = assignment.credentialSecretId
                it[a.assignedAt] = assignment.assignedAt
            }
        }
    }

    override suspend fun deleteAssignment(orgId: Uuid, serverId: Uuid, memberId: Uuid?): Boolean =
        database.inOrg(orgId) {
            val a = McpRelayAssignments
            a.deleteWhere { (a.orgId eq orgId) and (a.serverId eq serverId) and a.memberId.isOrNull(memberId) }
        } == 1

    override suspend fun listAssignments(orgId: Uuid, serverId: Uuid): List<McpAssignment> = database.inOrg(orgId) {
        val a = McpRelayAssignments
        a.select(a.columns).where { (a.orgId eq orgId) and (a.serverId eq serverId) }
            .orderBy(a.memberId to SortOrder.ASC_NULLS_FIRST)
            .limit(LISTED_ASSIGNMENTS)
            .map { it.toAssignment() }
    }

    override suspend fun effectiveServers(orgId: Uuid, memberId: Uuid, limit: Int): List<EffectiveServer> =
        database.inOrg(orgId) {
            val s = McpRelayServers
            val a = McpRelayAssignments
            serversWithAssignments().select(s.columns + a.columns)
                .where { (s.orgId eq orgId) and ((a.memberId eq memberId) or a.memberId.isNull()) }
                .withDistinctOn(s.name)
                .orderBy(s.name to SortOrder.ASC, a.memberId to SortOrder.ASC_NULLS_LAST)
                .limit(limit)
                .map { EffectiveServer(it.toServer(), it.toAssignment()) }
        }

    override suspend fun effectiveServer(orgId: Uuid, memberId: Uuid, name: String): EffectiveServer? =
        database.inOrg(orgId) {
            val s = McpRelayServers
            val a = McpRelayAssignments
            val row = serversWithAssignments().select(s.columns + a.columns)
                .where {
                    (s.orgId eq orgId) and (s.name eq name) and ((a.memberId eq memberId) or a.memberId.isNull())
                }
                .orderBy(a.memberId to SortOrder.ASC_NULLS_LAST)
                .limit(1)
                .firstOrNull()
            if (row == null) null else EffectiveServer(row.toServer(), row.toAssignment())
        }

    override suspend fun createPolicy(policy: ArgumentPolicy) {
        database.inOrg(policy.orgId) {
            val p = McpRelayPolicies
            p.insert {
                it[p.id] = policy.id
                it[p.orgId] = policy.orgId
                it[p.serverId] = policy.serverId
                it[p.tool] = policy.tool
                it[p.conditions] = json.encodeToString(
                    conditionList,
                    policy.conditions.map { condition ->
                        StoredCondition(condition.path, condition.operator.name, condition.value)
                    },
                )
                it[p.effect] = policy.effect.name
                it[p.message] = policy.message
                it[p.createdAt] = policy.createdAt
            }
        }
    }

    override suspend fun deletePolicy(orgId: Uuid, serverId: Uuid, id: Uuid): Boolean = database.inOrg(orgId) {
        val p = McpRelayPolicies
        p.deleteWhere { (p.orgId eq orgId) and (p.serverId eq serverId) and (p.id eq id) }
    } == 1

    override suspend fun listPolicies(orgId: Uuid, serverId: Uuid): List<ArgumentPolicy> = database.inOrg(orgId) {
        val p = McpRelayPolicies
        p.select(p.columns).where { (p.orgId eq orgId) and (p.serverId eq serverId) }
            .orderBy(p.createdAt to SortOrder.ASC, p.id to SortOrder.ASC)
            .limit(LISTED_POLICIES)
            .map { row ->
                ArgumentPolicy(
                    id = row[p.id],
                    orgId = row[p.orgId],
                    serverId = row[p.serverId],
                    tool = row[p.tool],
                    conditions = json.decodeFromString(conditionList, row[p.conditions]).map {
                        ArgumentCondition(it.path, ConditionOperator.valueOf(it.operator), it.value)
                    },
                    effect = PolicyEffect.valueOf(row[p.effect]),
                    message = row[p.message],
                    createdAt = row[p.createdAt],
                )
            }
    }

    override suspend fun settings(orgId: Uuid): McpSettings = database.inOrg(orgId) {
        val s = McpRelaySettings
        val row = s.select(s.agentPrefix, s.memberHostedMcp).where { s.orgId eq orgId }.firstOrNull()
        if (row == null) {
            McpSettings(McpSettings.DEFAULT_PREFIX)
        } else {
            McpSettings(row[s.agentPrefix], row[s.memberHostedMcp])
        }
    }

    override suspend fun putSettings(orgId: Uuid, settings: McpSettings) {
        database.inOrg(orgId) {
            val s = McpRelaySettings
            s.upsert {
                it[s.orgId] = orgId
                it[s.agentPrefix] = settings.agentPrefix
                it[s.memberHostedMcp] = settings.memberHostedMcp
            }
        }
    }

    override suspend fun record(call: McpCall) {
        database.inOrg(call.orgId) {
            val c = McpRelayCalls
            c.insert {
                it[c.id] = call.id
                it[c.orgId] = call.orgId
                it[c.memberId] = call.memberId
                it[c.serverId] = call.serverId
                it[c.serverName] = call.serverName
                it[c.operation] = call.operation.name
                it[c.tool] = call.tool
                it[c.outcome] = call.outcome.name
                it[c.startedAt] = call.startedAt
                it[c.durationMs] = call.durationMillis
                it[c.responseBytes] = call.responseBytes
                it[c.policyId] = call.policyId
                it[c.launchId] = call.launchId
            }
        }
    }

    override suspend fun listCalls(
        orgId: Uuid,
        before: McpCallCursor?,
        limit: Int,
        server: String?,
        memberId: Uuid?,
    ): List<McpCall> = database.inOrg(orgId) {
        val c = McpRelayCalls
        val after = when {
            before == null -> null
            before.id == null -> c.startedAt less before.startedAt
            else -> rowLess(
                listOf(c.startedAt, c.id),
                listOf(
                    QueryParameter(before.startedAt, InstantColumnType()),
                    QueryParameter(before.id, UuidColumnType()),
                ),
            )
        }
        c.select(c.columns)
            .where {
                listOfNotNull(
                    c.orgId eq orgId,
                    after,
                    server?.let { c.serverName eq it },
                    memberId?.let { c.memberId eq it },
                ).compoundAnd()
            }
            .orderBy(c.startedAt to SortOrder.DESC, c.id to SortOrder.DESC)
            .limit(limit)
            .map { it.toCall() }
    }

    private fun ResultRow.toCall(): McpCall {
        val c = McpRelayCalls
        return McpCall(
            id = this[c.id],
            orgId = this[c.orgId],
            memberId = this[c.memberId],
            serverId = this[c.serverId],
            serverName = this[c.serverName],
            operation = McpOperation.valueOf(this[c.operation]),
            tool = this[c.tool],
            outcome = McpOutcome.valueOf(this[c.outcome]),
            startedAt = this[c.startedAt],
            durationMillis = this[c.durationMs],
            responseBytes = this[c.responseBytes],
            policyId = this[c.policyId],
            launchId = this[c.launchId],
        )
    }

    override suspend fun usage(orgId: Uuid, since: Instant, memberId: Uuid?): List<McpUsage> = database.inOrg(orgId) {
        val c = McpRelayCalls
        val calls = countWhere(c.operation eq McpOperation.CALL.name)
        val lists = countWhere(c.operation eq McpOperation.LIST.name)
        val errors = countWhere(c.outcome inList ERRORS)
        val denied = countWhere(c.outcome inList DENIALS)
        val bytes = coalesce(c.responseBytes.sum().asBigint(), longLiteral(0))
        val duration = coalesce(c.durationMs.sum().asBigint(), longLiteral(0))
        c.select(c.memberId, c.serverName, c.tool, calls, lists, errors, denied, bytes, duration)
            .where {
                val ofMember = if (memberId == null) Op.TRUE else c.memberId eq memberId
                (c.orgId eq orgId) and (c.startedAt greaterEq since) and ofMember
            }
            .groupBy(c.memberId, c.serverName, c.tool)
            .orderBy(c.serverName to SortOrder.ASC, c.memberId to SortOrder.ASC, c.tool to SortOrder.ASC)
            .limit(LISTED_USAGE)
            .map { row ->
                McpUsage(
                    memberId = row[c.memberId],
                    serverName = row[c.serverName],
                    calls = row[calls],
                    lists = row[lists],
                    errors = row[errors],
                    denied = row[denied],
                    bytes = row[bytes],
                    durationMillis = row[duration],
                    tool = row[c.tool],
                )
            }
    }

    // The one operation across orgs, through the function of McpRelayMigrations version 4: old calls only.
    override suspend fun purgeCalls(before: Instant): Long = database.query {
        val purged = CustomFunction("mcp_relay_purge", LongColumnType(), QueryParameter(before, InstantColumnType()))
        Table.Dual.select(purged).single()[purged]
    }

    override suspend fun hostedAssignments(limit: Int): List<HostedAssignment>? = database.query {
        val h = McpHostedAssignments
        val rows = h.of(limit + 1)
            .select(h.orgId, h.serverId, h.version, h.sharing, h.memberId, h.assignedAt)
            .map { row ->
                HostedAssignment(
                    orgId = row[h.orgId],
                    serverId = row[h.serverId],
                    version = row[h.version],
                    sharing = McpSharing.valueOf(row[h.sharing]),
                    memberId = row[h.memberId],
                    assignedAt = row[h.assignedAt],
                )
            }
        if (rows.size <= limit) rows else null
    }

    override suspend fun orgHostedAssignments(orgId: Uuid, limit: Int): List<EffectiveServer>? =
        database.inOrg(orgId) {
            val rows = orgHostedAssignmentsQuery(orgId, limit + 1)
                .map { EffectiveServer(it.toServer(), it.toAssignment()) }
            if (rows.size <= limit) rows else null
        }

    override suspend fun stdioStates(orgId: Uuid, limit: Int): List<HostedProcessRecord>? = database.inOrg(orgId) {
        val t = McpRelayStdioStates
        val rows = t.select(t.columns).where { t.orgId eq orgId }
            .orderBy(t.serverId to SortOrder.ASC, t.memberId to SortOrder.ASC)
            .limit(limit + 1)
            .map { it.toRecord() }
        if (rows.size <= limit) rows else null
    }

    override suspend fun memberStdioStates(
        orgId: Uuid,
        serverIds: Collection<Uuid>,
        memberId: Uuid,
    ): List<HostedProcessRecord> {
        if (serverIds.isEmpty()) return emptyList()
        return database.inOrg(orgId) { memberStatesQuery(orgId, serverIds.distinct(), memberId).map { it.toRecord() } }
    }

    override suspend fun applyStdioStates(
        orgId: Uuid,
        changes: List<HostedProcessChange>,
        removals: List<HostedProcessRecord>,
    ): Int = database.inOrg(orgId) {
        val t = McpRelayStdioStates
        var applied = 0
        val drafts = ArrayList<OrgEventDraft>()
        val names = HashMap<Uuid, String>()
        for (change in changes) {
            val next = change.next
            val previous = change.previous
            // Compare-and-set: a row another replica changed meanwhile is left to it, and so is its event.
            val written = if (previous == null) {
                // A bare `ON CONFLICT DO NOTHING`, as before: the insert of a state another replica wrote first.
                t.insertIgnore {
                    it[t.orgId] = orgId
                    it[t.serverId] = next.serverId
                    it[t.memberId] = next.memberId
                    it[t.processKey] = next.processKey
                    it[t.state] = next.state
                    it[t.failure] = next.failure
                    it[t.changedAt] = next.changedAt
                }.insertedCount == 1
            } else {
                t.update({
                    (t.orgId eq orgId) and (t.serverId eq next.serverId) and t.memberId.isOrNull(previous.memberId) and
                        (t.changedAt eq previous.changedAt)
                }) {
                    it[t.processKey] = next.processKey
                    it[t.state] = next.state
                    it[t.failure] = next.failure
                    it[t.changedAt] = next.changedAt
                } == 1
            }
            if (written) {
                applied++
                val event = change.event
                if (event != null) drafts += hostedEvent(orgId, change, event, names)
            }
        }
        for (removal in removals) {
            t.deleteWhere {
                (t.orgId eq orgId) and (t.serverId eq removal.serverId) and t.memberId.isOrNull(removal.memberId) and
                    (t.changedAt eq removal.changedAt)
            }
        }
        // Last, after every other write: the organization's cursor row is the lock every publisher takes.
        if (drafts.isNotEmpty()) events.publishIn(this, orgId, drafts)
        applied
    }

    // The event of a transition, in the transaction that wrote it: the server's name is read there, under the tenant.
    private fun JdbcTransaction.hostedEvent(
        orgId: Uuid,
        change: HostedProcessChange,
        event: HostedProcessEvent,
        names: MutableMap<Uuid, String>,
    ): OrgEventDraft {
        val next = change.next
        // Once per server, not per process: a host restart flips every member's process of a server at once.
        val name = names.getOrPut(next.serverId) {
            val s = McpRelayServers
            s.select(s.name).where { (s.orgId eq orgId) and (s.id eq next.serverId) }.first()[s.name]
        }
        val failure = if (event == HostedProcessEvent.FAILED) next.failure else change.recoveredFrom
        val data = buildJsonObject {
            put("server", name)
            if (next.memberId != null) put("memberId", next.memberId.toString())
            put("state", next.state)
            put("failure", failure)
        }
        val failed = event == HostedProcessEvent.FAILED
        val type = if (failed) OrgEventType.MCP_STDIO_FAILED else OrgEventType.MCP_STDIO_RECOVERED
        val subject = if (next.memberId == null) name else "$name/${next.memberId}"
        return OrgEventDraft(type, subject, next.memberId, data)
    }

    private fun ResultRow.toRecord(): HostedProcessRecord {
        val t = McpRelayStdioStates
        return HostedProcessRecord(
            orgId = this[t.orgId],
            serverId = this[t.serverId],
            memberId = this[t.memberId],
            processKey = this[t.processKey],
            state = this[t.state],
            failure = this[t.failure],
            changedAt = this[t.changedAt],
        )
    }

    private fun ResultRow.toServer(): McpServer {
        val s = McpRelayServers
        val stdio = this[s.stdio]
        return McpServer(
            id = this[s.id],
            orgId = this[s.orgId],
            name = this[s.name],
            transport = McpTransport.valueOf(this[s.transport]),
            url = this[s.url],
            credentialSecretId = this[s.credentialSecretId],
            credentialHeader = this[s.credentialHeader],
            stdio = if (stdio == null) null else json.decodeFromString(StoredStdio.serializer(), stdio).toSpec(),
            sharing = McpSharing.valueOf(this[s.sharing]),
            version = this[s.version],
            createdAt = this[s.createdAt],
            ownerMemberId = this[s.ownerMemberId],
        )
    }

    private fun ResultRow.toAssignment(): McpAssignment {
        val a = McpRelayAssignments
        val tools = this[a.tools]
        return McpAssignment(
            orgId = this[a.orgId],
            serverId = this[a.serverId],
            memberId = this[a.memberId],
            policy = ToolPolicy(if (tools == null) null else json.decodeFromString(toolSet, tools)),
            credentialSecretId = this[a.credentialSecretId],
            assignedAt = this[a.assignedAt],
        )
    }

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

    // Instance properties, not companion ones: a private companion's accessors are synthetic code no test reaches.
    private val json = Json { ignoreUnknownKeys = true }
    private val toolSet = SetSerializer(String.serializer())
    private val conditionList = ListSerializer(StoredCondition.serializer())
}

private const val LISTED_ASSIGNMENTS = 10_000
private const val LISTED_POLICIES = 1_000
private const val LISTED_USAGE = 10_000
private val ERRORS = listOf(McpOutcome.UPSTREAM_ERROR, McpOutcome.TIMEOUT, McpOutcome.TOO_LARGE).map { it.name }
private val DENIALS = listOf(McpOutcome.DENIED, McpOutcome.APPROVAL_REQUIRED).map { it.name }

/** `column = value`, or `column IS NULL` when [value] is null: a member of a row that may belong to everyone. */
private fun Column<Uuid?>.isOrNull(value: Uuid?): Op<Boolean> = if (value == null) isNull() else this eq value

/** The servers with their assignments, `ON a.server_id = s.id AND a.org_id = s.org_id`. */
private fun serversWithAssignments() = McpRelayServers.join(
    McpRelayAssignments,
    JoinType.INNER,
    additionalConstraint = {
        (McpRelayAssignments.serverId eq McpRelayServers.id) and (McpRelayAssignments.orgId eq McpRelayServers.orgId)
    },
)

/** The stdio servers of one org with their assignments, for the hosted processes' administration. */
internal fun orgHostedAssignmentsQuery(orgId: Uuid, limit: Int): Query {
    val s = McpRelayServers
    val a = McpRelayAssignments
    return serversWithAssignments().select(s.columns + a.columns)
        .where { (s.orgId eq orgId) and (s.transport eq McpTransport.STDIO.name) }
        .orderBy(s.id to SortOrder.ASC, a.memberId to SortOrder.ASC_NULLS_FIRST)
        .limit(limit)
}

/** One member's recorded states of some servers (the servers' own and the shared ones' null member), for the list. */
internal fun memberStatesQuery(orgId: Uuid, serverIds: List<Uuid>, memberId: Uuid): Query {
    val t = McpRelayStdioStates
    return t.select(t.columns)
        .where {
            (t.orgId eq orgId) and (t.serverId inList serverIds) and ((t.memberId eq memberId) or t.memberId.isNull())
        }
}
