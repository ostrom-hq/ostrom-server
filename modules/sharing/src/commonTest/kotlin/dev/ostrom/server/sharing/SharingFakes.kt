package dev.ostrom.server.sharing

import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.MemberRole
import dev.ostrom.server.secrets.domain.SecretCipher
import dev.ostrom.server.sharing.domain.AccountCredentials
import dev.ostrom.server.sharing.domain.AccountProvider
import dev.ostrom.server.sharing.domain.AccountStatus
import dev.ostrom.server.sharing.domain.OAuthRefresher
import dev.ostrom.server.sharing.domain.QuotaWindow
import dev.ostrom.server.sharing.domain.RefreshResult
import dev.ostrom.server.sharing.domain.SharedAccount
import dev.ostrom.server.sharing.domain.SharedAccountRepository
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

    override suspend fun refresh(provider: AccountProvider, credentials: AccountCredentials): RefreshResult {
        calls++
        return result
    }
}

/** [SharedAccountRepository] in memory. */
class InMemoryAccounts : SharedAccountRepository {
    val accounts = mutableListOf<SharedAccount>()
    val envelopes = mutableMapOf<Uuid, String>()
    val windows = mutableMapOf<Uuid, MutableList<QuotaWindow>>()

    override suspend fun upsert(account: SharedAccount, envelope: String): SharedAccount {
        val index = accounts.indexOfFirst { it.orgId == account.orgId && it.provider == account.provider && it.providerAccountId == account.providerAccountId }
        if (index >= 0) accounts[index] = account.copy(id = accounts[index].id) else accounts += account
        val stored = accounts.first { it.orgId == account.orgId && it.providerAccountId == account.providerAccountId && it.provider == account.provider }
        envelopes[stored.id] = envelope
        return stored
    }

    override suspend fun get(orgId: Uuid, id: Uuid) = accounts.firstOrNull { it.orgId == orgId && it.id == id }

    override suspend fun list(orgId: Uuid) = accounts.filter { it.orgId == orgId }.sortedWith(compareByDescending<SharedAccount> { it.priority }.thenBy { it.label })

    override suspend fun ready(orgId: Uuid, provider: AccountProvider) =
        accounts.filter { it.orgId == orgId && it.provider == provider && it.status == AccountStatus.READY }

    override suspend fun update(account: SharedAccount): SharedAccount? {
        val index = accounts.indexOfFirst { it.orgId == account.orgId && it.id == account.id }
        if (index < 0) return null
        accounts[index] = account
        return account
    }

    override suspend fun delete(orgId: Uuid, id: Uuid) = accounts.removeAll { it.orgId == orgId && it.id == id }

    override suspend fun envelope(orgId: Uuid, id: Uuid) = envelopes[id]?.takeIf { get(orgId, id) != null }

    override suspend fun putEnvelope(orgId: Uuid, id: Uuid, envelope: String) {
        envelopes[id] = envelope
    }

    override suspend fun setStatus(orgId: Uuid, id: Uuid, status: AccountStatus) {
        get(orgId, id)?.let { update(it.copy(status = status)) }
    }

    override suspend fun setCooldown(orgId: Uuid, id: Uuid, until: Instant?) {
        get(orgId, id)?.let { update(it.copy(cooldownUntil = until)) }
    }

    override suspend fun putWindows(orgId: Uuid, id: Uuid, windows: List<QuotaWindow>) {
        val list = this.windows.getOrPut(id) { mutableListOf() }
        windows.forEach { window ->
            list.removeAll { it.window == window.window }
            list += window
        }
    }

    override suspend fun windows(orgId: Uuid) = windows.filterKeys { id -> accounts.any { it.id == id && it.orgId == orgId } }
}
