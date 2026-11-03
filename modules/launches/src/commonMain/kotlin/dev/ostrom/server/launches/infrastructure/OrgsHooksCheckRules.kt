// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.infrastructure

import dev.ostrom.server.gates.domain.services.GateNodeResolver
import dev.ostrom.server.launches.domain.models.HooksCheck
import dev.ostrom.server.launches.domain.services.HooksCheckRules
import dev.ostrom.server.orgs.domain.DEFAULT_HOOKS_DEADLINE
import dev.ostrom.server.orgs.domain.OrgsRepository
import kotlin.uuid.Uuid

/**
 * [HooksCheckRules] on the orgs module's `hooksDeadlineSeconds` setting and the gates module's [GateNodeResolver]: an
 * agent without hooks is stopped where the organization enforces a gate (its policy is `failMode: closed`, always), and
 * left to run where it enforces none, so that a slow start never stops a workstation without a gate.
 *
 * @param orgs Reads the organization's setting.
 * @param gates Tells whether the organization enforces a gate.
 */
class OrgsHooksCheckRules(
    private val orgs: OrgsRepository,
    private val gates: GateNodeResolver,
) : HooksCheckRules {
    override suspend fun check(orgId: Uuid, repo: String?): HooksCheck = HooksCheck(
        afterSeconds = orgs.get(orgId)?.hooksDeadlineSeconds ?: DEFAULT_HOOKS_DEADLINE,
        stopAgent = gates.enforcesAny(orgId, repo),
    )
}
