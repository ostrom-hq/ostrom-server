// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.domain

import run.ostrom.server.sharing.domain.AccountGroups
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.AccountSlotRepository
import run.ostrom.server.sharing.domain.ActiveSession
import run.ostrom.server.sharing.domain.FORECAST_HISTORY
import run.ostrom.server.sharing.domain.ForecastHistoryUseCase
import run.ostrom.server.sharing.domain.MemberPoolRepository
import run.ostrom.server.sharing.domain.QuotaHistoryRepository
import run.ostrom.server.sharing.domain.SharedAccount
import run.ostrom.server.sharing.domain.SharedAccountRepository
import run.ostrom.server.sharing.domain.SharingOrgs
import run.ostrom.server.sharing.domain.WindowPacing
import run.ostrom.server.sharing.domain.inPool
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** One pass of the capacity controller over an org (TeamCode's `SqlDirectiveService.update`). */
fun interface UpdateDirectivesUseCase {
    /** Advises every member with a recent session, and each of their recent sessions. */
    suspend operator fun invoke(orgId: Uuid)
}

/**
 * What the controller reads of the org beside the stored advice.
 *
 * @param accounts The shared accounts.
 * @param history Their readings over the last hour.
 * @param slots The sessions' accounts.
 * @param groups The accounts lent to members through groups.
 * @param settings The members' concurrency limits and whether shared accounts serve.
 * @param spend Whether a member's budgets still let a call through.
 * @param pools The members' home pools: a member is paced on the accounts of their pool only, as they are served.
 */
// One port per owner: accounts, history, slots, groups, settings, spend, pools.
@Suppress("LongParameterList")
class PacingSources(
    val accounts: SharedAccountRepository,
    val history: QuotaHistoryRepository,
    val slots: AccountSlotRepository,
    val groups: AccountGroups,
    val settings: GatewaySettingsRepository,
    val spend: SpendGuard,
    val pools: MemberPoolRepository,
)

/**
 * [UpdateDirectivesUseCase]: for each member with a session seen within [ACTIVE], the member-wide advice and each
 * session's, each paced on the providers its sessions' accounts belong to ([RoutePacingUseCase]), stabilized
 * ([PacingHysteresisUseCase]) within its capacity context, and stored for [DirectiveRepository.EXPIRY]. A member
 * without a recent session gets none (TeamCode advises every member, mostly UNKNOWN).
 *
 * @param sources What it reads.
 * @param directives The stored advice.
 * @param clock The time.
 */
class UpdateDirectivesUseCaseImpl(
    private val sources: PacingSources,
    private val directives: DirectiveRepository,
    private val clock: Clock,
) : UpdateDirectivesUseCase {
    private val hysteresis = PacingHysteresisUseCase()
    private val pacing = RoutePacingUseCase()

    override suspend fun invoke(orgId: Uuid) {
        val now = clock.now()
        val sessions = sources.slots.activeSessions(orgId, ACTIVE)
        val writes = if (sessions.isEmpty()) emptyList() else plan(orgId, sessions, now)
        directives.save(orgId, writes)
    }

    private suspend fun plan(orgId: Uuid, sessions: List<ActiveSession>, now: Instant): List<DirectiveWrite> {
        val config = sources.settings.forCall(orgId).settings
        val accounts = sources.accounts.list(orgId)
        val byId = accounts.associateBy { it.id }
        val pacings = pacings(orgId, accounts, now)
        val stored = directives.stored(orgId)
        val bySession = sessions.groupBy { it.memberId }
        // One read for every member advised: TeamCode's `accessible`, the accounts of the member's home pool.
        val homes = sources.pools.poolsOf(orgId, bySession.keys)
        return bySession.flatMap { (member, mine) ->
            val allowed = allowed(orgId, member, accounts.filter { it.inPool(homes[member]) }, now)
            val context = MemberContext(
                member = member,
                maximum = config.memberConcurrency[member.toString()]?.coerceAtLeast(0) ?: 0,
                allowance = sources.spend.available(orgId, member),
                accounts = allowed.map { AccountPacing(it, pacings.getValue(it.id)) },
            )
            val scopes = mapOf("" to mine) + mine.groupBy { it.sessionHash }
            scopes.map { (scope, scoped) ->
                val targets = if (config.useSharedAccounts) {
                    scoped.mapNotNull { byId[it.accountId]?.provider }.toSet()
                } else {
                    emptySet()
                }
                write(context, scope, targets, stored[member to scope], now)
            }
        }
    }

    /** The forecast of each account's windows, from its readings of the last hour at its own ceiling. */
    private suspend fun pacings(
        orgId: Uuid,
        accounts: List<SharedAccount>,
        now: Instant,
    ): Map<Uuid, List<WindowPacing>> {
        val readings = sources.history.recentOfOrg(orgId, (now - FORECAST_HISTORY)..now, MAX_READINGS)
        return accounts.associate { account ->
            val history = ForecastHistoryUseCase(account.limits.utilizationCeiling)
            account.id to readings[account.id].orEmpty().groupBy { it.window }.values
                .mapNotNull { history.evaluate(it, now) }
        }
    }

    /**
     * The accounts of their home pool [member] may use: lent to the whole org, to them by name, or to one of their
     * groups.
     */
    private suspend fun allowed(
        orgId: Uuid,
        member: Uuid,
        accounts: List<SharedAccount>,
        now: Instant,
    ): List<SharedAccount> {
        val named = accounts.filter { it.limits.allowedMembers?.contains(member) != false }
        if (named.size == accounts.size) return accounts
        val lent = sources.groups.lentTo(orgId, member, now)
        return accounts.filter { it in named || it.id in lent }
    }

    private class MemberContext(
        val member: Uuid,
        val maximum: Int,
        val allowance: Boolean,
        val accounts: List<AccountPacing>,
    )

    private fun write(
        context: MemberContext,
        scope: String,
        targets: Set<AccountProvider>,
        previous: StoredDirective?,
        now: Instant,
    ): DirectiveWrite {
        val advice = pacing.advise(targets, context.accounts, context.maximum, now, context.allowance)
        // A different limit, budget standing, provider set or set of usable accounts must not inherit optimistic advice
        // (or an old throttle) from another capacity context.
        val key = listOf(
            context.maximum.toString(),
            context.allowance.toString(),
            targets.map { it.wire }.sorted().joinToString(","),
            context.accounts.map { "${it.account.id}:${it.account.priority}" }.sorted().joinToString(","),
        ).joinToString("\n")
        val memory = previous?.takeIf { it.contextKey == key }?.let {
            PacingMemory(it.directive.advice, it.directive.expiresAt, it.recovery)
        }
        val decision = hysteresis.decide(memory, advice, now)
        // The same advice in the same context keeps its id and creation; anything else is a new advice.
        val kept = previous?.let { p -> p.directive.takeIf { it.advice == decision.advice && p.contextKey == key } }
            ?: AgentDirective(Uuid.random().toString(), context.member.toString(), decision.advice, now, now)
        val directive = kept.copy(expiresAt = now + DirectiveRepository.EXPIRY)
        return DirectiveWrite(context.member, scope, directive, key, decision.recovery)
    }

    /** The bounds. */
    companion object {
        /** A session counts while it called within this (TeamCode's `ACTIVE_TTL`). */
        val ACTIVE: Duration = 2.minutes

        /** Readings read at most per pass (TeamCode's 10,000). */
        const val MAX_READINGS: Int = 10_000
    }
}

/**
 * One pass of the controller over every org that shares accounts: an org whose pass fails leaves the others to run,
 * and the first failure is thrown at the end, for the job's log.
 *
 * @param orgs The orgs that share accounts.
 * @param update One org's pass.
 */
class RunPacingControllerUseCase(private val orgs: SharingOrgs, private val update: UpdateDirectivesUseCase) {
    /** Runs the pass. */
    suspend operator fun invoke() {
        var failure: Exception? = null
        orgs.withAccounts().forEach { orgId ->
            try {
                update(orgId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = failure ?: error
            }
        }
        failure?.let { throw it }
    }
}
