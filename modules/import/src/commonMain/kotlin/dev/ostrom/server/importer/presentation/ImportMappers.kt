// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer.presentation

import dev.ostrom.server.importer.domain.ImportAction
import dev.ostrom.server.importer.domain.ImportReport
import dev.ostrom.server.importer.domain.TeamCodeAccount
import dev.ostrom.server.importer.domain.TeamCodeAssignment
import dev.ostrom.server.importer.domain.TeamCodeExport
import dev.ostrom.server.importer.domain.TeamCodeMcpServer
import dev.ostrom.server.importer.domain.TeamCodeMember
import dev.ostrom.server.importer.domain.TeamCodeStdio
import dev.ostrom.server.importer.domain.ToolMode
import dev.ostrom.server.mcprelay.domain.StdioLauncher
import dev.ostrom.server.secrets.domain.SecretValue
import io.ktor.http.HttpHeaders
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Maps the request to the domain. */
fun TeamCodeImportRequest.toExport() = TeamCodeExport(
    members = members.map { TeamCodeMember(it.id, it.username) },
    servers = mcpServers.map { it.toDomain() },
    assignments = assignments.map { TeamCodeAssignment(it.memberId, it.serverId, it.memberSecret?.let(::SecretValue)) },
    accounts = accounts.map { it.toDomain() },
)

/** The member map, with Ostrom ids parsed. */
fun TeamCodeImportRequest.memberIds(): Map<String, Uuid> = memberMap.mapValues { (teamCode, ostrom) ->
    Uuid.parseOrNull(ostrom) ?: throw InvalidImportException("The member map gives $teamCode a malformed member id.")
}

private fun TeamCodeMcpServerDto.toDomain() = TeamCodeMcpServer(
    id = id,
    target = target,
    variant = variant,
    perMember = perMember,
    url = url,
    // TeamCode stores `Authorization` for the default; Ostrom says Bearer with no header.
    credentialHeader = credentialHeader?.takeUnless { it.equals(HttpHeaders.Authorization, ignoreCase = true) },
    sharedSecret = sharedSecret?.let(::SecretValue),
    stdio = stdio?.let { command ->
        TeamCodeStdio(
            launcher = StdioLauncher.entries.firstOrNull { it.wire == command.launcher }
                ?: throw InvalidImportException("The launcher of $target is npm or pypi."),
            packageName = command.packageName,
            args = command.args,
            secretEnv = command.secretEnv.mapValues { SecretValue(it.value) },
        )
    },
    toolMode = toolMode?.let { mode ->
        ToolMode.entries.firstOrNull { it.name.equals(mode, ignoreCase = true) }
            ?: throw InvalidImportException("The tool mode of $target is allow or deny.")
    },
    tools = tools,
)

private fun TeamCodeAccountDto.toDomain() = TeamCodeAccount(
    provider = provider,
    providerAccountId = providerAccountId,
    label = label,
    email = email,
    accessToken = SecretValue(accessToken),
    refreshToken = SecretValue(refreshToken),
    expiresAt = try {
        Instant.parse(expiresAt)
    } catch (_: IllegalArgumentException) {
        throw InvalidImportException("The expiry of $label is not an ISO 8601 instant.")
    },
    disabled = disabled,
)

/** Maps the report to the API. */
fun ImportReport.toDto() = ImportReportDto(
    items = items.map { ImportItemDto(it.kind.name.lowercase(), it.name, it.action.name.lowercase(), it.reason) },
    created = count(ImportAction.CREATED),
    updated = count(ImportAction.UPDATED),
    unchanged = count(ImportAction.UNCHANGED),
    skipped = count(ImportAction.SKIPPED),
)
