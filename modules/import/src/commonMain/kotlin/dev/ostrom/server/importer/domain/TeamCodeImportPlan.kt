// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer.domain

import dev.ostrom.server.secrets.domain.SecretValue
import kotlin.uuid.Uuid

/**
 * A TeamCode variant as an Ostrom server.
 *
 * @property name The server's name in Ostrom.
 * @property source The variant.
 * @property primary Whether it keeps the slot's name (so its tools keep their names); a slot's other variants get
 *   `<slot>.<variant>`.
 */
data class PlannedServer(val name: String, val source: TeamCodeMcpServer, val primary: Boolean) {
    /** The org secret holding the shared credential: `TEAMCODE_<SERVER>`. */
    val secretName: String get() = secretName(name)

    /**
     * The org secret holding a secret variable of the command: `TEAMCODE_<SERVER>_<VARIABLE>`.
     *
     * @param variable The variable.
     */
    fun variableSecretName(variable: String): String = "${secretName(name)}_$variable"

    /** The credential every member shares, for a shared HTTP server; a stdio server's are its secret variables. */
    val sharedCredential: SecretValue? get() = source.sharedSecret.takeIf { !source.perMember && source.stdio == null }

    /** The tools the members may call, or null for every tool (a deny list becomes argument policies). */
    val allowedTools: Set<String>? get() = source.tools.toSet().takeIf { source.toolMode == ToolMode.ALLOW }

    /** The tools denied to everyone. */
    val deniedTools: List<String> get() = source.tools.takeIf { source.toolMode == ToolMode.DENY }.orEmpty()
}

/**
 * A member's assignment.
 *
 * @property server The server.
 * @property memberId The Ostrom member.
 * @property memberSecret The member's own credential, kept as their personal secret `TEAMCODE_<SERVER>`.
 */
data class PlannedAssignment(val server: PlannedServer, val memberId: Uuid, val memberSecret: SecretValue?)

/**
 * What the import will write.
 *
 * @property servers The servers, primaries first.
 * @property assignments The assignments of mapped members.
 * @property skipped What cannot be imported, with the reason.
 */
data class TeamCodeImportPlan(
    val servers: List<PlannedServer>,
    val assignments: List<PlannedAssignment>,
    val skipped: List<ImportItem>,
)

private val secretChars = (('A'..'Z') + ('0'..'9')).toSet()
private val nameChars = (('a'..'z') + ('0'..'9') + '-' + '_').toSet()

/** The org secret name for a server: upper case, other characters as `_`. */
internal fun secretName(server: String): String =
    "TEAMCODE_" + server.uppercase().map { if (it in secretChars) it else '_' }.joinToString("")

/** Plans an import: pure, so a dry run and the real run see the same plan. */
object TeamCodeImportPlanner {
    /**
     * Plans it.
     *
     * @param export What the CLI read from TeamCode.
     * @param memberMap TeamCode member id to Ostrom member id.
     */
    fun plan(export: TeamCodeExport, memberMap: Map<String, Uuid>): TeamCodeImportPlan {
        val assignedCount = export.assignments.groupingBy { it.serverId }.eachCount()
        val servers = export.servers.groupBy { it.target }.flatMap { (target, variants) ->
            // The most assigned variant keeps the slot's name, so the tools most members use keep their names.
            val ordered = variants.sortedWith(compareBy({ -(assignedCount[it.id] ?: 0) }, { it.variant }))
            ordered.mapIndexed { index, variant ->
                val name = if (index == 0) slug(target) else "${slug(target)}.${slug(variant.variant)}"
                PlannedServer(name, variant, primary = index == 0)
            }
        }.sortedBy { !it.primary }
        val byId = servers.associateBy { it.source.id }
        val usernames = export.members.associate { it.id to it.username }
        val skipped = mutableListOf<ImportItem>()
        val assignments = mutableListOf<PlannedAssignment>()
        for (assignment in export.assignments) {
            val server = byId[assignment.serverId]
            val member = memberMap[assignment.memberId]
            val who = usernames[assignment.memberId] ?: assignment.memberId
            when {
                server == null -> skipped += skip(assignment.serverId, "No MCP variant with this id in the export.")
                member == null -> skipped += skip("${server.name}/$who", "No Ostrom member is mapped to $who.")
                else -> assignments += PlannedAssignment(server, member, assignment.memberSecret)
            }
        }
        return TeamCodeImportPlan(servers, assignments, skipped)
    }

    private fun skip(name: String, reason: String) =
        ImportItem(ImportKind.ASSIGNMENT, name, ImportAction.SKIPPED, reason)

    private fun slug(value: String): String =
        value.lowercase().map { if (it in nameChars) it else '-' }.joinToString("").trimStart('-', '_')
}
