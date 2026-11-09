// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.rules.presentation

import run.ostrom.server.gates.domain.models.SelfApprovalPolicy
import run.ostrom.server.receipts.domain.usecases.isoMillis
import run.ostrom.server.rules.domain.models.Decision
import run.ostrom.server.rules.domain.models.InvalidRuleException
import run.ostrom.server.rules.domain.models.NewRule
import run.ostrom.server.rules.domain.models.Rule
import run.ostrom.server.rules.domain.models.RuleCursor
import run.ostrom.server.rules.domain.models.RuleGate
import run.ostrom.server.rules.domain.models.RuleGateAction
import run.ostrom.server.rules.domain.models.RuleGateCheck
import run.ostrom.server.rules.domain.models.RuleQuery
import run.ostrom.server.rules.domain.models.RuleStatus
import run.ostrom.server.rules.domain.models.RuleStrength
import run.ostrom.server.rules.domain.services.SelfApprovalValues

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
    checks = checks.map { RuleGateCheckDto(it.check, it.attested) },
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
    checks = checks.map { RuleGateCheck(it.check, it.attested) },
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
 * @throws InvalidRuleException When the status is unknown, or the cursor is not one a page gave.
 */
fun RulesApi.toQuery(): RuleQuery = RuleQuery(
    repo = repo?.trim().orEmpty().ifEmpty { null },
    status = status?.let(::ruleStatusOf),
    group = group,
    after = after?.let(RuleCursor::of),
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
    strength = if (strength == null) RuleStrength.REQUIRED else ruleStrengthOf(strength),
    why = why,
    status = if (status == null) RuleStatus.ACCEPTED else ruleStatusOf(status),
    reviewBy = reviewBy,
    gate = gate?.toGate(),
)
