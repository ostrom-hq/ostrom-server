// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.infrastructure

import dev.ostrom.core.api.models.CheckAttestation
import dev.ostrom.server.gates.domain.models.GateSubject
import dev.ostrom.server.gates.domain.services.CiChecks
import dev.ostrom.server.gates.domain.services.GateCheckProof
import dev.ostrom.server.gates.domain.services.GateCheckProofs
import dev.ostrom.server.workflowruns.domain.repositories.RunQuery
import dev.ostrom.server.workflowruns.domain.repositories.WorkflowRunsRepository
import kotlin.uuid.Uuid

/**
 * The proof of a check a gate needs, for the gates module, from what the server holds beyond the workstation's word
 * (decision 13:23, core#40), the way a receipt's check node reads it: the session's last run of the check, attested
 * `isolated_runner` when the cloud session's runner reported it; else the CI's own successful run of the check on
 * every commit the gate covers (the pull request's head, or each pushed commit), read from the forge (`ci`); else the
 * last run as the workstation declared it.
 *
 * @param repository The check runs, in the gate's organization.
 * @param ci The forge's record of a CI run, read at the first call: the gates module asks for this port while it
 *   binds the defaults of the others, the forge's among them.
 */
class RunsGateCheckProofs(
    private val repository: WorkflowRunsRepository,
    ci: () -> CiChecks,
) : GateCheckProofs {
    private val ci by lazy(ci)

    override suspend fun of(orgId: Uuid, sessionId: String?, subject: GateSubject, check: String): GateCheckProof {
        val run = if (sessionId == null) null else lastRun(orgId, sessionId, check)
        return when {
            run != null && run.passed && run.attestation == CheckAttestation.ISOLATED_RUNNER -> run
            passedInCi(orgId, subject, check) -> GateCheckProof(CheckAttestation.CI, passed = true)
            else -> run ?: GateCheckProof(attestation = null, passed = false)
        }
    }

    private suspend fun lastRun(orgId: Uuid, sessionId: String, check: String): GateCheckProof? {
        val last = repository.checks(orgId, RunQuery(sessionId = sessionId, nodeId = check, limit = 1)).firstOrNull()
            ?: return null
        val attestation = repository.attestations(orgId, sessionId)[last.id] ?: CheckAttestation.WORKSTATION_DECLARED
        // A run of another script than the package's never passes, as in the receipt (AUDIT-SECU-RUNNER-2 R1).
        return GateCheckProof(attestation, passed = last.exit == 0 && last.tampered != true)
    }

    // The check's id is its name on the forge. A deleted ref has no commit to prove; a gate with no commit, none.
    private suspend fun passedInCi(orgId: Uuid, subject: GateSubject, check: String): Boolean {
        val repo = subject.realRepo ?: subject.repo ?: return false
        val pullRequest = subject.pullRequest
        val push = subject.push
        val commits = when {
            pullRequest != null -> listOf(pullRequest.headSha)
            push != null -> push.refs.map { it.sha }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
        return commits.isNotEmpty() && commits.all { ci.latest(orgId, repo, it, check)?.conclusion == SUCCESS }
    }

    private companion object {
        const val SUCCESS = "success"
    }
}
