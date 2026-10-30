// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets

import dev.ostrom.server.budgets.domain.BudgetAlert
import dev.ostrom.server.budgets.domain.BudgetAlerts
import dev.ostrom.server.budgets.domain.BudgetDecisionCache
import dev.ostrom.server.budgets.domain.BudgetLimits
import dev.ostrom.server.budgets.domain.BudgetSettings
import dev.ostrom.server.budgets.domain.BudgetsRepository
import dev.ostrom.server.budgets.domain.MemberBudget
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.MemberRole
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
    private val byToken = mapOf("admin-a" to adminA, "member-a" to memberA, "admin-b" to adminB)

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
                override suspend fun authenticate(token: String) =
                    byToken[token]?.let { AuthenticatedPrincipal(token, PrincipalKind.USER) }
            }
        }
        single<CallerResolver> {
            object : CallerResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): Caller? =
                    byToken[principal.subjectId]?.takeIf { orgId == null || it.orgId == orgId }
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

    override suspend fun spentMicros(orgId: Uuid, memberId: Uuid?, since: Instant): Long {
        reads += orgId to memberId
        sinces += since
        return spent[orgId to memberId] ?: 0
    }

    override suspend fun accountSpentMicros(accountId: Uuid, since: Instant): Long = 0
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
    val marked = mutableSetOf<String>()
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

    override suspend fun putOrgLimits(orgId: Uuid, orgLimitMicros: Long?, defaultMemberLimitMicros: Long?, at: Instant) {
        if (orgLimitMicros == null) orgLimits.remove(orgId) else orgLimits[orgId] = orgLimitMicros
        if (defaultMemberLimitMicros == null) defaultLimits.remove(orgId) else defaultLimits[orgId] = defaultMemberLimitMicros
    }

    override suspend fun putMemberLimit(orgId: Uuid, memberId: Uuid, limitMicros: Long, at: Instant): MemberBudget =
        MemberBudget(memberId, limitMicros, at).also { memberLimits[orgId to memberId] = it }

    override suspend fun deleteMemberLimit(orgId: Uuid, memberId: Uuid): Boolean = memberLimits.remove(orgId to memberId) != null

    override suspend fun markAlerted(alert: BudgetAlert, at: Instant): Boolean {
        markFailure?.let { throw it }
        return marked.add("${alert.orgId}/${alert.memberId}/${alert.scope}/${alert.periodStart}/${alert.thresholdPercent}")
    }
}
