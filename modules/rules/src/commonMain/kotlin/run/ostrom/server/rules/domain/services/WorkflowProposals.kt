// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.rules.domain.services

import run.ostrom.server.rules.domain.models.LearnedWorkflow
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Where the analysis stores the process workflows it proposes: the organization's workflows, which the compiler keeps.
 * A proposal is not a decision: nothing is signed, nothing is accepted, and a workflow that exists is never rewritten.
 */
interface WorkflowProposals {
    /**
     * Stores, `proposed`, each workflow whose id is free, while the organization keeps fewer workflows than its limit.
     * A workflow that exists under the id (proposed, edited, accepted or rejected) is left as it is: a person may have
     * edited it, and a rejected one is never proposed again.
     *
     * @param orgId The organization.
     * @param workflows The proposals, in order of preference.
     * @param at When they were found.
     * @return How many were stored.
     */
    suspend fun propose(orgId: Uuid, workflows: List<LearnedWorkflow>, at: Instant): Int
}
