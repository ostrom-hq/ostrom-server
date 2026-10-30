// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer.domain

import dev.ostrom.server.mcprelay.domain.ArgumentCondition
import dev.ostrom.server.mcprelay.domain.ArgumentPolicyPayload
import dev.ostrom.server.mcprelay.domain.McpServer
import dev.ostrom.server.mcprelay.domain.McpServerPayload
import dev.ostrom.server.mcprelay.domain.McpSettings
import dev.ostrom.server.mcprelay.domain.PolicyEffect
import dev.ostrom.server.mcprelay.domain.ToolPolicy
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.CreateSecretPayload
import dev.ostrom.server.secrets.domain.SecretNotFoundException
import dev.ostrom.server.secrets.domain.SecretValue
import kotlin.uuid.Uuid

/** The prefix TeamCode gives every relayed server: tools stay `mcp__teamcode-<server>__<tool>`. */
const val TEAMCODE_PREFIX: String = "teamcode"

/** Writes secrets, leaving the ones that already hold the value alone. */
internal class SecretImporter(private val secrets: SecretPorts) {
    /**
     * Writes [value] under [name], an org secret, or a personal secret of [caller] when [personal].
     *
     * @return The secret's id and the report line.
     */
    suspend fun upsert(caller: Caller, name: String, value: SecretValue, personal: Boolean): Pair<Uuid, ImportItem> {
        val label = if (personal) "$name (personal, ${caller.memberId})" else name
        val existing = try {
            secrets.find(caller, name, personal)
        } catch (_: SecretNotFoundException) {
            null
        }
        val (id, action) = when {
            existing == null -> secrets.create(caller, CreateSecretPayload(name, value, personal)).id to
                ImportAction.CREATED
            secrets.reveal(caller.orgId, listOf(existing.id))[existing.id] == value ->
                existing.id to
                    ImportAction.UNCHANGED
            else -> secrets.rotate(caller, existing.id, value).id to ImportAction.UPDATED
        }
        return id to ImportItem(ImportKind.SECRET, label, action)
    }
}

/** Writes servers, denied tools, assignments and the prefix, leaving what already matches alone. */
internal class McpImporter(private val mcp: McpPorts) {
    /** Sets the agent prefix to TeamCode's. */
    suspend fun prefix(caller: Caller): ImportItem {
        val unchanged = mcp.getSettings(caller).agentPrefix == TEAMCODE_PREFIX
        if (!unchanged) mcp.putSettings(caller, McpSettings(TEAMCODE_PREFIX))
        return ImportItem(
            ImportKind.SETTING,
            "agentPrefix",
            if (unchanged) ImportAction.UNCHANGED else ImportAction.UPDATED,
        )
    }

    /** Declares or updates the server named by [payload]. */
    suspend fun server(caller: Caller, payload: McpServerPayload): Pair<McpServer, ImportItem> {
        val existing = mcp.listServers(caller).firstOrNull { it.name == payload.name }
        val (server, action) = when {
            existing == null -> mcp.createServer(caller, payload) to ImportAction.CREATED
            existing.toPayload() == payload -> existing to ImportAction.UNCHANGED
            else -> mcp.updateServer(caller, existing.id, payload) to ImportAction.UPDATED
        }
        return server to ImportItem(ImportKind.SERVER, payload.name, action)
    }

    /** Denies [tools] of [server] to everyone, as argument policies without conditions. */
    suspend fun deny(caller: Caller, server: McpServer, tools: List<String>): List<ImportItem> {
        val existing = mcp.listPolicies(caller, server.id).map { Triple(it.tool, it.conditions, it.effect) }.toSet()
        return tools.map { tool ->
            val action = if (Triple(tool, emptyList<ArgumentCondition>(), PolicyEffect.DENY) in existing) {
                ImportAction.UNCHANGED
            } else {
                mcp.createPolicy(caller, server.id, ArgumentPolicyPayload(tool, emptyList(), PolicyEffect.DENY, DENIED))
                ImportAction.CREATED
            }
            ImportItem(ImportKind.POLICY, "${server.name}/$tool", action)
        }
    }

    /** Assigns [server] to [memberId] with [tools] and [credentialId]. */
    suspend fun assign(
        caller: Caller,
        server: McpServer,
        memberId: Uuid,
        tools: Set<String>?,
        credentialId: Uuid?,
    ): ImportItem {
        val policy = ToolPolicy(tools)
        val existing = mcp.listAssignments(caller, server.id).firstOrNull { it.memberId == memberId }
        val action = when {
            existing == null -> ImportAction.CREATED
            (existing.policy to existing.credentialSecretId) == (policy to credentialId) -> ImportAction.UNCHANGED
            else -> ImportAction.UPDATED
        }
        if (action != ImportAction.UNCHANGED) mcp.assign(caller, server.id, memberId, policy, credentialId)
        return ImportItem(ImportKind.ASSIGNMENT, "${server.name}/$memberId", action)
    }

    private fun McpServer.toPayload() =
        McpServerPayload(name, transport, url, credentialSecretId, credentialHeader, stdio, sharing)

    private companion object {
        const val DENIED = "Denied in TeamCode."
    }
}
