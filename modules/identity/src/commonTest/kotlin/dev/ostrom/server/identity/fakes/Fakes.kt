package dev.ostrom.server.identity.fakes

import dev.ostrom.server.identity.domain.ApiToken
import dev.ostrom.server.identity.domain.ApiTokensRepository
import dev.ostrom.server.identity.domain.AuditEntry
import dev.ostrom.server.identity.domain.AuditEvent
import dev.ostrom.server.identity.domain.AuditLog
import dev.ostrom.server.identity.domain.AuditRepository
import dev.ostrom.server.identity.domain.DeviceAuthorization
import dev.ostrom.server.identity.domain.DeviceAuthorizationStatus
import dev.ostrom.server.identity.domain.DeviceAuthorizationsRepository
import dev.ostrom.server.identity.domain.DuplicateIdentityException
import dev.ostrom.server.identity.domain.RateLimiter
import dev.ostrom.server.identity.domain.SecretGenerator
import dev.ostrom.server.identity.domain.SecretHasher
import dev.ostrom.server.identity.domain.TokenKind
import dev.ostrom.server.identity.domain.User
import dev.ostrom.server.identity.domain.UserEmail
import dev.ostrom.server.identity.domain.UsersRepository
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.identity.domain.WebSession
import dev.ostrom.server.identity.domain.WebSessionsRepository
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

// Hand-written fakes (Kotlin/Native has no MockK), shared by the unit tests of the identity module.

class SettableClock(var now: Instant = Instant.parse("2026-10-30T10:00:00Z")) : Clock {
    override fun now(): Instant = now
}

class CountingSecretGenerator : SecretGenerator {
    var secrets = 0
    var codes = 0

    override fun secret(): String = "secret${++secrets}"

    override fun userCode(): String = listOf("BCDF-GHJK", "MNPQ-RSTV", "WXZB-CDFG")[codes++ % 3]
}

class FakeHasher : SecretHasher {
    override suspend fun hash(secret: String): String = "h($secret)"

    override suspend fun csrfToken(sessionSecret: String): String = "csrf($sessionSecret)"

    override suspend fun pkceChallenge(verifier: String): String = "s256($verifier)"
}

class ScriptedRateLimiter(var allow: Boolean = true) : RateLimiter {
    val keys = mutableListOf<String>()

    override suspend fun tryAcquire(key: String, limit: Int, window: Duration): Boolean {
        keys += key
        return allow
    }
}

class RecordingAuditLog : AuditLog {
    val entries = mutableListOf<AuditEntry>()

    override suspend fun record(entry: AuditEntry): AuditEvent {
        entries += entry
        return AuditEvent(Uuid.random(), entries.size.toLong(), entry, Instant.parse("2026-10-30T10:00:00Z"), null, "hash", "sig", "kid")
    }
}

class InMemoryUsersRepository : UsersRepository {
    val users = mutableMapOf<Uuid, User>()
    val identities = mutableMapOf<Pair<String, String>, Uuid>()
    val emails = mutableMapOf<Uuid, MutableMap<String, Boolean>>()

    /** Duplicate failures to throw from the next creations (a race lost to another sign-in). */
    var duplicatesToThrow = 0
    var onDuplicate: () -> Unit = {}

    override suspend fun get(id: Uuid): User? = users[id]

    override suspend fun findByIdentity(issuer: String, subject: String): User? = identities[issuer to subject]?.let(users::get)

    override suspend fun findByVerifiedEmail(email: String): User? =
        emails.entries.firstOrNull { it.value[email] == true }?.key?.let(users::get)

    override suspend fun loginTaken(login: String): Boolean = users.values.any { it.login == login }

    override suspend fun create(user: User, identity: VerifiedIdentity, email: UserEmail?, at: Instant) {
        if (duplicatesToThrow > 0) {
            duplicatesToThrow--
            onDuplicate()
            throw DuplicateIdentityException()
        }
        users[user.id] = user
        link(user.id, identity, email, at)
    }

    override suspend fun link(userId: Uuid, identity: VerifiedIdentity, email: UserEmail?, at: Instant) {
        if (identities.getOrPut(identity.issuer to identity.subject) { userId } != userId) throw DuplicateIdentityException()
        if (email != null) {
            val mine = emails.getOrPut(userId) { mutableMapOf() }
            mine[email.email] = (mine[email.email] ?: false) || email.verified
        }
    }

    override suspend fun listEmails(userId: Uuid): List<UserEmail> =
        emails[userId].orEmpty().map { UserEmail(it.key, it.value) }.sortedWith(compareBy({ !it.verified }, { it.email }))

    override suspend fun listIssuers(userId: Uuid): List<String> =
        identities.filterValues { it == userId }.keys.map { it.first }.distinct().sorted()
}

class InMemoryWebSessionsRepository : WebSessionsRepository {
    val sessions = mutableMapOf<String, WebSession>()
    var touches = 0

    override suspend fun create(session: WebSession, secretHash: String) {
        sessions[secretHash] = session
    }

    override suspend fun findBySecretHash(secretHash: String): WebSession? = sessions[secretHash]

    override suspend fun touch(id: Uuid, at: Instant) {
        touches++
        update(id) { it.copy(lastSeenAt = at) }
    }

    override suspend fun revoke(id: Uuid, at: Instant) = update(id) { if (it.revokedAt == null) it.copy(revokedAt = at) else it }

    override suspend fun revokeAllOfUser(userId: Uuid, at: Instant): Long {
        val live = sessions.filterValues { it.userId == userId && it.revokedAt == null }
        live.forEach { (hash, session) -> sessions[hash] = session.copy(revokedAt = at) }
        return live.size.toLong()
    }

    private fun update(id: Uuid, change: (WebSession) -> WebSession) {
        sessions.entries.firstOrNull { it.value.id == id }?.let { it.setValue(change(it.value)) }
    }
}

class InMemoryApiTokensRepository : ApiTokensRepository {
    val tokens = mutableMapOf<String, ApiToken>()
    var touches = 0
    var failRotation = false

    override suspend fun create(token: ApiToken, secretHash: String) {
        tokens[secretHash] = token
    }

    override suspend fun findBySecretHash(secretHash: String): ApiToken? = tokens[secretHash]

    override suspend fun get(userId: Uuid, id: Uuid): ApiToken? = tokens.values.firstOrNull { it.id == id && it.userId == userId }

    override suspend fun listLive(userId: Uuid, kinds: Set<TokenKind>, now: Instant): List<ApiToken> =
        tokens.values.filter { it.userId == userId && it.kind in kinds && it.revokedAt == null && (it.expiresAt?.let { e -> e > now } ?: true) }
            .sortedByDescending { it.createdAt }

    override suspend fun countLive(userId: Uuid, kinds: Set<TokenKind>, now: Instant): Int = listLive(userId, kinds, now).size

    override suspend fun touch(id: Uuid, at: Instant) {
        touches++
        update(id) { it.copy(lastUsedAt = at) }
    }

    override suspend fun revoke(userId: Uuid, id: Uuid, at: Instant): Boolean {
        val token = get(userId, id)?.takeIf { it.revokedAt == null && it.kind != TokenKind.AGENT } ?: return false
        update(token.id) { it.copy(revokedAt = at) }
        return true
    }

    override suspend fun rotate(userId: Uuid, oldId: Uuid, replacement: ApiToken, secretHash: String, at: Instant): Boolean {
        if (failRotation) return false
        update(oldId) { it.copy(revokedAt = at) }
        tokens[secretHash] = replacement
        return true
    }

    override suspend fun revokeOfAgentSession(agentSessionId: String, at: Instant): Long {
        val live = tokens.values.filter { it.agentSessionId == agentSessionId && it.revokedAt == null }
        live.forEach { token -> update(token.id) { it.copy(revokedAt = at) } }
        return live.size.toLong()
    }

    private fun update(id: Uuid, change: (ApiToken) -> ApiToken) {
        tokens.entries.firstOrNull { it.value.id == id }?.let { it.setValue(change(it.value)) }
    }
}

class InMemoryDeviceAuthorizationsRepository(private val tokens: InMemoryApiTokensRepository) : DeviceAuthorizationsRepository {
    val authorizations = mutableMapOf<String, DeviceAuthorization>()
    var refuseAnswer = false
    var refuseConsume = false

    override suspend fun create(authorization: DeviceAuthorization, deviceCodeHash: String) {
        authorizations[deviceCodeHash] = authorization
    }

    override suspend fun findByDeviceCodeHash(deviceCodeHash: String): DeviceAuthorization? = authorizations[deviceCodeHash]

    override suspend fun findPendingByUserCode(userCode: String, now: Instant): DeviceAuthorization? =
        authorizations.values.firstOrNull { it.userCode == userCode && it.status == DeviceAuthorizationStatus.PENDING && it.expiresAt > now }

    override suspend fun answer(id: Uuid, userId: Uuid, approved: Boolean, now: Instant): Boolean {
        if (refuseAnswer) return false
        update(id) {
            it.copy(status = if (approved) DeviceAuthorizationStatus.APPROVED else DeviceAuthorizationStatus.DENIED, userId = userId)
        }
        return true
    }

    override suspend fun markPolled(id: Uuid, at: Instant) = update(id) { it.copy(lastPolledAt = at) }

    override suspend fun consume(id: Uuid, token: ApiToken, secretHash: String): Boolean {
        if (refuseConsume) return false
        update(id) { it.copy(status = DeviceAuthorizationStatus.CONSUMED) }
        tokens.create(token, secretHash)
        return true
    }

    fun update(id: Uuid, change: (DeviceAuthorization) -> DeviceAuthorization) {
        authorizations.entries.firstOrNull { it.value.id == id }?.let { it.setValue(change(it.value)) }
    }
}

class InMemoryAuditRepository : AuditRepository {
    val events = mutableListOf<AuditEvent>()

    override suspend fun append(orgId: Uuid?, build: suspend (previousHash: String?, sequence: Long) -> AuditEvent): AuditEvent {
        val chain = events.filter { it.entry.orgId == orgId }
        return build(chain.lastOrNull()?.hash, chain.size + 1L).also { events += it }
    }

    override suspend fun list(orgId: Uuid?, actorId: Uuid?, beforeSequence: Long?, limit: Int): List<AuditEvent> =
        events.filter { it.entry.orgId == orgId && it.sequence < (beforeSequence ?: Long.MAX_VALUE) }.reversed().take(limit)
}
