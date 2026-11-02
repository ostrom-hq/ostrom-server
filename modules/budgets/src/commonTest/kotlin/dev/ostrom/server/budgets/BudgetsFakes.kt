// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets

import dev.ostrom.server.budgets.domain.BudgetAlert
import dev.ostrom.server.budgets.domain.BudgetAlerts
import dev.ostrom.server.budgets.domain.BudgetDecisionCache
import dev.ostrom.server.budgets.domain.BudgetLimits
import dev.ostrom.server.budgets.domain.BudgetSettings
import dev.ostrom.server.budgets.domain.BudgetsRepository
import dev.ostrom.server.budgets.domain.MemberBudget
import dev.ostrom.server.budgets.domain.SharedLimit
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalChannel.BROWSER
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.MemberRole
import dev.ostrom.server.secrets.domain.RelayScopes
import dev.ostrom.server.usage.domain.SpendReader
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

/** Spend by (org, member), member null for the org's total; counts the reads. */
class FakeSpendReader : SpendReader {
    val spent = mutableMapOf<Pair<Uuid, Uuid?>, Long>()
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

/** Remembers the orgs invalidated. */
class RecordingCache : BudgetDecisionCache {
    val invalidated = mutableListOf<Uuid>()

    override suspend fun invalidate(orgId: Uuid) {
        invalidated += orgId
    }
}

/** [BudgetsRepository] in memory. */
class InMemoryBudgetsRepository : BudgetsRepository {
    val orgLimits = mutableMapOf<Uuid, Long>()
    val defaultLimits = mutableMapOf<Uuid, Long>()
    val memberLimits = mutableMapOf<Pair<Uuid, Uuid>, MemberBudget>()
    val marked = mutableSetOf<List<Any?>>()
    var limitReads = 0
    var markFailure: Exception? = null

    override suspend fun settings(orgId: Uuid, maxOverrides: Int) = BudgetSettings(
        orgLimits[orgId],
        defaultLimits[orgId],
        memberLimits.filterKeys { it.first == orgId }.values.sortedBy { it.memberId.toString() }.take(maxOverrides),
    )

    override suspend fun limits(orgId: Uuid, memberId: Uuid): BudgetLimits {
        limitReads++
        return BudgetLimits(orgLimits[orgId], memberLimits[orgId to memberId]?.limitMicros ?: defaultLimits[orgId])
    }

    override suspend fun putOrgLimits(
        orgId: Uuid,
        orgLimitMicros: Long?,
        defaultMemberLimitMicros: Long?,
        at: Instant,
    ) {
        if (orgLimitMicros == null) orgLimits.remove(orgId) else orgLimits[orgId] = orgLimitMicros
        if (defaultMemberLimitMicros == null) {
            defaultLimits.remove(orgId)
        } else {
            defaultLimits[orgId] = defaultMemberLimitMicros
        }
    }

    override suspend fun putSharedLimit(orgId: Uuid, limit: SharedLimit, limitMicros: Long?, at: Instant) {
        val limits = if (limit == SharedLimit.ORG) orgLimits else defaultLimits
        if (limitMicros == null) limits.remove(orgId) else limits[orgId] = limitMicros
    }

    override suspend fun putMemberLimit(orgId: Uuid, memberId: Uuid, limitMicros: Long, at: Instant): MemberBudget =
        MemberBudget(memberId, limitMicros, at).also { memberLimits[orgId to memberId] = it }

    override suspend fun deleteMemberLimit(orgId: Uuid, memberId: Uuid): Boolean =
        memberLimits.remove(orgId to memberId) != null

    override suspend fun markAlerted(alert: BudgetAlert, at: Instant): Boolean {
        markFailure?.let { throw it }
        return marked.add(alert.markKey())
    }

    override suspend fun unmarkAlerted(alert: BudgetAlert) {
        marked.remove(alert.markKey())
    }

    private fun BudgetAlert.markKey() = listOf(orgId, memberId, scope, periodStart, thresholdPercent, limitMicros)
}
