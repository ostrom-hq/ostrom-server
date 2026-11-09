// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.importer.domain

import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.mcprelay.domain.McpServerPayload
import run.ostrom.server.mcprelay.domain.McpSharing
import run.ostrom.server.mcprelay.domain.McpTransport
import run.ostrom.server.mcprelay.domain.StdioSpec
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.MemberRole
import kotlin.uuid.Uuid

/**
 * One import, real or dry: both go through the same steps and report the same lines, the dry run writing nothing.
 *
 * @param caller The admin.
 * @param secrets Writes the secrets.
 * @param mcp Writes the servers, denials, assignments and prefix.
 */
internal class ImportRun(
    private val caller: Caller,
    private val secrets: SecretImporter,
    private val mcp: McpImporter,
) {
    private val items = mutableListOf<ImportItem>()
    private val imported = mutableMapOf<String, ImportedServer>()

    /**
     * Applies [plan].
     *
     * @return The report lines: the prefix, each server with its secrets and denials, each assignment, the assignments
     *   TeamCode no longer has, then what the plan skipped.
     */
    suspend fun import(plan: TeamCodeImportPlan): MutableList<ImportItem> {
        items += mcp.prefix(caller)
        val writes = plan.servers.filter { it.conflict == null }.flatMap(::serverSecrets) +
            plan.assignments.filter { it.server.conflict == null }.mapNotNull(::personalSecret)
        secrets.prepare(caller.orgId, writes)
        for (planned in plan.servers) server(planned)
        for (planned in plan.assignments) assignment(planned)
        val members = plan.assignments.groupBy({ it.server.source.id }, { it.memberId })
        for ((source, server) in imported) {
            guarded(items, ImportKind.ASSIGNMENT, server.planned.name) {
                items += mcp.revoke(caller, server, members[source].orEmpty().toSet())
            }
        }
        items += plan.skipped
        return items
    }

    private suspend fun server(planned: PlannedServer) {
        val writes = serverSecrets(planned)
        val refusal = planned.conflict ?: mcp.refusal(planned) ?: writes.firstNotNullOfOrNull(secrets::foreign)
        if (refusal != null) {
            items += ImportItem(ImportKind.SERVER, planned.name, ImportAction.SKIPPED, refusal)
            return
        }
        guarded(items, ImportKind.SERVER, planned.name) {
            val ids = writes.associate { write ->
                val (id, line) = secrets.upsert(write)
                items += line
                write.name to id
            }
            val outcome = mcp.server(caller, planned, payload(planned, ids))
            items += outcome.lines
            if (outcome.assignable) imported[planned.source.id] = ImportedServer(planned, outcome.server)
        }
    }

    private fun payload(planned: PlannedServer, ids: Map<String, Uuid>): McpServerPayload {
        val source = planned.source
        val stdio = source.stdio?.let { command ->
            val secretEnv = command.secretEnv.mapValues { (variable, _) ->
                ids.getValue(planned.variableSecretName(variable))
            }
            StdioSpec(command.launcher, command.packageName, command.args, secretEnv = secretEnv)
        }
        return McpServerPayload(
            name = planned.name,
            transport = if (stdio == null) McpTransport.HTTP else McpTransport.STDIO,
            url = source.url.takeIf { stdio == null },
            credentialSecretId = planned.sharedCredential?.let { ids.getValue(planned.secretName) },
            credentialHeader = source.credentialHeader.takeIf { stdio == null },
            stdio = stdio,
            sharing = if (source.perMember) McpSharing.PER_MEMBER else McpSharing.SHARED,
        )
    }

    private suspend fun assignment(planned: PlannedAssignment) {
        val name = "${planned.server.name}/${planned.memberId}"
        val server = imported[planned.server.source.id]
        if (server == null) {
            // Reported, not dropped: the real run has as many lines as its dry run.
            items += ImportItem(ImportKind.ASSIGNMENT, name, ImportAction.SKIPPED, "Its server was not imported.")
            return
        }
        guarded(items, ImportKind.ASSIGNMENT, name) {
            val credential = personalCredential(planned)
            items += mcp.assign(caller, server, planned.memberId, credential)
        }
    }

    /** The member's credential from TeamCode, written as their personal secret, or null to keep the one they have. */
    private suspend fun personalCredential(planned: PlannedAssignment): Uuid? {
        val write = personalSecret(planned)
        val label = "${planned.server.secretName} (personal, ${planned.memberId})"
        val foreign = write?.let(secrets::foreign)
        return when {
            write == null && planned.memberSecret != null -> {
                items += ImportItem(ImportKind.SECRET, label, ImportAction.SKIPPED, NOT_CARRIED)
                null
            }
            write == null -> null
            foreign != null -> {
                // The member's own secret of that name stays theirs, and so does their assignment's credential.
                items += ImportItem(ImportKind.SECRET, label, ImportAction.SKIPPED, foreign)
                null
            }
            else -> {
                val (id, line) = secrets.upsert(write)
                items += line
                id
            }
        }
    }

    private fun serverSecrets(planned: PlannedServer): List<SecretWrite> {
        val id = planned.source.id
        val shared = planned.sharedCredential?.let { SecretWrite(caller, planned.secretName, it, false, id) }
        return listOfNotNull(shared) + planned.source.stdio?.secretEnv.orEmpty().map { (variable, value) ->
            SecretWrite(caller, planned.variableSecretName(variable), value, false, "$id/$variable")
        }
    }

    /** A member's own credential is their personal secret, written as them. */
    private fun personalSecret(planned: PlannedAssignment): SecretWrite? {
        val server = planned.server
        return planned.memberSecret?.takeIf { server.carriesMemberCredential }?.let {
            val member = Caller(caller.orgId, planned.memberId, MemberRole.MEMBER)
            SecretWrite(member, server.secretName, it, true, "${server.source.id}/member/${planned.memberId}")
        }
    }

    private companion object {
        const val NOT_CARRIED = "Only an HTTP per-member server carries a member's credential: set it again in Ostrom."
    }
}

/** Runs [block]; a refusal of the owning module is reported as skipped and the import goes on. */
internal suspend fun guarded(
    items: MutableList<ImportItem>,
    kind: ImportKind,
    name: String,
    block: suspend () -> Unit,
) {
    try {
        block()
    } catch (refused: OstromException) {
        // Refusals only: a cancellation or an unforeseen error still stops the import.
        items += ImportItem(kind, name, ImportAction.SKIPPED, refused.message)
    }
}
