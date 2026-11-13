// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer.presentation

import run.ostrom.server.importer.domain.ImportAction
import run.ostrom.server.importer.domain.ImportHandover
import run.ostrom.server.importer.domain.ImportReport
import run.ostrom.server.importer.domain.TeamCodeAccount
import run.ostrom.server.importer.domain.TeamCodeAssignment
import run.ostrom.server.importer.domain.TeamCodeAutomation
import run.ostrom.server.importer.domain.TeamCodeExport
import run.ostrom.server.importer.domain.TeamCodeMcpServer
import run.ostrom.server.importer.domain.TeamCodeMember
import run.ostrom.server.importer.domain.TeamCodeProcesses
import run.ostrom.server.importer.domain.TeamCodeProfile
import run.ostrom.server.importer.domain.TeamCodeStdio
import run.ostrom.server.importer.domain.TeamCodeWorkflow
import run.ostrom.server.importer.domain.TeamCodeWorkflowEditors
import run.ostrom.server.importer.domain.ToolMode
import run.ostrom.server.mcprelay.domain.StdioLauncher
import run.ostrom.server.secrets.domain.SecretValue
import io.ktor.http.HttpHeaders
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Maps the request to the domain. */
fun TeamCodeImportRequest.toExport() = TeamCodeExport(
    members = members.map { TeamCodeMember(it.id, it.username) },
    servers = mcpServers.map { it.toDomain() },
    assignments = assignments.map { TeamCodeAssignment(it.memberId, it.serverId, it.memberSecret?.let(::SecretValue)) },
    accounts = accounts.map { it.toDomain() },
    processes = TeamCodeProcesses(
        workflows = workflows.map {
            TeamCodeWorkflow(it.id, it.name, it.version, it.definition, it.updatedById, it.createdAt)
        },
        editors = workflowEditors?.let { TeamCodeWorkflowEditors(it.mode, it.memberIds) },
        automations = automations.map { it.toDomain() },
        profiles = profiles.map { TeamCodeProfile(it.id, it.name, it.repositories, it.setupCommands) },
    ),
)

private fun TeamCodeAutomationDto.toDomain() = TeamCodeAutomation(
    id = id,
    ownerId = ownerId,
    name = name,
    triggerKind = triggerKind,
    cron = cron,
    timezone = timezone,
    catchup = catchup,
    catchupGraceMinutes = catchupGraceMinutes,
    actionKind = actionKind,
    profileId = profileId,
    model = model,
    effort = effort,
    maxRuntimeMinutes = maxRuntimeMinutes,
    prompt = prompt.orEmpty(),
    variables = variables,
    samplePayload = samplePayload,
    overlap = overlap,
    maxRunsPerDay = maxRunsPerDay,
    enabled = enabled,
    workflowId = workflowId,
    workflowInputs = workflowInputs,
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
    items = items.map {
        ImportItemDto(
            it.kind.name.lowercase(),
            it.name,
            it.action.name.lowercase(),
            it.reason,
            it.handover?.let { handover -> handover.name.lowercase() },
        )
    },
    created = count(ImportAction.CREATED),
    updated = count(ImportAction.UPDATED),
    unchanged = count(ImportAction.UNCHANGED),
    skipped = count(ImportAction.SKIPPED),
    reviveAtCutover = count(ImportAction.REVIVE_AT_CUTOVER),
    pendingHandover = count(ImportHandover.PENDING_HANDOVER),
    accessOnly = count(ImportHandover.ACCESS_ONLY),
)
