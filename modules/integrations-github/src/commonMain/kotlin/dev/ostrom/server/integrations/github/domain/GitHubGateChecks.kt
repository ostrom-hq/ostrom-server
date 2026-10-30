// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github.domain

import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GatePullRequest
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.gates.domain.services.GateChannel
import kotlin.time.Clock

/**
 * The `ostrom-gate` check run of a pull request's gate, as the prototype publishes it (`launcher/routes.ts`): waiting
 * while the gate waits, green on the yes (or when nothing gates the merge, or the gate is only logged), red on the no.
 * The check links to the request on the page of the yes. Only a merge gate has a check: a push has no commit on GitHub
 * to put it on before the push.
 *
 * @param repository Installations and published checks.
 * @param api GitHub.
 * @param webOrigin The console's address, for the check's link.
 * @param clock The clock.
 */
class GitHubGateChecks(
    private val repository: GitHubRepository,
    private val api: GitHubApi,
    private val webOrigin: String,
    private val clock: Clock,
) : GateChannel {
    override val name: String = "github-check"

    override suspend fun requested(gate: Gate, requests: List<ApprovalRequest>) = publish(gate, requests)

    override suspend fun decided(gate: Gate, requests: List<ApprovalRequest>) = publish(gate, requests)

    /**
     * Publishes the gate's check, or updates the one already published. A check completed with a conclusion that
     * changed (a "Logged" green asked again once its workflow is accepted) is a new check run: GitHub keeps the last.
     *
     * @param gate The gate.
     * @param requests Its requests.
     * @return The check, or null when the gate is not a pull request's or the organization has no installation.
     */
    suspend fun publish(gate: Gate, requests: List<ApprovalRequest>): GateCheck? {
        val pullRequest = gate.subject.pullRequest
        val repo = gate.subject.repo
        if (pullRequest == null || repo == null) return null
        return repository.installation(gate.orgId)?.let { publish(it, gate, requests, repo, pullRequest) }
    }

    private suspend fun publish(
        installation: GitHubInstallation,
        gate: Gate,
        requests: List<ApprovalRequest>,
        repo: String,
        pullRequest: GatePullRequest,
    ): GateCheck {
        val run = checkOf(gate, requests, pullRequest)
        val held = repository.check(gate.orgId, gate.id)
        val reusable = held?.takeIf { it.conclusion == null || it.conclusion == run.conclusion }
        val id = api.publishCheck(installation.installationId, repo, run, reusable?.checkRunId)
        val check = GateCheck(gate.id, repo, pullRequest.headSha, id, run.conclusion, clock.now())
        repository.saveCheck(gate.orgId, check)
        return check
    }

    private fun checkOf(gate: Gate, requests: List<ApprovalRequest>, pullRequest: GatePullRequest): CheckRun {
        val label = gate.label
        val receipt = "Signed receipt ${gate.receiptId ?: "-"}."
        val (conclusion, title, summary) = when (gate.status) {
            GateStatus.APPROVED, GateStatus.RESERVED, GateStatus.CONSUMED -> {
                val self = if (gate.selfApproved) " (self-approved: sole approver)" else ""
                Triple(SUCCESS, "Approved by ${gate.decidedBy}$self", "Gate \"$label\". $receipt")
            }
            GateStatus.REJECTED -> Triple(FAILURE, "Refused by ${gate.decidedBy}", "Gate \"$label\". $receipt")
            GateStatus.SHADOW -> Triple(
                SUCCESS,
                "Logged (gate not decided yet)",
                "The gate \"$label\" belongs to a workflow still proposed: logged, not enforced.",
            )
            GateStatus.UNGATED -> Triple(
                SUCCESS,
                "No gate for this change",
                "No decided gate covers a merge into ${pullRequest.base} in ${gate.subject.repo}.",
            )
            GateStatus.PENDING -> Triple(
                null,
                "Waiting for a yes from ${waitingFor(gate, requests).joinToString(" or ").ifEmpty { "-" }}",
                "Gate \"$label\" (request ${gate.id}). The approver answers in Slack or in the Ostrom console; " +
                    "this check turns green on the yes.",
            )
        }
        return CheckRun(
            headSha = pullRequest.headSha,
            status = if (conclusion == null) IN_PROGRESS else COMPLETED,
            conclusion = conclusion,
            title = title,
            summary = summary,
            detailsUrl = "${webOrigin.trimEnd('/')}/yes/${gate.id}",
            externalId = gate.id.toString(),
        )
    }

    // Who can still say yes: never the asker while someone else can (the self-approval rule of the gates module).
    private fun waitingFor(gate: Gate, requests: List<ApprovalRequest>): List<String> {
        val pending = requests.filter { it.status == RequestStatus.PENDING }.map { it.approverId }
        return pending.filterNot { approver -> gate.askedBy.any { it.equals(approver, ignoreCase = true) } }
            .ifEmpty { pending }
    }

    private companion object {
        const val SUCCESS = "success"
        const val FAILURE = "failure"
        const val IN_PROGRESS = "in_progress"
        const val COMPLETED = "completed"
    }
}
