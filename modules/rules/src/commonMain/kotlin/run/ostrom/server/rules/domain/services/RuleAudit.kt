// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.rules.domain.services

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.rules.domain.models.AnalysisRun
import run.ostrom.server.rules.domain.models.Decider
import run.ostrom.server.rules.domain.models.Decision

/**
 * The lines the rules write to the organization's audit log (`GET /api/v1/audit`), after the change is committed:
 * `rule.created`, `rule.accepted`, `rule.edited`, `rule.rejected`, `rule.proposed` (reopened) and `rule.deleted`, one
 * per signed decision, and `rule.analysis.started` when someone starts an analysis or a replay. Metadata only: the
 * decision's id leads to the statement and its receipt, the log never holds the statement itself.
 */
object RuleAudit {
    private const val RULE = "rule"
    private const val ANALYSIS = "analysis"

    /**
     * The line of a signed decision on a rule.
     *
     * @param decider Who decided.
     * @param decision The decision.
     * @return The line: `rule.created` for a new rule (with its status), else `rule.<action>`.
     */
    fun of(decider: Decider, decision: Decision): OrgAuditRecord {
        val created = decision.before == null
        return OrgAuditRecord(
            orgId = decider.orgId,
            actor = decider.actor,
            event = "$RULE.${if (created) "created" else decision.action}",
            subjectType = RULE,
            subjectId = decision.subject.id,
            details = auditDetails("decision" to decision.id, "status" to decision.action.takeIf { created }),
        )
    }

    /**
     * The line of a started analysis or replay.
     *
     * @param decider Who started it.
     * @param run The run, as recorded.
     * @return `rule.analysis.started`, with the run's kind.
     */
    fun started(decider: Decider, run: AnalysisRun): OrgAuditRecord = OrgAuditRecord(
        orgId = decider.orgId,
        actor = decider.actor,
        event = "$RULE.$ANALYSIS.started",
        subjectType = ANALYSIS,
        subjectId = run.id,
        details = auditDetails("kind" to run.kind.value),
    )
}
