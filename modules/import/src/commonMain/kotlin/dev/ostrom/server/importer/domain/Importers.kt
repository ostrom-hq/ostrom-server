// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer.domain

import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.mcprelay.domain.ArgumentCondition
import dev.ostrom.server.mcprelay.domain.ArgumentPolicyPayload
import dev.ostrom.server.mcprelay.domain.McpAssignment
import dev.ostrom.server.mcprelay.domain.McpRules
import dev.ostrom.server.mcprelay.domain.McpServer
import dev.ostrom.server.mcprelay.domain.McpServerPayload
import dev.ostrom.server.mcprelay.domain.McpSettings
import dev.ostrom.server.mcprelay.domain.PolicyEffect
import dev.ostrom.server.mcprelay.domain.ToolPolicy
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.CreateSecretPayload
import dev.ostrom.server.secrets.domain.Secret
import dev.ostrom.server.secrets.domain.SecretNotFoundException
import dev.ostrom.server.secrets.domain.SecretValue
import kotlin.uuid.Uuid

/** The prefix TeamCode gives every relayed server: tools stay `mcp__teamcode-<server>__<tool>`. */
const val TEAMCODE_PREFIX: String = "teamcode"

/** The id a dry run gives what it would create: no stored row has it, so nothing referring to it reads unchanged. */
internal val PENDING: Uuid = Uuid.NIL

/** Writes the import's records, as the run's caller's org. */
internal typealias Recorder = suspend (ImportRecord) -> Unit

/**
 * A secret the import writes.
 *
 * @property owner Who writes it: the admin for an org secret, the member for their own.
 * @property name Its name.
 * @property value TeamCode's value.
 * @property personal Whether it is [owner]'s personal secret.
 * @property sourceKey What it comes from in TeamCode (see [ImportRecord.sourceKey]).
 */
internal data class SecretWrite(
    val owner: Caller,
    val name: String,
    val value: SecretValue,
    val personal: Boolean,
    val sourceKey: String,
) {
    /** Its line in the report. */
    val label: String get() = if (personal) "$name (personal, ${owner.memberId})" else name

    /** Who owns it and its name: what tells two secrets apart. */
    val key: Pair<Uuid?, String> get() = (if (personal) owner.memberId else null) to name
}

/**
 * Writes secrets, leaving alone the ones that already hold the value and the ones it did not create.
 *
 * @param secrets The secrets use cases.
 * @param dryRun Whether to write nothing.
 * @param owned The ids of the secrets the import created.
 * @param record Records what it creates.
 */
internal class SecretImporter(
    private val secrets: SecretPorts,
    private val dryRun: Boolean,
    owned: Set<Uuid>,
    private val record: Recorder,
) {
    private val owned = owned.toMutableSet()
    private val found = mutableMapOf<Pair<Uuid?, String>, Secret?>()
    private val values = mutableMapOf<Uuid, SecretValue>()

    /**
     * Finds the secrets [writes] name, then reads the values of those that exist in one call.
     *
     * @throws dev.ostrom.server.secrets.domain.SecretUnreadableException When a stored value does not open with this
     *   instance's key: the whole import stops, as every secret of the org is then unreadable.
     */
    suspend fun prepare(orgId: Uuid, writes: List<SecretWrite>) {
        for (write in writes) found[write.key] = find(write)
        values += secrets.reveal(orgId, found.values.mapNotNull { it?.id })
    }

    /** Why [write] is not imported: a secret of that name exists that the import did not create; or null. */
    fun foreign(write: SecretWrite): String? = found[write.key]?.takeIf { it.id !in owned }?.let {
        "A secret named ${write.label} exists and was not imported from TeamCode."
    }

    /**
     * Writes [write] (prepared, not [foreign]).
     *
     * @return The secret's id ([PENDING] when a dry run would create it) and the report line.
     */
    suspend fun upsert(write: SecretWrite): Pair<Uuid, ImportItem> {
        val existing = found[write.key]
        val (id, action) = if (existing == null) {
            if (dryRun) PENDING to ImportAction.PLANNED else create(write) to ImportAction.CREATED
        } else {
            when {
                values[existing.id] == write.value -> existing.id to ImportAction.UNCHANGED
                dryRun -> existing.id to ImportAction.PLANNED
                else -> secrets.rotate(write.owner, existing.id, write.value).id to ImportAction.UPDATED
            }
        }
        values[id] = write.value
        return id to ImportItem(ImportKind.SECRET, write.label, action)
    }

    private suspend fun create(write: SecretWrite): Uuid {
        val created = secrets.create(write.owner, CreateSecretPayload(write.name, write.value, write.personal))
        record(ImportRecord(ImportRecordKind.SECRET, write.sourceKey, write.label, created.id))
        owned += created.id
        found[write.key] = created
        return created.id
    }

    private suspend fun find(write: SecretWrite): Secret? = try {
        secrets.find(write.owner, write.name, write.personal)
    } catch (_: SecretNotFoundException) {
        null
    }
}

/**
 * A server the import may assign.
 *
 * @property planned What the plan says of it.
 * @property server The server, or null when a dry run would create it.
 */
internal data class ImportedServer(val planned: PlannedServer, val server: McpServer?)

/**
 * What importing a server gave.
 *
 * @property lines Its report lines: the server's and its denials', or one skipped line when a denial was refused.
 * @property assignable Whether it is as in TeamCode with every denial in place, so members may be assigned to it; a
 *   server missing a denial is never assigned, or a member would reach a tool TeamCode denied.
 * @property server The server, or null when a dry run would create it.
 */
internal data class ServerOutcome(val lines: List<ImportItem>, val assignable: Boolean, val server: McpServer?)

/**
 * Writes servers, denied tools, assignments and the prefix, leaving alone what already matches and what the import did
 * not create. It lists the org's servers once, and each server's assignments once.
 *
 * @param mcp The MCP relay use cases.
 * @param dryRun Whether to write nothing.
 * @param existing The org's servers before the import.
 * @param owned The ids of the servers the import created.
 * @param record Records what it creates.
 */
internal class McpImporter(
    private val mcp: McpPorts,
    private val dryRun: Boolean,
    existing: List<McpServer>,
    private val owned: Set<Uuid>,
    private val record: Recorder,
) {
    private val hadServers = existing.isNotEmpty()
    private val byName = existing.associateBy { it.name }.toMutableMap()
    private val assignments = mutableMapOf<Uuid, MutableList<McpAssignment>>()

    /**
     * Sets the agent prefix to TeamCode's, only in an org that never changed it and exposes no server yet: otherwise
     * every tool the org's agents already call would be renamed.
     */
    suspend fun prefix(caller: Caller): ImportItem {
        val current = mcp.getSettings(caller).agentPrefix
        val action = when {
            current == TEAMCODE_PREFIX -> ImportAction.UNCHANGED
            current != McpSettings.DEFAULT_PREFIX || hadServers -> ImportAction.SKIPPED
            dryRun -> ImportAction.PLANNED
            else -> {
                mcp.putSettings(caller, McpSettings(TEAMCODE_PREFIX))
                ImportAction.UPDATED
            }
        }
        val reason = "The agent prefix $current was kept: changing it would rename the tools the org already uses."
        return ImportItem(ImportKind.SETTING, "agentPrefix", action, reason.takeIf { action == ImportAction.SKIPPED })
    }

    /** Why a server named [name] cannot be imported, known before writing anything; or null. */
    fun refusal(name: String): String? {
        val existing = byName[name]
        val seen = McpRules.agentName("", name)
        val shadowed = byName.values.firstOrNull { it.name != name && McpRules.agentName("", it.name) == seen }
        return when {
            existing != null && existing.id !in owned ->
                "A server named $name exists and was not imported from TeamCode."
            shadowed != null -> "The agent would see $name as the server ${shadowed.name} already is."
            else -> null
        }
    }

    /**
     * Declares or updates [planned] as [payload]. A new server is declared, then denied its tools; an existing one is
     * denied its new tools before it changes. Either way it is only assignable once every denial is in place.
     */
    suspend fun server(caller: Caller, planned: PlannedServer, payload: McpServerPayload): ServerOutcome {
        val existing = byName[planned.name]
        return if (existing == null) create(caller, planned, payload) else update(caller, planned, existing, payload)
    }

    /**
     * Assigns [imported] to [memberId]. Only an explicit TeamCode credential replaces the member's: without one, the
     * credential the member set stays, so a second run never wipes it.
     */
    suspend fun assign(caller: Caller, imported: ImportedServer, memberId: Uuid, credential: Uuid?): ImportItem {
        val planned = imported.planned
        val server = imported.server
        val policy = ToolPolicy(planned.allowedTools)
        val existing = server?.let { assignmentsOf(caller, it.id).firstOrNull { row -> row.memberId == memberId } }
        val kept = credential ?: existing?.credentialSecretId?.takeIf { planned.carriesMemberCredential }
        val wanted = when {
            existing == null -> ImportAction.CREATED
            existing.policy == policy && existing.credentialSecretId == kept -> ImportAction.UNCHANGED
            else -> ImportAction.UPDATED
        }
        val action = when {
            server == null -> ImportAction.PLANNED
            wanted == ImportAction.UNCHANGED -> ImportAction.UNCHANGED
            dryRun -> ImportAction.PLANNED
            else -> {
                val assigned = mcp.assign(caller, server.id, memberId, policy, kept)
                val rows = assignmentsOf(caller, server.id)
                rows.removeAll { it.memberId == memberId }
                rows += assigned
                wanted
            }
        }
        return ImportItem(ImportKind.ASSIGNMENT, "${planned.name}/$memberId", action)
    }

    private suspend fun create(caller: Caller, planned: PlannedServer, payload: McpServerPayload): ServerOutcome {
        if (dryRun) return ServerOutcome(lines(planned, ImportAction.PLANNED) { ImportAction.PLANNED }, true, null)
        val server = mcp.createServer(caller, payload)
        record(ImportRecord(ImportRecordKind.SERVER, planned.source.id, planned.name, server.id))
        byName[planned.name] = server
        assignments[server.id] = mutableListOf()
        val refused = deny(caller, server, planned.deniedTools)
        return if (refused == null) {
            ServerOutcome(lines(planned, ImportAction.CREATED) { ImportAction.CREATED }, true, server)
        } else {
            skippedServer(planned, refused)
        }
    }

    private suspend fun update(
        caller: Caller,
        planned: PlannedServer,
        existing: McpServer,
        payload: McpServerPayload,
    ): ServerOutcome {
        val policies = mcp.listPolicies(caller, existing.id).map { Triple(it.tool, it.conditions, it.effect) }.toSet()
        val present = planned.deniedTools
            .filter { Triple(it, emptyList<ArgumentCondition>(), PolicyEffect.DENY) in policies }
            .toSet()
        val refused = if (dryRun) null else deny(caller, existing, planned.deniedTools.filter { it !in present })
        if (refused != null) return skippedServer(planned, refused)
        val unchanged = existing.toPayload() == payload
        val (server, action) = when {
            unchanged -> existing to ImportAction.UNCHANGED
            dryRun -> existing to ImportAction.PLANNED
            else -> mcp.updateServer(caller, existing.id, payload).also { byName[planned.name] = it } to
                ImportAction.UPDATED
        }
        val written = if (dryRun) ImportAction.PLANNED else ImportAction.CREATED
        return ServerOutcome(
            lines(planned, action) { tool -> if (tool in present) ImportAction.UNCHANGED else written },
            true,
            server,
        )
    }

    /** Creates the denials of [tools], in order; the first refusal stops them and is returned as the reason. */
    private suspend fun deny(caller: Caller, server: McpServer, tools: List<String>): String? {
        for (tool in tools) {
            try {
                mcp.createPolicy(caller, server.id, ArgumentPolicyPayload(tool, emptyList(), PolicyEffect.DENY, DENIED))
            } catch (refused: OstromException) {
                return "Denying $tool was refused (${refused.message}), so this import assigns the server to no one."
            }
        }
        return null
    }

    private fun lines(planned: PlannedServer, action: ImportAction, policy: (String) -> ImportAction) =
        listOf(ImportItem(ImportKind.SERVER, planned.name, action)) +
            planned.deniedTools.map { ImportItem(ImportKind.POLICY, "${planned.name}/$it", policy(it)) }

    private suspend fun assignmentsOf(caller: Caller, serverId: Uuid): MutableList<McpAssignment> =
        assignments.getOrPut(serverId) { mcp.listAssignments(caller, serverId).toMutableList() }

    private companion object {
        const val DENIED = "Denied in TeamCode."
    }
}

private fun skippedServer(planned: PlannedServer, reason: String) =
    ServerOutcome(listOf(ImportItem(ImportKind.SERVER, planned.name, ImportAction.SKIPPED, reason)), false, null)

private fun McpServer.toPayload() =
    McpServerPayload(name, transport, url, credentialSecretId, credentialHeader, stdio, sharing)
