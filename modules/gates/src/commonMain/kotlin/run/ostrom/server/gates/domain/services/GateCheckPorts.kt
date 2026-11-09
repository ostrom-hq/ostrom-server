// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gates.domain.services

import run.ostrom.core.api.models.CheckAttestation
import run.ostrom.server.gates.domain.models.GateNode
import run.ostrom.server.gates.domain.models.GateSubject
import kotlin.uuid.Uuid

/**
 * A check a gate needs before its action passes (contract `RuleGateCheck`).
 *
 * @property check The id of the check node in the organization's workflows.
 * @property attested When true, only a run attested by an Ostrom runner or the CI satisfies the gate.
 */
data class GateCheckNeed(val check: String, val attested: Boolean)

/**
 * Where the last run of a check comes from, as the server can tell, for a gate that needs it.
 *
 * @property attestation `isolated_runner` when the session's runner reported it, `ci` when the forge records the CI
 *   running it on the commit the gate covers, `workstation_declared` when only the workstation reported it; null when
 *   nothing ran it.
 * @property passed Whether that run passed.
 */
data class GateCheckProof(val attestation: CheckAttestation?, val passed: Boolean)

/** The checks the gate of an action needs (rules module: a rule's `gate.checks`). */
interface GateCheckNeeds {
    /**
     * Lists the checks a gate needs.
     *
     * @param orgId The organization.
     * @param node The gate node that covers the action.
     * @return Its checks; empty when it needs none.
     */
    suspend fun of(orgId: Uuid, node: GateNode): List<GateCheckNeed>
}

/** How a check of the action was run (workflow-runs module), never as the workstation alone says. */
interface GateCheckProofs {
    /**
     * Reads the proof of a check: the session's last run of it, or the CI's run on the commit the gate covers.
     *
     * @param orgId The organization.
     * @param sessionId The session that asks, or null outside one (the GitHub check).
     * @param subject What the gate covers: its repository and commits.
     * @param check The check node id.
     * @return Where its last run comes from.
     */
    suspend fun of(orgId: Uuid, sessionId: String?, subject: GateSubject, check: String): GateCheckProof
}
