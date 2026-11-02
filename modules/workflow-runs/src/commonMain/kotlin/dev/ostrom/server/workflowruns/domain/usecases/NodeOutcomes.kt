// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.domain.usecases

import dev.ostrom.core.api.models.CheckAttestation
import dev.ostrom.core.api.models.CheckRun
import dev.ostrom.core.api.models.Notification
import dev.ostrom.core.api.models.NotificationStatus
import dev.ostrom.core.api.models.PackageMode
import dev.ostrom.core.api.models.ReceiptNode
import dev.ostrom.server.compiler.domain.services.PackageTexts
import dev.ostrom.server.gates.domain.models.CiCheck
import dev.ostrom.server.gates.domain.usecases.SessionGate
import dev.ostrom.server.sessions.domain.models.Session
import dev.ostrom.server.workflowruns.domain.models.PackageNode
import dev.ostrom.server.workflowruns.domain.models.RunPackage

/**
 * What each node of a session's workflows did (prototype `outcomes`): it says only what Ostrom saw, a check run, a
 * notification, a skill opened, never that an instruction was followed. A check node always carries its attestation:
 * the workstation's declaration, unless the forge's own record of the CI running that check on the same commit proves
 * it (`ci`).
 *
 * @param pack The session's package.
 * @param session The session.
 * @param runs Its check runs, oldest first.
 * @param notifications Its notifications, oldest first.
 * @param gates Its gates.
 * @param ci The CI's record of each check node, by node id, read from the forge on the commit its last run reported.
 */
class NodeOutcomes(
    private val pack: RunPackage,
    private val session: Session,
    private val runs: List<CheckRun>,
    private val notifications: List<Notification>,
    private val gates: List<SessionGate>,
    private val ci: Map<String, CiCheck> = emptyMap(),
) {
    /**
     * The nodes the receipt carries: those of the workflows the session made act, or, when none acted, those of the
     * enforced workflows.
     *
     * @return The nodes, in the package's order.
     */
    fun nodes(): List<ReceiptNode> {
        val all = pack.nodes.map { it to outcome(it) }
        val acted = all.filter { (node, outcome) -> node.kind in ACTING && outcome.outcome != NOT_TRIGGERED }
            .map { (node, _) -> node.workflowId }.toSet()
        val kept = if (acted.isEmpty()) all.filter { (node, _) -> node.mode == PackageMode.ENFORCE }
        else all.filter { (node, _) -> node.workflowId in acted }
        return kept.map { it.second }
    }

    private fun outcome(node: PackageNode): ReceiptNode {
        val base = ReceiptNode(node.id, node.workflowId, node.kind, node.label, NOT_TRIGGERED)
        return when (node.kind) {
            "check" -> check(base, node)
            "gate" -> gate(base)
            "notify" -> notify(base)
            "step" -> step(base, node)
            "agent" -> base.copy(outcome = "delivered (sub-agent or skill), not reported")
            "trigger" -> base.copy(outcome = "in the session context")
            else -> base
        }
    }

    // Every check node says where its runs come from (decision 13:23, core#40). A run reaches the server only by
    // POST /checks, from the hooks on the developer's machine, which the agent can read: it is a declaration of the
    // workstation. `ci` is written only when the server read the CI's own run of it on GitHub (decision of 30/10
    // 15:14); `isolated_runner` once an Ostrom runner's proof exists, never before.
    private fun check(base: ReceiptNode, node: PackageNode): ReceiptNode {
        val declared = base.copy(attestation = CheckAttestation.WORKSTATION_DECLARED)
        val mine = runs.filter { it.nodeId == node.id }
        val last = mine.lastOrNull()
        return when {
            last == null -> declared
            node.mode == PackageMode.SHADOW -> declared.copy(outcome = "shadow: would have run ${mine.size}×, not run")
            else -> proven(ran(declared, mine, last), ci[node.id])
        }
    }

    // The CI's run on that commit is the proof: its conclusion is the outcome, the workstation's runs stay as detail.
    private fun proven(reported: ReceiptNode, check: CiCheck?): ReceiptNode {
        if (check == null) return reported
        val outcome = if (check.conclusion == SUCCESS) "passed in CI" else "failed in CI (${check.conclusion})"
        val detail = "CI ${check.name} by ${check.app} on ${check.headSha.take(SHORT_SHA)}; ${reported.detail}"
        val ref = check.url ?: reported.ref
        return reported.copy(outcome = outcome, detail = detail, ref = ref, attestation = CheckAttestation.CI)
    }

    private fun ran(base: ReceiptNode, mine: List<CheckRun>, last: CheckRun): ReceiptNode {
        val failed = mine.count { it.exit != 0 }
        val outcome = when {
            last.exit != 0 -> "failed: action blocked (${plural(mine.size, "run")})"
            failed > 0 -> "passed after ${plural(failed, "failure")} that blocked the action"
            else -> "passed"
        }
        val head = last.head?.takeIf { it.isNotEmpty() }?.let { " on ${it.take(SHORT_SHA)}" }.orEmpty()
        val detail = "${plural(mine.size, "run")}, sandbox ${last.sandbox ?: "?"}, last exit ${last.exit}$head"
        return base.copy(outcome = outcome, detail = detail, ref = last.id)
    }

    // The gates of a session are not tied to a node yet (the gates module records no node): the receipt of the
    // session, cited in the bundle, carries each one with its answer.
    private fun gate(base: ReceiptNode): ReceiptNode = if (gates.isEmpty()) {
        base
    } else {
        base.copy(outcome = "${plural(gates.size, "gate request")} in the session", ref = session.receiptId)
    }

    private fun notify(base: ReceiptNode): ReceiptNode {
        val last = notifications.lastOrNull { it.nodeId == base.id } ?: return base
        val outcome = when (last.status) {
            NotificationStatus.SENT -> "sent to ${last.channel}"
            NotificationStatus.QUEUED -> "queued for ${last.channel}"
            NotificationStatus.LOGGED_ONLY -> "shadow: logged, not sent (${last.channel})"
            else -> "not sent (${last.channel}${last.error?.let { ": $it" }.orEmpty()})"
        }
        return base.copy(outcome = outcome, detail = last.text.take(DETAIL), ref = last.id)
    }

    // A skill delivered is not a skill read, and a skill read is not an instruction followed: say only what was seen.
    private fun step(base: ReceiptNode, node: PackageNode): ReceiptNode {
        val name = "ostrom-wf-${PackageTexts.slug(node.workflowName)}"
        val opened = session.seen.firstOrNull { seen ->
            seen.kind == "skill" && (seen.name == name || seen.name.endsWith(":$name") || "/$name/" in seen.name)
        }
        val outcome = if (opened == null) {
            "delivered as a skill; no sign it was opened"
        } else {
            "skill opened at ${opened.at.toString().substring(HOUR, MINUTE)} (whether it was followed is not checked)"
        }
        return base.copy(outcome = outcome)
    }

    private fun plural(count: Int, noun: String) = if (count == 1) "1 $noun" else "$count ${noun}s"

    private companion object {
        const val NOT_TRIGGERED = "not triggered"
        const val SUCCESS = "success"
        const val SHORT_SHA = 8
        const val DETAIL = 200
        const val HOUR = 11
        const val MINUTE = 16
    }
}

/** The node kinds whose outcome says the session acted. */
private val ACTING = setOf("check", "gate", "notify")
