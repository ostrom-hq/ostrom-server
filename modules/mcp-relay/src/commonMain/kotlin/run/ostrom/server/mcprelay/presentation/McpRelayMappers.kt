// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay.presentation

import run.ostrom.server.mcprelay.domain.InvalidMcpServerException
import run.ostrom.server.mcprelay.domain.McpAssignment
import run.ostrom.server.mcprelay.domain.McpSecretNotFoundException
import run.ostrom.server.mcprelay.domain.McpServer
import run.ostrom.server.mcprelay.domain.McpServerPayload
import run.ostrom.server.mcprelay.domain.McpSharing
import run.ostrom.server.mcprelay.domain.McpTransport
import run.ostrom.server.mcprelay.domain.MemberServer
import run.ostrom.server.mcprelay.domain.StdioLauncher
import run.ostrom.server.mcprelay.domain.StdioSpec
import kotlin.uuid.Uuid

/**
 * Maps a server to the domain.
 *
 * @param secretIds The ids of the org secrets it names, by name.
 */
fun McpServerDto.toPayload(secretIds: Map<String, Uuid>) = McpServerPayload(
    name = name,
    transport = enumOf<McpTransport>(transport, "transport"),
    url = url.takeIf { enumOf<McpTransport>(transport, "transport") == McpTransport.HTTP && it.isNotEmpty() },
    credentialSecretId = credentialName()?.let { secretIds[it] ?: throw McpSecretNotFoundException() },
    credentialHeader = credentialHeader,
    stdio = stdio?.let {
        StdioSpec(
            launcher = enumOf<StdioLauncher>(it.launcher, "launcher"),
            packageName = it.packageName,
            args = it.args,
            env = it.env,
            secretEnv = it.secretEnv.mapValues { (_, name) -> secretIds[name] ?: throw McpSecretNotFoundException() },
        )
    },
    sharing = when (scope) {
        "shared" -> McpSharing.SHARED
        "per-member" -> McpSharing.PER_MEMBER
        else -> throw InvalidMcpServerException("scope must be shared or per-member.")
    },
)

/** The credential of an HTTP server: its first secret name. */
fun McpServerDto.credentialName(): String? =
    if (transport.equals("http", ignoreCase = true)) secretNames.firstOrNull() else null

/** Every secret name a server uses. */
fun McpServerDto.secretNamesUsed(): List<String> = listOfNotNull(credentialName()) + stdio?.secretEnv.orEmpty().values

/** Every secret id a server uses. */
fun McpServer.secretIds(): List<Uuid> = listOfNotNull(credentialSecretId) + stdio?.secretEnv.orEmpty().values

/**
 * Maps a server to the API.
 *
 * @param names Secret names by id.
 */
fun McpServer.toDto(names: Map<Uuid, String>) = McpServerDto(
    name = name,
    url = url ?: stdio?.let { "stdio:${it.launcher.wire}:${it.packageName}" }.orEmpty(),
    transport = transport.name.lowercase(),
    secretNames = secretIds().mapNotNull { names[it] }.distinct(),
    scope = if (sharing == McpSharing.SHARED) "shared" else "per-member",
    credentialHeader = credentialHeader,
    stdio = stdio?.let { spec ->
        StdioSpecDto(
            spec.launcher.name.lowercase(),
            spec.packageName,
            spec.args,
            spec.env,
            spec.secretEnv.mapNotNull { (key, id) ->
                names[id]?.let {
                    key to
                        it
                }
            }.toMap(),
        )
    },
    version = version,
    createdAt = createdAt.toString(),
)

/** Maps an assignment to the API. */
fun McpAssignment.toResponse(server: String, credentialName: String?) = McpAssignmentResponse(
    server = server,
    member = memberId?.toString(),
    tools = policy.tools?.sorted(),
    credentialSecret = credentialName,
    assignedAt = assignedAt.toString(),
)

/** Maps a member's server to the API. */
fun MemberServer.toDto() = McpServerDto(
    name = name,
    url = "/api/v1/mcp/$name",
    transport = transport.name.lowercase(),
    tools = tools?.sorted(),
    agentName = agentName,
    path = "/api/v1/mcp/$name",
)

/** The enum constant named [value], case-insensitively, or a 400 naming [field]. */
internal inline fun <reified T : Enum<T>> enumOf(value: String, field: String): T =
    enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: throw InvalidMcpServerException(
            "$field must be one of ${enumValues<T>().joinToString {
                it.name.lowercase()
            }}.",
        )
