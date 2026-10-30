// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.usage

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.MemberRole
import dev.ostrom.server.usage.domain.UsageCursor
import dev.ostrom.server.usage.domain.UsageEvent
import dev.ostrom.server.usage.domain.UsageGroupBy
import dev.ostrom.server.usage.domain.UsageKind
import dev.ostrom.server.usage.domain.UsageRepository
import dev.ostrom.server.usage.domain.UsageTotal
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

    /** The fixed time of the tests: 30 October 2026, 12:00 UTC. */
    val epoch: Instant = Instant.parse("2026-10-30T12:00:00Z")

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
                    byToken[token]?.let { AuthenticatedPrincipal(token, PrincipalKind.USER, emptySet()) }
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

/** Keeps every log event, to assert on what was reported. */
class RecordingLogger : Logger {
    val events = mutableListOf<Pair<LogLevel, String>>()

    override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
        events += level to message
    }

    override fun with(fields: Map<String, Any?>): Logger = this
}

/** A model event of org A, with sensible defaults. */
fun modelEvent(
    costMicros: Long,
    memberId: Uuid? = TestCallers.adminA.memberId,
    orgId: Uuid = TestCallers.orgA,
    at: Instant = TestCallers.epoch,
    name: String = "claude-sonnet-4-5",
    provider: String? = "anthropic",
    accountId: Uuid? = null,
) = UsageEvent(
    id = Uuid.random(),
    orgId = orgId,
    memberId = memberId,
    kind = UsageKind.MODEL,
    name = name,
    provider = provider,
    accountId = accountId,
    inputTokens = 100,
    outputTokens = 10,
    cacheReadTokens = 5,
    cacheWriteTokens = 1,
    costMicros = costMicros,
    durationMillis = 900,
    at = at,
)

/** [UsageRepository] in memory, with the same semantics as the PostgreSQL one. */
class InMemoryUsageRepository : UsageRepository {
    val events = mutableListOf<UsageEvent>()

    /** When set, every call throws it. */
    var failure: Exception? = null

    /** The batch sizes purge was called with. */
    val purges = mutableListOf<Int>()

    override suspend fun insert(events: List<UsageEvent>) {
        failure?.let { throw it }
        this.events += events
    }

    override suspend fun spentMicros(orgId: Uuid, memberId: Uuid?, since: Instant): Long =
        events.filter { it.orgId == orgId && it.at >= since && (memberId == null || it.memberId == memberId) }.sumOf { it.costMicros }

    override suspend fun accountSpentMicros(accountId: Uuid, since: Instant): Long =
        events.filter { it.accountId == accountId && it.at >= since }.sumOf { it.costMicros }

    override suspend fun summary(orgId: Uuid, memberId: Uuid?, since: Instant, groupBy: UsageGroupBy, limit: Int): List<UsageTotal> =
        events.filter { it.orgId == orgId && it.at >= since && (memberId == null || it.memberId == memberId) }
            .filter { groupBy != UsageGroupBy.MODEL || it.kind == UsageKind.MODEL }
            .groupBy {
                when (groupBy) {
                    UsageGroupBy.MEMBER -> it.memberId?.toString()
                    UsageGroupBy.KIND -> it.kind.name
                    UsageGroupBy.MODEL -> it.name
                    UsageGroupBy.PROVIDER -> it.provider
                    UsageGroupBy.ACCOUNT -> it.accountId?.toString()
                }
            }
            .map { (key, group) ->
                UsageTotal(
                    key,
                    group.size.toLong(),
                    group.sumOf { it.inputTokens },
                    group.sumOf { it.outputTokens },
                    group.sumOf { it.cacheReadTokens },
                    group.sumOf { it.cacheWriteTokens },
                    group.sumOf { it.costMicros },
                )
            }
            .sortedByDescending { it.costMicros }
            .take(limit)

    override suspend fun list(orgId: Uuid, memberId: Uuid?, before: UsageCursor?, limit: Int): List<UsageEvent> =
        events.filter { it.orgId == orgId && (memberId == null || it.memberId == memberId) }
            .sortedWith(compareByDescending<UsageEvent> { it.at }.thenByDescending { it.id.toString() })
            .filter { before == null || it.at < before.at || (before.id != null && it.at == before.at && it.id.toString() < before.id.toString()) }
            .take(limit)

    override suspend fun purge(before: Instant, limit: Int): Long {
        purges += limit
        failure?.let { throw it }
        val old = events.filter { it.at < before }.take(limit)
        events.removeAll(old.toSet())
        return old.size.toLong()
    }
}
