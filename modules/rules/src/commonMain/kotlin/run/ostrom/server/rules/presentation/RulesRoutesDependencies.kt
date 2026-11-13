// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules.presentation

import run.ostrom.server.gates.domain.services.GateActorResolver
import run.ostrom.server.kernel.auth.ServiceKeyAudit
import run.ostrom.server.rules.domain.usecases.BulkUpdateRulesUseCase
import run.ostrom.server.rules.domain.usecases.CreateRuleUseCase
import run.ostrom.server.rules.domain.usecases.DeleteRuleUseCase
import run.ostrom.server.rules.domain.usecases.GetGatePolicyUseCase
import run.ostrom.server.rules.domain.usecases.GetRuleUseCase
import run.ostrom.server.rules.domain.usecases.ListDecisionsUseCase
import run.ostrom.server.rules.domain.usecases.ListRulesUseCase
import run.ostrom.server.rules.domain.usecases.SetGatePolicyUseCase
import run.ostrom.server.rules.domain.usecases.UpdateRuleUseCase
import run.ostrom.server.secrets.domain.CallerResolver

/**
 * What the rules routes need.
 *
 * @property callers Resolves the caller's organization and role.
 * @property actors Names the person who decides (their GitHub login), as the gates name their approvers.
 * @property listRules Lists rules.
 * @property getRule Reads a rule.
 * @property createRule Writes a rule.
 * @property updateRule Decides or edits a rule.
 * @property bulkUpdateRules Decides several rules.
 * @property deleteRule Deletes a rule.
 * @property listDecisions Lists the decisions.
 * @property getGatePolicy Reads the self-approval policy.
 * @property setGatePolicy Sets it.
 * @property serviceKeys Audits the calls of a service key, which reads the rules for its organization's CI.
 */
data class RulesRoutesDependencies(
    val callers: CallerResolver,
    val actors: GateActorResolver,
    val listRules: ListRulesUseCase,
    val getRule: GetRuleUseCase,
    val createRule: CreateRuleUseCase,
    val updateRule: UpdateRuleUseCase,
    val bulkUpdateRules: BulkUpdateRulesUseCase,
    val deleteRule: DeleteRuleUseCase,
    val listDecisions: ListDecisionsUseCase,
    val getGatePolicy: GetGatePolicyUseCase,
    val setGatePolicy: SetGatePolicyUseCase,
    val serviceKeys: ServiceKeyAudit,
)
