// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.domain

import dev.ostrom.server.kernel.audit.OrgAuditRecord
import dev.ostrom.server.kernel.audit.OrgAuditTrail
import dev.ostrom.server.kernel.audit.auditDetails
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Spending crossed a threshold of a limit. Published once per limit, threshold and period (day or month), across
 * replicas.
 *
 * @property orgId The org.
 * @property memberId The member, for a [BudgetScope.MEMBER] limit; null for the org's.
 * @property scope The limit crossed.
 * @property thresholdPercent 80 or 100.
 * @property limitMicros The limit, USD millionths.
 * @property spentMicros What was spent in the period when the crossing was seen.
 * @property periodStart The day or the month (its first instant, UTC).
 * @property window Whether the limit is a day's or a month's.
 * @property action Whether it stops the calls or only warns.
 * @property workflowId The workflow, for a [BudgetScope.WORKFLOW] limit.
 */
data class BudgetAlert(
    val orgId: Uuid,
    val memberId: Uuid?,
    val scope: BudgetScope,
    val thresholdPercent: Int,
    val limitMicros: Long,
    val spentMicros: Long,
    val periodStart: Instant,
    val window: BudgetWindow = BudgetWindow.MONTH,
    val action: BudgetAction = BudgetAction.BLOCK,
    val workflowId: String? = null,
)

/**
 * Where budget alerts go: the port the notifications module binds in Koin. Until it does, the budgets module uses
 * [NoBudgetAlerts].
 */
interface BudgetAlerts {
    /**
     * Delivers an alert.
     *
     * @param alert The alert.
     */
    suspend fun publish(alert: BudgetAlert)
}

/** Drops every alert: the default until notifications bind their own [BudgetAlerts]. */
class NoBudgetAlerts : BudgetAlerts {
    override suspend fun publish(alert: BudgetAlert) = Unit
}

/**
 * Delivers the alerts to [alerts], and writes a limit that warns, reached (its 100 % alert), to the organization's
 * audit log as `budget.exceeded`, by the budgets ([BUDGETS_ACTOR]): the guard sends each alert once per limit and
 * period, so the line is written once too. A limit that stops is not written: its refusals are the gateway's answers.
 *
 * @param alerts Where the alerts go.
 * @param audit The organization's audit log.
 */
class AuditedBudgetAlerts(private val alerts: BudgetAlerts, private val audit: OrgAuditTrail) : BudgetAlerts {
    override suspend fun publish(alert: BudgetAlert) {
        // The line first: if it cannot be written, nothing is sent and the guard tries both again later, rather than
        // sending the alert again at every check while the audit log is down.
        if (alert.action == BudgetAction.WARN && alert.thresholdPercent == REACHED) audit.record(alert.exceeded())
        alerts.publish(alert)
    }

    private fun BudgetAlert.exceeded(): OrgAuditRecord = OrgAuditRecord(
        orgId = orgId,
        actor = BUDGETS_ACTOR,
        event = "budget.exceeded",
        subjectType = "budget",
        subjectId = workflowId ?: memberId?.toString() ?: ORGANIZATION,
        details = auditDetails(
            "scope" to scope.label(),
            "period" to window.name.lowercase(),
            "action" to action.name.lowercase(),
            "limit_usd" to fixedUsd(limitMicros),
            "spent_usd" to fixedUsd(spentMicros),
            "period_start" to periodStart,
        ),
    )
}

/** The scope as the API spells it. */
private fun BudgetScope.label(): String = when (this) {
    BudgetScope.ORG -> ORGANIZATION
    BudgetScope.MEMBER -> "member"
    BudgetScope.WORKFLOW -> "workflow"
}

/** Who writes `budget.exceeded`: the budgets, a service and not a person (the audit log keeps its kind and id). */
val BUDGETS_ACTOR: AuthenticatedPrincipal = AuthenticatedPrincipal("budgets", PrincipalKind.SERVICE, emptySet())

private const val REACHED = 100
private const val ORGANIZATION = "organization"
private const val MICROS_PER_USD = 1_000_000L
private const val MICROS_DIGITS = 6

/** USD millionths as a fixed-point amount, `12.345678` or `0.0005`: never a float's exponent nor its noise. */
private fun fixedUsd(micros: Long): String {
    val whole = micros / MICROS_PER_USD
    val fraction = (micros % MICROS_PER_USD).toString().padStart(MICROS_DIGITS, '0').trimEnd('0')
    return if (fraction.isEmpty()) whole.toString() else "$whole.$fraction"
}
