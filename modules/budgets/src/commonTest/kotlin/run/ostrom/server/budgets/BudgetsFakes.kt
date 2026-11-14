// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets

import run.ostrom.server.budgets.domain.BudgetAction
import run.ostrom.server.budgets.domain.BudgetAlert
import run.ostrom.server.budgets.domain.BudgetAlerts
import run.ostrom.server.budgets.domain.BudgetLimit
import run.ostrom.server.budgets.domain.BudgetLimits
import run.ostrom.server.budgets.domain.BudgetSettings
import run.ostrom.server.budgets.domain.BudgetTarget
import run.ostrom.server.budgets.domain.BudgetWindow
import run.ostrom.server.budgets.domain.BudgetsRepository
import run.ostrom.server.budgets.domain.ExceededBudget
import run.ostrom.server.budgets.domain.MemberBudget
import run.ostrom.server.budgets.domain.WorkflowBudget
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalChannel.BROWSER
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.MemberRole
import run.ostrom.server.secrets.domain.RelayScopes
import run.ostrom.server.usage.domain.SpendReader
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Two orgs and their members, each with a bearer token equal to its name (copied from the secrets tests). */
object TestCallers {
    val orgA: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    val orgB: Uuid = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    val adminA = Caller(orgA, Uuid.parse("0000000a-0000-0000-0000-0000000000a1"), MemberRole.ADMIN)
    val memberA = Caller(orgA, Uuid.parse("0000000a-0000-0000-0000-0000000000b1"), MemberRole.MEMBER)
    val adminB = Caller(orgB, Uuid.parse("0000000b-0000-0000-0000-0000000000a1"), MemberRole.OWNER)
    private val byToken = mapOf(
        "admin-a" to adminA,
        "member-a" to memberA,
        "admin-b" to adminB,
        "agent-a" to memberA,
        "device-a" to adminA,
        "cli-read" to adminA,
        "cli-write" to adminA,
        "cli-other" to adminA,
        "stranger" to null,
    )

    private val browserScopes = setOf(RelayScopes.ALL_USER)

    /** The scopes and kind of each token: a browser session has every user scope (`user:*`). */
    private val principals = mapOf(
        "agent-a" to (PrincipalKind.SERVICE to setOf(RelayScopes.AGENT_SESSION)),
        "device-a" to (PrincipalKind.DEVICE to setOf(RelayScopes.ALL_USER)),
        "cli-read" to (PrincipalKind.USER to setOf("budgets:read")),
        "cli-write" to (PrincipalKind.USER to setOf("budgets:write")),
        "cli-other" to (PrincipalKind.USER to setOf(RelayScopes.MCP_READ)),
    )

    /** 30 October 2026, 12:00 UTC: the month runs from 1 October to 1 November. */
    val epoch: Instant = Instant.parse("2026-10-30T12:00:00Z")
    val monthStart: Instant = Instant.parse("2026-10-01T00:00:00Z")
    val nextMonth: Instant = Instant.parse("2026-11-01T00:00:00Z")

    /**
     * Koin overrides: the fake token authenticator and caller resolver, and the clock.
     *
     * @param clock The clock the server reads.
     */
    fun overrides(clock: Clock): Module = module {
        single<Clock> { clock }
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String): AuthenticatedPrincipal? = when {
                    token !in byToken -> null
                    // A token without its own kind is a browser session, with every user scope.
                    token !in principals ->
                        AuthenticatedPrincipal(token, PrincipalKind.USER, browserScopes, channel = BROWSER)
                    else -> principals.getValue(token).let { (kind, scopes) ->
                        AuthenticatedPrincipal(token, kind, scopes)
                    }
                }
            }
        }
        single<CallerResolver> {
            object : CallerResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): Caller? =
                    byToken[principal.subjectId]
            }
        }
    }
}

/** A clock the test moves by hand. */
class TestClock(var now: Instant = TestCallers.epoch) : Clock {
    override fun now(): Instant = now

    fun advance(by: Duration) {
        now += by
    }
}

/** Keeps every log event. */
class RecordingLogger : Logger {
    val events = mutableListOf<Pair<LogLevel, String>>()

    override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
        events += level to message
    }

    override fun with(fields: Map<String, Any?>): Logger = this
}

/** Spend by (org, member), member null for the org's total, and by (org, workflow); counts the reads. */
class FakeSpendReader : SpendReader {
    val spent = mutableMapOf<Pair<Uuid, Uuid?>, Long>()
    val workflowSpent = mutableMapOf<Pair<Uuid, String>, Long>()
    val workflowReads = mutableListOf<Pair<Uuid, String>>()
    val reads = mutableListOf<Pair<Uuid, Uuid?>>()
    val sinces = mutableListOf<Instant>()

    /** Runs during the next read, as something happening while a check is in flight. */
    var duringRead: (suspend () -> Unit)? = null

    override suspend fun spentMicros(orgId: Uuid, memberId: Uuid?, since: Instant): Long {
        reads += orgId to memberId
        sinces += since
        duringRead?.let {
            duringRead = null
            it()
        }
        return spent[orgId to memberId] ?: 0
    }

    override suspend fun accountSpentMicros(orgId: Uuid, accountId: Uuid, since: Instant): Long = 0

    override suspend fun workflowSpentMicros(orgId: Uuid, workflowId: String, since: Instant): Long {
        workflowReads += orgId to workflowId
        return workflowSpent[orgId to workflowId] ?: 0
    }
}

/** Keeps every alert published; can fail. */
class RecordingAlerts : BudgetAlerts {
    val alerts = mutableListOf<BudgetAlert>()
    var failure: Exception? = null

    override suspend fun publish(alert: BudgetAlert) {
        failure?.let { throw it }
        alerts += alert
    }
}

/**
 * [BudgetsRepository] in memory. [orgLimits], [defaultLimits] and [memberLimits] hold the monthly limits that stop
 * (a member's may also warn); [otherLimits] the subjects' other limits, by the day or that warn.
 */
class InMemoryBudgetsRepository : BudgetsRepository {
    val orgLimits = mutableMapOf<Uuid, Long>()
    val defaultLimits = mutableMapOf<Uuid, Long>()
    val memberLimits = mutableMapOf<Pair<Uuid, Uuid>, MemberBudget>()
    val otherLimits = mutableMapOf<Pair<Uuid, BudgetTarget>, List<BudgetLimit>>()
    val exceeded = mutableListOf<Pair<Uuid, ExceededBudget>>()
    val marked = mutableSetOf<List<Any?>>()
    var limitReads = 0
    var markFailure: Exception? = null

    private fun others(orgId: Uuid, target: BudgetTarget) = otherLimits[orgId to target].orEmpty()

    private fun org(orgId: Uuid) =
        listOfNotNull(orgLimits[orgId]?.let { BudgetLimit(it) }) + others(orgId, BudgetTarget.Org)

    private fun defaultMember(orgId: Uuid) =
        listOfNotNull(defaultLimits[orgId]?.let { BudgetLimit(it) }) + others(orgId, BudgetTarget.DefaultMember)

    private fun member(orgId: Uuid, memberId: Uuid) =
        listOfNotNull(memberLimits[orgId to memberId]?.limit) + others(orgId, BudgetTarget.Member(memberId))

    override suspend fun settings(orgId: Uuid, maxOverrides: Int) = BudgetSettings(
        org(orgId),
        defaultMember(orgId),
        memberLimits.filterKeys { it.first == orgId }.values.sortedBy { it.memberId.toString() }.take(maxOverrides),
        otherLimits.filterKeys { it.first == orgId }.mapNotNull { (key, limits) ->
            (key.second as? BudgetTarget.Workflow)?.let { target ->
                limits.map { WorkflowBudget(target.workflowId, it, Instant.fromEpochSeconds(0)) }
            }
        }.flatten().sortedBy { it.workflowId },
    )

    override suspend fun limits(orgId: Uuid, memberId: Uuid, workflowId: String?): BudgetLimits {
        limitReads++
        return BudgetLimits(
            org(orgId),
            member(orgId, memberId).ifEmpty { defaultMember(orgId) },
            workflowId?.let { others(orgId, BudgetTarget.Workflow(it)) }.orEmpty(),
        )
    }

    override suspend fun replaceLimits(orgId: Uuid, target: BudgetTarget, limits: List<BudgetLimit>, at: Instant) {
        val monthly = limits.firstOrNull { it.window == BudgetWindow.MONTH }
        val kept = when (target) {
            BudgetTarget.Org -> orgLimits.store(orgId, monthly)
            BudgetTarget.DefaultMember -> defaultLimits.store(orgId, monthly)
            is BudgetTarget.Member -> {
                val key = orgId to target.memberId
                if (monthly == null) {
                    memberLimits.remove(key)
                } else {
                    memberLimits[key] =
                        MemberBudget(target.memberId, monthly.micros, at, monthly.window, monthly.action)
                }
                monthly
            }
            is BudgetTarget.Workflow -> null
        }
        val rest = limits.filter { it !== kept }
        if (rest.isEmpty()) otherLimits.remove(orgId to target) else otherLimits[orgId to target] = rest
    }

    /** Keeps a monthly limit that stops in this map; returns it, or null when it belongs with the others. */
    private fun MutableMap<Uuid, Long>.store(orgId: Uuid, monthly: BudgetLimit?): BudgetLimit? {
        val stops = monthly?.takeIf { it.action == BudgetAction.BLOCK }
        if (stops == null) remove(orgId) else this[orgId] = stops.micros
        return stops
    }

    override suspend fun removeWorkflow(orgId: Uuid, workflowId: String) {
        otherLimits.remove(orgId to BudgetTarget.Workflow(workflowId))
    }

    override suspend fun markAlerted(alert: BudgetAlert, at: Instant): Boolean {
        markFailure?.let { throw it }
        return marked.add(alert.markKey())
    }

    override suspend fun unmarkAlerted(alert: BudgetAlert) {
        marked.remove(alert.markKey())
    }

    override suspend fun exceeded(
        orgId: Uuid,
        memberId: Uuid?,
        day: Instant,
        month: Instant,
        max: Int,
    ): List<ExceededBudget> = exceeded
        .filter { (org, budget) -> org == orgId && (memberId == null || budget.memberId == memberId) }
        .map { it.second }
        .take(max)

    private fun BudgetAlert.markKey() =
        listOf(orgId, memberId, workflowId, scope, window, action, periodStart, thresholdPercent, limitMicros)
}
