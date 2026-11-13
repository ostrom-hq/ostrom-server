// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import run.ostrom.server.gateway.domain.AllowAllSpend
import run.ostrom.server.gateway.domain.BudgetCheck
import run.ostrom.server.gateway.domain.ByteSink
import run.ostrom.server.gateway.domain.ByteSource
import run.ostrom.server.gateway.domain.GatewayOrgConfig
import run.ostrom.server.gateway.domain.GatewaySettings
import run.ostrom.server.gateway.domain.GatewaySettingsRepository
import run.ostrom.server.gateway.domain.InferencePlanner
import run.ostrom.server.gateway.domain.InferenceRefusal
import run.ostrom.server.gateway.domain.InferenceResponder
import run.ostrom.server.gateway.domain.InferenceUpstream
import run.ostrom.server.gateway.domain.InstanceProviderKeys
import run.ostrom.server.gateway.domain.ModelPolicy
import run.ostrom.server.gateway.domain.PriceTable
import run.ostrom.server.gateway.domain.ProviderEndpoints
import run.ostrom.server.gateway.domain.SpendGuard
import run.ostrom.server.gateway.domain.UpstreamAnswer
import run.ostrom.server.gateway.domain.UpstreamRequest
import run.ostrom.server.gateway.domain.UpstreamRequests
import run.ostrom.server.gateway.domain.WireProtocol
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.MemberRole
import run.ostrom.server.secrets.domain.RevealSecretsUseCase
import run.ostrom.server.secrets.domain.SecretUnreadableException
import run.ostrom.server.secrets.domain.SecretValue
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.sharing.domain.AccountCredentials
import run.ostrom.server.sharing.domain.AccountLimits
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.AccountRefreshRepository
import run.ostrom.server.sharing.domain.AccountSlot
import run.ostrom.server.sharing.domain.AccountSlotRepository
import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.AccountTokenCipher
import run.ostrom.server.sharing.domain.AccountTokens
import run.ostrom.server.sharing.domain.NoAccountSpendReader
import run.ostrom.server.sharing.domain.OAuthRefresher
import run.ostrom.server.sharing.domain.QuotaWindow
import run.ostrom.server.sharing.domain.QuotaWindowRepository
import run.ostrom.server.sharing.domain.RefreshOwnership
import run.ostrom.server.sharing.domain.RefreshResult
import run.ostrom.server.sharing.domain.RefreshState
import run.ostrom.server.sharing.domain.RefreshWrite
import run.ostrom.server.sharing.domain.SharedAccount
import run.ostrom.server.sharing.domain.SharedAccountPool
import run.ostrom.server.sharing.domain.SharedAccountRepository
import run.ostrom.server.sharing.domain.SlotView
import run.ostrom.server.sharing.infrastructure.ProviderQuotaHeaderParser
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Orgs and members of the gateway tests. */
object Gw {
    val orgA: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    val orgB: Uuid = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    val adminA = Caller(orgA, Uuid.parse("0000000a-0000-0000-0000-0000000000a1"), MemberRole.ADMIN)
    val memberA = Caller(orgA, Uuid.parse("0000000a-0000-0000-0000-0000000000b1"), MemberRole.MEMBER)
    val otherA = Caller(orgA, Uuid.parse("0000000a-0000-0000-0000-0000000000c1"), MemberRole.MEMBER)
    val adminB = Caller(orgB, Uuid.parse("0000000b-0000-0000-0000-0000000000a1"), MemberRole.ADMIN)
    val epoch: Instant = Instant.parse("2026-10-30T10:00:00Z")
    val endpoints =
        ProviderEndpoints(
            "https://anthropic.test",
            "https://openai.test",
            "https://openrouter.test/api",
            "https://codex.test",
        )
    val cipher = AccountTokenCipher(AesGcmSecretCipher(ByteArray(32) { 7 }))
}

/** A clock the test moves. */
class TestClock(var now: Instant = Gw.epoch) : Clock {
    override fun now(): Instant = now

    fun advance(by: Duration) {
        now += by
    }
}

/** [GatewaySettingsRepository] in memory. */
class InMemorySettings : GatewaySettingsRepository {
    val settings = mutableMapOf<Uuid, GatewaySettings>()
    val policies = mutableMapOf<Uuid, ModelPolicy>()

    /** Reads for calls, to prove a call reads its org once over its tries. */
    var callReads = 0

    override suspend fun forCall(orgId: Uuid): GatewayOrgConfig {
        callReads++
        return GatewayOrgConfig(get(orgId), policy(orgId))
    }

    override suspend fun get(orgId: Uuid) = settings[orgId] ?: GatewaySettings()

    override suspend fun put(orgId: Uuid, settings: GatewaySettings) {
        this.settings[orgId] = settings
    }

    override suspend fun policy(orgId: Uuid) = policies[orgId] ?: ModelPolicy()

    override suspend fun putPolicy(orgId: Uuid, policy: ModelPolicy) {
        policies[orgId] = policy
    }
}

/** Shared accounts, their windows and their refresh intents in memory. */
class InMemoryAccounts :
    SharedAccountRepository,
    QuotaWindowRepository,
    AccountRefreshRepository {
    val accounts = mutableListOf<SharedAccount>()
    val envelopes = mutableMapOf<Uuid, String>()
    val windows = mutableMapOf<Uuid, List<QuotaWindow>>()
    private val pending = mutableSetOf<Uuid>()

    /** Thrown by the writes of what a provider answered (windows, cool-downs): the database down or locked. */
    var answerWrites: Exception? = null

    override suspend fun upsert(account: SharedAccount, envelope: String, expectedVersion: Long?): SharedAccount {
        accounts += account
        envelopes[account.id] = envelope
        return account
    }

    override suspend fun get(orgId: Uuid, id: Uuid) = accounts.firstOrNull { it.orgId == orgId && it.id == id }

    override suspend fun list(orgId: Uuid) = accounts.filter { it.orgId == orgId }

    override suspend fun ready(orgId: Uuid, provider: AccountProvider) =
        accounts.filter { it.orgId == orgId && it.provider == provider && it.serves }

    override suspend fun setOwnership(orgId: Uuid, id: Uuid, ownership: RefreshOwnership): SharedAccount? =
        get(orgId, id)?.let { update(it.copy(ownership = ownership)) }

    override suspend fun update(account: SharedAccount, expectedVersion: Long?): SharedAccount? {
        val index = accounts.indexOfFirst { it.id == account.id }
        if (index < 0) return null
        accounts[index] = account
        return account
    }

    override suspend fun delete(orgId: Uuid, id: Uuid) = accounts.removeAll { it.id == id }

    override suspend fun envelope(orgId: Uuid, id: Uuid) = envelopes[id]

    override suspend fun <T> locked(
        orgId: Uuid,
        id: Uuid,
        decide: suspend (RefreshState?) -> Pair<RefreshWrite, T>,
    ): T {
        val account = get(orgId, id)
        val state = account?.let {
            RefreshState(it.status, envelopes.getValue(id), id in pending, false, 1, it.ownership)
        }
        val (write, result) = decide(state)
        when (write) {
            RefreshWrite.Keep -> Unit
            RefreshWrite.Claim -> pending += id
            RefreshWrite.Retire -> setStatus(orgId, id, AccountStatus.RECONNECT_REQUIRED)
            is RefreshWrite.Reseal -> envelopes[id] = write.envelope
        }
        return result
    }

    override suspend fun save(orgId: Uuid, id: Uuid, credentialVersion: Long, envelope: String): Boolean {
        envelopes[id] = envelope
        return pending.remove(id)
    }

    override suspend fun fail(orgId: Uuid, id: Uuid, credentialVersion: Long, status: AccountStatus): Boolean {
        setStatus(orgId, id, status)
        return id in pending
    }

    override suspend fun setStatus(orgId: Uuid, id: Uuid, status: AccountStatus) {
        get(orgId, id)?.let { update(it.copy(status = status)) }
    }

    override suspend fun setCooldown(orgId: Uuid, id: Uuid, until: Instant) {
        answerWrites?.let { throw it }
        get(orgId, id)?.let { update(it.copy(cooldownUntil = until)) }
    }

    override suspend fun put(orgId: Uuid, id: Uuid, windows: List<QuotaWindow>) {
        answerWrites?.let { throw it }
        this.windows[id] = windows
    }

    override suspend fun byAccount(orgId: Uuid) = windows

    /** Shares an account of [provider] in org A. */
    suspend fun share(provider: AccountProvider, label: String, clock: Clock, org: Uuid = Gw.orgA): SharedAccount {
        val account = SharedAccount(
            Uuid.random(), org, provider, label, null, "acct-$label", Gw.memberA.memberId, 0,
            AccountStatus.READY, AccountLimits(), null, clock.now(),
        )
        val tokens = AccountCredentials("access-$label", "refresh-$label", clock.now() + 1.days)
        return upsert(account, Gw.cipher.seal(tokens, org, account.id))
    }
}

/** A refresher that never renews. */
class NoRefresh : OAuthRefresher {
    override suspend fun refresh(provider: AccountProvider, credentials: AccountCredentials) = RefreshResult.Unavailable
}

/** Slots in memory, never expiring; sessions remembered without bounds. */
class InMemorySlots : AccountSlotRepository {
    private val slots = mutableListOf<AccountSlot>()
    private val affinities = mutableMapOf<String, Uuid>()

    override suspend fun take(
        orgId: Uuid,
        memberId: Uuid,
        session: String?,
        candidates: List<SharedAccount>,
        pick: (SlotView) -> SharedAccount?,
    ): AccountSlot? {
        val key = session?.let { "$orgId:$memberId:$it" }
        val view = SlotView(active(orgId), key?.let { affinities[it] }, candidates.map { it.id }.toSet())
        val account = pick(view) ?: return null
        if (key != null) affinities[key] = account.id
        return AccountSlot(Uuid.random(), account.id).also { slots += it }
    }

    override suspend fun release(orgId: Uuid, slot: AccountSlot) {
        slots.remove(slot)
    }

    override suspend fun renew(orgId: Uuid, slot: AccountSlot) = Unit

    override suspend fun active(orgId: Uuid): Map<Uuid, Int> = slots.groupingBy { it.accountId }.eachCount()
}

/** The pool over [accounts]. */
fun pool(accounts: InMemoryAccounts, clock: Clock) = SharedAccountPool(
    accounts,
    accounts,
    AccountTokens(accounts, Gw.cipher, NoRefresh(), clock, JsonLogger(LogLevel.ERROR, sink = {})),
    InMemorySlots(),
    ProviderQuotaHeaderParser(),
    NoAccountSpendReader(),
    clock,
)

/** Org key secrets by name. */
class FakeSecrets(private val values: Map<String, String> = emptyMap()) : RevealSecretsUseCase {
    var unreadable = false

    override suspend fun invoke(orgId: Uuid, ids: Collection<Uuid>): Map<Uuid, SecretValue> = emptyMap()

    override suspend fun byName(orgId: Uuid, names: Collection<String>): Map<String, SecretValue> {
        if (unreadable) throw SecretUnreadableException()
        return names.mapNotNull { name -> values[name]?.let { name to SecretValue(it) } }.toMap()
    }
}

/** A planner over fakes. */
fun planner(
    settings: InMemorySettings,
    shared: SharedAccountPool,
    secrets: FakeSecrets = FakeSecrets(),
    keys: InstanceProviderKeys = InstanceProviderKeys(),
    spend: SpendGuard = AllowAllSpend,
) = InferencePlanner(
    settings,
    shared,
    secrets,
    BudgetCheck(spend, PriceTable()),
    keys,
    UpstreamRequests(Gw.endpoints),
)

/** What the agent received. */
class RecordingResponder : InferenceResponder {
    var refusal: InferenceRefusal? = null
    var protocol: WireProtocol? = null
    var status: Int? = null
    var headers: List<Pair<String, String>> = emptyList()
    private val chunks = mutableListOf<ByteArray>()

    /** Writing fails after this many writes (the agent went away). */
    var failAfterWrites = Int.MAX_VALUE

    val size: Int get() = chunks.sumOf { it.size }

    val text: String get() = chunks.fold(ByteArray(0)) { all, chunk -> all + chunk }.decodeToString()

    override suspend fun refuse(protocol: WireProtocol, refusal: InferenceRefusal) {
        this.protocol = protocol
        this.refusal = refusal
    }

    override suspend fun stream(status: Int, headers: List<Pair<String, String>>, body: suspend (ByteSink) -> Unit) {
        this.status = status
        this.headers = headers
        var writes = 0
        body(
            ByteSink { bytes, count ->
                check(writes++ < failAfterWrites) { "agent gone" }
                chunks += bytes.copyOf(count)
            },
        )
    }
}

/** Reads [chunks] in turn, as much of each as the buffer holds; an empty chunk is a read of 0. */
class ChunkSource(chunks: List<ByteArray>) : ByteSource {
    private val queue = ArrayDeque(chunks)
    private var offset = 0

    override suspend fun read(buffer: ByteArray): Int {
        val chunk = queue.firstOrNull() ?: return -1
        val count = minOf(chunk.size - offset, buffer.size)
        chunk.copyInto(buffer, 0, offset, offset + count)
        offset += count
        if (offset == chunk.size) {
            queue.removeFirst()
            offset = 0
        }
        return count
    }
}

/** A body of [chunks]. */
fun source(vararg chunks: ByteArray): ByteSource = ChunkSource(chunks.toList())

/** Reads [chunks], then throws [error] instead of ending (the connection reset). */
class FailingSource(chunks: List<ByteArray>, private val error: Exception) : ByteSource {
    private val chunks = ChunkSource(chunks)

    override suspend fun read(buffer: ByteArray): Int {
        val count = chunks.read(buffer)
        if (count < 0) throw error
        return count
    }
}

/** A provider answering each request with the next answer of [answers] (an exception is thrown). */
class FakeUpstream(vararg answers: Any) : InferenceUpstream {
    private val queue = ArrayDeque(answers.toList())
    val requests = mutableListOf<UpstreamRequest>()

    override suspend fun <T> exchange(request: UpstreamRequest, handle: suspend (UpstreamAnswer) -> T): T {
        requests += request
        return when (val next = queue.removeFirst()) {
            is Throwable -> throw next
            else -> handle(next as UpstreamAnswer)
        }
    }
}
