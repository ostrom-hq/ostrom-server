// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.presentation

import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.receipts.domain.usecases.isoMillis
import dev.ostrom.server.rules.domain.models.Decision
import dev.ostrom.server.rules.domain.models.InvalidRuleException
import dev.ostrom.server.rules.domain.models.NewRule
import dev.ostrom.server.rules.domain.models.Rule
import dev.ostrom.server.rules.domain.models.RuleGate
import dev.ostrom.server.rules.domain.models.RuleGateAction
import dev.ostrom.server.rules.domain.models.RuleQuery
import dev.ostrom.server.rules.domain.models.RuleStatus
import dev.ostrom.server.rules.domain.models.RuleStrength
import dev.ostrom.server.rules.domain.services.SelfApprovalValues

/**
 * The rule as the API serves it.
 *
 * @return The DTO.
 */
fun Rule.toDto(): RuleDto = RuleDto(
    id = id,
    statement = statement,
    output = output,
    group = group,
    scope = scope,
    sources = sources.map { RuleSourceDto(it.kind, it.ref, it.url) },
    why = why,
    strength = strength.value,
    confidence = confidence,
    status = status.value,
    foundBy = foundBy,
    foundAt = foundAt.isoMillis(),
    reviewBy = reviewBy,
    decidedBy = decidedBy,
    decidedAt = decidedAt?.isoMillis(),
    origin = origin,
    gate = gate?.toDto(),
)

/**
 * The gate as the API serves it.
 *
 * @return The DTO.
 */
fun RuleGate.toDto(): RuleGateDto = RuleGateDto(
    actions = actions.map { GateActionDto(it.cls, it.label, it.patterns, it.repos, it.tools) },
    approvers = approvers,
    reason = reason,
)

/**
 * The gate a request sends.
 *
 * @return The domain gate, not checked yet.
 */
fun RuleGateDto.toGate(): RuleGate = RuleGate(
    actions = actions.map { RuleGateAction(it.cls, it.label, it.patterns, it.repos, it.tools) },
    approvers = approvers,
    reason = reason,
)

/**
 * The decision as the API serves it.
 *
 * @return The DTO.
 */
fun Decision.toDto(): DecisionDto = DecisionDto(
    id = id,
    at = at.isoMillis(),
    by = by,
    subject = DecisionSubjectDto(subject.kind, subject.id, subject.label),
    action = action,
    before = before,
    after = after,
    receiptId = receiptId,
)

/**
 * The policy as the API serves it.
 *
 * @return The DTO.
 */
fun SelfApprovalPolicy.toDto(): GatePolicyDto = GatePolicyDto(SelfApprovalValues.valueOf(this))

/**
 * The policy a request sets.
 *
 * @return The setting.
 * @throws InvalidRuleException When it is neither `sole-approver` nor `never`.
 */
fun GatePolicyDto.toPolicy(): SelfApprovalPolicy = SelfApprovalValues.of(selfApproval)
    ?: throw InvalidRuleException("selfApproval is sole-approver or never.")

/**
 * The filters of a list.
 *
 * @return The query.
 * @throws InvalidRuleException When the status is unknown.
 */
fun RulesApi.toQuery(): RuleQuery = RuleQuery(
    repo = repo?.trim()?.ifEmpty { null },
    status = status?.let(::ruleStatusOf),
    group = group,
)

/**
 * The rule a request writes.
 *
 * @return The new rule, not checked yet.
 * @throws InvalidRuleException When the strength or the status is unknown.
 */
fun NewRuleRequest.toNewRule(): NewRule = NewRule(
    statement = statement,
    group = group,
    output = output,
    scope = scope,
    strength = strength?.let(::ruleStrengthOf) ?: RuleStrength.REQUIRED,
    why = why,
    status = status?.let(::ruleStatusOf) ?: RuleStatus.ACCEPTED,
    reviewBy = reviewBy,
    gate = gate?.toGate(),
)
