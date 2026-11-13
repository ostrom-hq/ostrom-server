// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.sharing

import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.MemberRole
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.sharing.domain.AccountChangedException
import run.ostrom.server.sharing.domain.AccountCredentials
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.AccountRefreshRepository
import run.ostrom.server.sharing.domain.AccountSlot
import run.ostrom.server.sharing.domain.AccountSlotRepository
import run.ostrom.server.sharing.domain.AccountSpendReader
import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.AccountTokenCipher
import run.ostrom.server.sharing.domain.AccountTokens
import run.ostrom.server.sharing.domain.OAuthRefresher
import run.ostrom.server.sharing.domain.QuotaWindow
import run.ostrom.server.sharing.domain.QuotaWindowRepository
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
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Orgs and callers of the sharing tests. */
object Sharing {
    val orgA: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    val orgB: Uuid = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    val adminA = Caller(orgA, Uuid.parse("0000000a-0000-0000-0000-0000000000a1"), MemberRole.ADMIN)
    val memberA = Caller(orgA, Uuid.parse("0000000a-0000-0000-0000-0000000000b1"), MemberRole.MEMBER)
    val otherA = Caller(orgA, Uuid.parse("0000000a-0000-0000-0000-0000000000c1"), MemberRole.MEMBER)
    val adminB = Caller(orgB, Uuid.parse("0000000b-0000-0000-0000-0000000000a1"), MemberRole.OWNER)
    val epoch: Instant = Instant.parse("2026-10-30T12:00:00Z")
}

/** A clock the test moves. */
class TestClock(var now: Instant = Sharing.epoch) : Clock {
    override fun now(): Instant = now

    fun advance(by: Duration) {
        now += by
    }
}

/** Reversible "encryption" bound to its context, so a wrong context does not open. */
class ContextCipher : SecretCipher {
    override suspend fun seal(plaintext: ByteArray, context: String): String = "$context|" + plaintext.decodeToString()

    override suspend fun open(envelope: String, context: String): ByteArray? =
        envelope.takeIf { it.startsWith("$context|") }?.removePrefix("$context|")?.encodeToByteArray()
}

/** A refresher answering what the test says, counting its calls. */
class FakeRefresher(var result: RefreshResult = RefreshResult.Unavailable) : OAuthRefresher {
    var calls = 0
    var onRefresh: suspend () -> Unit = {}

    override suspend fun refresh(provider: AccountProvider, credentials: AccountCredentials): RefreshResult {
        calls++
        onRefresh()
        return result
    }
}

/** [SharedAccountRepository], [QuotaWindowRepository] and [AccountRefreshRepository] in memory. */
class InMemoryAccounts :
    SharedAccountRepository,
    QuotaWindowRepository,
    AccountRefreshRepository {
    val accounts = mutableListOf<SharedAccount>()
    val envelopes = mutableMapOf<Uuid, String>()
    val windows = mutableMapOf<Uuid, MutableList<QuotaWindow>>()

    /** Accounts whose refresh intent is committed. */
    val pending = mutableSetOf<Uuid>()

    /** Accounts whose intent is older than the limit by the database's clock. */
    val staleIntents = mutableSetOf<Uuid>()

    /** The tokens' version of each account (1 when never changed). */
    val credentialVersions = mutableMapOf<Uuid, Long>()

    /** Row looks taken, in order. */
    var looks = 0

    /** Rows vanish between a read and the write that follows (a concurrent delete). */
    var vanishing = false

    /** Reading tokens fails (the database is down). */
    var failing = false

    override suspend fun upsert(account: SharedAccount, envelope: String, expectedVersion: Long?): SharedAccount {
        val index = accounts.indexOfFirst { it.sameProviderAccount(account) }
        val current = accounts.getOrNull(index)
        if (expectedVersion != null && (current?.version ?: 0) != expectedVersion) throw AccountChangedException()
        val stored = if (current == null) {
            account.copy(version = 1).also { accounts += it }
        } else {
            account.copy(id = current.id, version = current.version + 1).also { accounts[index] = it }
        }
        envelopes[stored.id] = envelope
        pending -= stored.id
        staleIntents -= stored.id
        credentialVersions[stored.id] = credentialVersion(stored.id) + 1
        return stored
    }

    private fun credentialVersion(id: Uuid) = credentialVersions[id] ?: 1

    override suspend fun <T> locked(
        orgId: Uuid,
        id: Uuid,
        decide: suspend (RefreshState?) -> Pair<RefreshWrite, T>,
    ): T {
        check(!failing) { "database down" }
        looks++
        val account = get(orgId, id)
        val envelope = envelopes[id]
        val state = if (account == null || envelope == null) {
            null
        } else {
            RefreshState(account.status, envelope, id in pending, id in staleIntents, credentialVersion(id))
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
        if (id !in pending || credentialVersion(id) != credentialVersion) return false
        envelopes[id] = envelope
        pending -= id
        credentialVersions[id] = credentialVersion + 1
        return true
    }

    override suspend fun fail(orgId: Uuid, id: Uuid, credentialVersion: Long, status: AccountStatus): Boolean {
        val current = id in pending && credentialVersion(id) == credentialVersion
        if (current) setStatus(orgId, id, status)
        return current
    }

    private fun SharedAccount.sameProviderAccount(other: SharedAccount) =
        orgId == other.orgId && provider == other.provider && providerAccountId == other.providerAccountId

    override suspend fun get(orgId: Uuid, id: Uuid) = accounts.firstOrNull { it.orgId == orgId && it.id == id }

    override suspend fun list(orgId: Uuid) = accounts.filter { it.orgId == orgId }
        .sortedWith(compareByDescending<SharedAccount> { it.priority }.thenBy { it.label })

    override suspend fun ready(orgId: Uuid, provider: AccountProvider) =
        accounts.filter { it.orgId == orgId && it.provider == provider && it.status == AccountStatus.READY }

    override suspend fun update(account: SharedAccount, expectedVersion: Long?): SharedAccount? {
        val index = accounts.indexOfFirst { it.orgId == account.orgId && it.id == account.id }
        val current = accounts.getOrNull(index)
        val stale = expectedVersion != null && current?.version != expectedVersion
        if (current == null || vanishing || stale) return null
        return account.copy(version = current.version + 1).also { accounts[index] = it }
    }

    override suspend fun delete(orgId: Uuid, id: Uuid) =
        !vanishing && accounts.removeAll { it.orgId == orgId && it.id == id }

    override suspend fun envelope(orgId: Uuid, id: Uuid): String? {
        check(!failing) { "database down" }
        return envelopes[id]?.takeIf { get(orgId, id) != null }
    }

    override suspend fun setStatus(orgId: Uuid, id: Uuid, status: AccountStatus) {
        get(orgId, id)?.let { update(it.copy(status = status)) }
    }

    override suspend fun setCooldown(orgId: Uuid, id: Uuid, until: Instant) {
        get(orgId, id)?.let { update(it.copy(cooldownUntil = until)) }
    }

    override suspend fun put(orgId: Uuid, id: Uuid, windows: List<QuotaWindow>) {
        val list = this.windows.getOrPut(id) { mutableListOf() }
        windows.forEach { window ->
            list.removeAll { it.window == window.window }
            list += window
        }
    }

    override suspend fun byAccount(orgId: Uuid) =
        windows.filterKeys { id -> accounts.any { it.id == id && it.orgId == orgId } }
}

/**
 * [AccountSlotRepository] in memory, expiring by [clock] like the database's clock; the accounts' current status read
 * from [accounts] when given (an account no longer READY is not serving).
 */
class InMemorySlots(private val clock: Clock, private val accounts: InMemoryAccounts? = null) : AccountSlotRepository {
    private class Slot(val orgId: Uuid, val slot: AccountSlot, val expiresAt: Instant)

    private class Affinity(val accountId: Uuid, val expiresAt: Instant)

    private val slots = mutableListOf<Slot>()
    private val affinities = mutableMapOf<Triple<Uuid, Uuid, String>, Affinity>()

    override suspend fun take(
        orgId: Uuid,
        memberId: Uuid,
        session: String?,
        candidates: List<SharedAccount>,
        pick: (SlotView) -> SharedAccount?,
    ): AccountSlot? {
        val now = clock.now()
        val serving = candidates.filter { candidate ->
            accounts == null || accounts.get(orgId, candidate.id)?.status == AccountStatus.READY
        }.map { it.id }.toSet()
        val key = session?.let { Triple(orgId, memberId, it) }
        val remembered = key?.let { affinities[it] }?.takeIf { it.expiresAt > now }?.accountId
        val account = pick(SlotView(active(orgId), remembered, serving)) ?: return null
        val slot = AccountSlot(Uuid.random(), account.id)
        slots += Slot(orgId, slot, now + AccountSlotRepository.SLOT_LEASE)
        if (key != null) {
            affinities.entries.removeAll { (k, v) -> k.first == orgId && k.second == memberId && v.expiresAt <= now }
            val member = affinities.keys.count { it.first == orgId && it.second == memberId }
            val org = affinities.count { (k, v) -> k.first == orgId && v.expiresAt > now }
            val room = member < AccountSlotRepository.MAX_MEMBER_AFFINITIES &&
                org < AccountSlotRepository.MAX_ORG_AFFINITIES
            if (key in affinities || room) {
                affinities[key] = Affinity(account.id, now + AccountSlotRepository.AFFINITY)
            }
        }
        return slot
    }

    override suspend fun release(orgId: Uuid, slot: AccountSlot) {
        slots.removeAll { it.orgId == orgId && it.slot.id == slot.id }
    }

    override suspend fun active(orgId: Uuid): Map<Uuid, Int> = slots
        .filter { it.orgId == orgId && it.expiresAt > clock.now() }
        .groupingBy { it.slot.accountId }
        .eachCount()
}

/** The pool and its collaborators over [InMemoryAccounts], as the module wires them. */
fun pool(
    repository: InMemoryAccounts,
    cipher: AccountTokenCipher,
    refresher: OAuthRefresher,
    spend: AccountSpendReader,
    clock: TestClock,
) = pool(repository, tokens(repository, cipher, refresher, clock), spend, clock)

/** The pool over [repository] with [tokens], its slots in [slots]. */
fun pool(
    repository: InMemoryAccounts,
    tokens: AccountTokens,
    spend: AccountSpendReader,
    clock: TestClock,
    slots: AccountSlotRepository = InMemorySlots(clock, repository),
) = SharedAccountPool(repository, repository, tokens, slots, ProviderQuotaHeaderParser(), spend, clock)

/** The tokens over [repository], warnings written to [logged]. */
fun tokens(
    repository: InMemoryAccounts,
    cipher: AccountTokenCipher,
    refresher: OAuthRefresher,
    clock: TestClock,
    logged: (String) -> Unit = {},
) = AccountTokens(repository, cipher, refresher, clock, JsonLogger(LogLevel.WARN, sink = logged))
