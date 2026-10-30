// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer.domain

import dev.ostrom.server.mcprelay.domain.McpRules
import dev.ostrom.server.secrets.domain.SecretValue
import kotlin.uuid.Uuid

/**
 * A TeamCode variant as an Ostrom server.
 *
 * @property name The server's name in Ostrom.
 * @property source The variant.
 * @property primary Whether it keeps the slot's name (so its tools keep their names); a slot's other variants get
 *   `<slot>.<variant>`.
 * @property conflict Why it is not imported: its name or one of its secrets' names is the one of another variant.
 */
data class PlannedServer(
    val name: String,
    val source: TeamCodeMcpServer,
    val primary: Boolean,
    val conflict: String? = null,
) {
    /** The org secret holding the shared credential, or each member's own: `TEAMCODE_<SERVER>`. */
    val secretName: String get() = secretName(name)

    /**
     * The org secret holding a secret variable of the command: `TEAMCODE_<SERVER>_<VARIABLE>`.
     *
     * @param variable The variable.
     */
    fun variableSecretName(variable: String): String = "${secretName(name)}_$variable"

    /** The credential every member shares, for a shared HTTP server; a stdio server's are its secret variables. */
    val sharedCredential: SecretValue? get() = source.sharedSecret.takeIf { !source.perMember && source.stdio == null }

    /** Whether each member's assignment carries their own credential: only an HTTP per-member server does. */
    val carriesMemberCredential: Boolean get() = source.perMember && source.stdio == null

    /** The secret names it writes, so that two servers never write, and rotate, the same secret. */
    val secretNames: List<String>
        get() = listOfNotNull(secretName.takeIf { sharedCredential != null || carriesMemberCredential }) +
            source.stdio?.let { stdio -> stdio.secretEnv.keys.map(::variableSecretName) }.orEmpty()

    /** The tools the members may call, or null for every tool (a deny list becomes argument policies). */
    val allowedTools: Set<String>? get() = source.tools.toSet().takeIf { source.toolMode == ToolMode.ALLOW }

    /** The tools denied to everyone. */
    val deniedTools: List<String> get() = source.tools.takeIf { source.toolMode == ToolMode.DENY }.orEmpty().distinct()

    /** The variant as TeamCode names it, for the reasons of the report. */
    val label: String get() = "${source.target}/${source.variant}"
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
 * @property servers The servers, primaries first, then by name, slot, variant and id; a server in conflict stays in the
 *   list, so that it and its assignments are reported.
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

/**
 * A name a server takes for itself: the one the agent sees, or a secret's.
 *
 * @property secret Whether it is a secret's name.
 * @property name The name.
 */
private data class Claim(val secret: Boolean, val name: String)

/** Plans an import: pure, so a dry run and the real run see the same plan. */
object TeamCodeImportPlanner {
    /**
     * Plans it.
     *
     * @param export What the CLI read from TeamCode.
     * @param memberMap TeamCode member id to Ostrom member id.
     * @param recorded The name each variant was imported under before, by TeamCode variant id: a variant keeps it,
     *   so that adding or reassigning variants in TeamCode never renames the tools members already use.
     */
    fun plan(
        export: TeamCodeExport,
        memberMap: Map<String, Uuid>,
        recorded: Map<String, String> = emptyMap(),
    ): TeamCodeImportPlan {
        val named = export.servers.groupBy { it.target }
            .flatMap { (target, variants) -> name(target, variants, recorded) }
            .sortedWith(
                compareBy({ !it.primary }, { it.name }, { it.source.target }, { it.source.variant }, { it.source.id }),
            )
        val servers = resolveConflicts(named, recorded.keys)
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

    /**
     * Names a slot's variants. A variant imported before keeps its name; of the others, by variant name then id (never
     * by how many members use them, which changes between runs), the first takes the slot's name if no variant holds
     * it, and the rest `<slot>.<variant>`.
     */
    private fun name(
        target: String,
        variants: List<TeamCodeMcpServer>,
        recorded: Map<String, String>,
    ): List<PlannedServer> {
        val bare = slug(target)
        // A recorded name of another slot (the variant moved in TeamCode) is not kept.
        val kept = variants.mapNotNull { variant ->
            recorded[variant.id]?.takeIf { it == bare || it == suffixed(bare, variant) }?.let { variant.id to it }
        }.toMap()
        var bareTaken = bare in kept.values
        return variants.sortedWith(compareBy({ it.variant }, { it.id })).map { variant ->
            val name = kept[variant.id] ?: if (bareTaken) suffixed(bare, variant) else bare.also { bareTaken = true }
            PlannedServer(name, variant, primary = name == bare)
        }
    }

    private fun suffixed(bare: String, variant: TeamCodeMcpServer) = "$bare.${slug(variant.variant)}"

    /**
     * Keeps, of servers that would share a name the agent sees (`a.b` and `a_b`, `Grafana` and `grafana`) or a secret
     * (`a-b` and `a_b` both write `TEAMCODE_A_B`), the first: the ones imported before, then in [servers]' order. The
     * others are marked in conflict, never imported, so one never rotates another's credential.
     */
    private fun resolveConflicts(servers: List<PlannedServer>, recorded: Set<String>): List<PlannedServer> {
        val claims = mutableMapOf<Claim, PlannedServer>()
        val conflicts = mutableMapOf<String, String>()
        val (known, fresh) = servers.partition { it.source.id in recorded }
        for (server in known + fresh) {
            val wanted = listOf(Claim(false, McpRules.agentName("", server.name))) +
                server.secretNames.map { Claim(true, it) }
            val clash = wanted.firstOrNull { it in claims }
            if (clash == null) {
                wanted.forEach { claims[it] = server }
            } else {
                conflicts[server.source.id] = collision(clash, claims.getValue(clash))
            }
        }
        return servers.map { server ->
            val conflict = conflicts[server.source.id]
            if (conflict == null) server else server.copy(conflict = conflict)
        }
    }

    private fun collision(clash: Claim, other: PlannedServer): String = if (clash.secret) {
        "Its secret ${clash.name} would be the one of TeamCode's ${other.label}, imported as ${other.name}."
    } else {
        "The agent would see it as TeamCode's ${other.label}, imported as ${other.name}."
    }

    private fun skip(name: String, reason: String) =
        ImportItem(ImportKind.ASSIGNMENT, name, ImportAction.SKIPPED, reason)

    private fun slug(value: String): String =
        value.lowercase().map { if (it in nameChars) it else '-' }.joinToString("").trimStart('-', '_')
}
