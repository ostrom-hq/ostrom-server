package dev.ostrom.server.secrets

import dev.ostrom.server.secrets.domain.AdminRequiredException
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.CreateSecretPayload
import dev.ostrom.server.secrets.domain.CreateSecretUseCaseImpl
import dev.ostrom.server.secrets.domain.DeleteSecretUseCaseImpl
import dev.ostrom.server.secrets.domain.FindSecretUseCaseImpl
import dev.ostrom.server.secrets.domain.InvalidSecretException
import dev.ostrom.server.secrets.domain.ListSecretsUseCaseImpl
import dev.ostrom.server.secrets.domain.MemberRole
import dev.ostrom.server.secrets.domain.RevealSecretsUseCaseImpl
import dev.ostrom.server.secrets.domain.RotateSecretUseCaseImpl
import dev.ostrom.server.secrets.domain.Secret
import dev.ostrom.server.secrets.domain.SecretCipher
import dev.ostrom.server.secrets.domain.SecretNameTakenException
import dev.ostrom.server.secrets.domain.SecretNotFoundException
import dev.ostrom.server.secrets.domain.SecretUnreadableException
import dev.ostrom.server.secrets.domain.SecretValue
import dev.ostrom.server.secrets.domain.SecretsRepository
import dev.ostrom.server.secrets.domain.StoredSecret
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SecretUseCasesTest {
    private val org = Uuid.parse("00000000-0000-0000-0000-00000000000a")
    private val admin = Caller(org, Uuid.parse("00000000-0000-0000-0000-0000000000a1"), MemberRole.ADMIN)
    private val member = Caller(org, Uuid.parse("00000000-0000-0000-0000-0000000000b1"), MemberRole.MEMBER)
    private val otherMember = Caller(org, Uuid.parse("00000000-0000-0000-0000-0000000000b2"), MemberRole.MEMBER)
    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-10-30T02:00:00Z")
    }
    private val repository = InMemorySecretsRepository()
    private val cipher = ReversingCipher()
    private val create = CreateSecretUseCaseImpl(repository, cipher, clock)
    private val reveal = RevealSecretsUseCaseImpl(repository, cipher)

    @Test
    fun anAdminCreatesAnOrgSecretAndTheServerRevealsIt() = runTest {
        val secret = create(admin, CreateSecretPayload("GITHUB_TOKEN", SecretValue("ghp_value_123"), personal = false))

        assertEquals(null, secret.ownerMemberId, "An org secret")
        assertEquals(1, secret.version)
        assertEquals(Instant.parse("2026-10-30T02:00:00Z"), secret.createdAt)
        assertEquals("ghp_value_123", reveal(org, listOf(secret.id))[secret.id]?.reveal())
        assertEquals(emptyMap(), reveal(org, emptyList()))
    }

    @Test
    fun aMemberCreatesOnlyPersonalSecrets() = runTest {
        assertFailsWith<AdminRequiredException> {
            create(member, CreateSecretPayload("SHARED", SecretValue("value-12345"), personal = false))
        }
        val personal = create(member, CreateSecretPayload("MINE", SecretValue("value-12345"), personal = true))

        assertEquals(member.memberId, personal.ownerMemberId)
    }

    @Test
    fun namesAndValuesAreValidated() = runTest {
        assertFailsWith<InvalidSecretException> { create(admin, CreateSecretPayload("bad name", SecretValue("v"), false)) }
        assertFailsWith<InvalidSecretException> { create(admin, CreateSecretPayload("OK", SecretValue(""), false)) }
        assertFailsWith<InvalidSecretException> { create(admin, CreateSecretPayload("OK", SecretValue("a\u0000b"), false)) }
        assertFailsWith<InvalidSecretException> {
            create(admin, CreateSecretPayload("OK", SecretValue("x".repeat(16 * 1024 + 1)), false))
        }
        assertFailsWith<SecretNameTakenException> {
            create(admin, CreateSecretPayload("DUP", SecretValue("one-value"), false))
            create(admin, CreateSecretPayload("DUP", SecretValue("two-value"), false))
        }
    }

    @Test
    fun membersListOrgSecretsAndTheirOwnAdminsListEverything() = runTest {
        create(admin, CreateSecretPayload("ORG", SecretValue("org-value-1"), false))
        create(member, CreateSecretPayload("MINE", SecretValue("my-value-1"), true))
        create(otherMember, CreateSecretPayload("THEIRS", SecretValue("their-value"), true))
        val list = ListSecretsUseCaseImpl(repository)

        assertEquals(listOf("MINE", "ORG"), list(member, null, 50).map { it.name })
        assertEquals(listOf("MINE", "ORG", "THEIRS"), list(admin, null, 500).map { it.name })
        assertEquals(listOf("ORG"), list(admin, "MINE", 1).map { it.name })
    }

    @Test
    fun rotationBumpsTheVersionAndChangesTheValue() = runTest {
        val secret = create(admin, CreateSecretPayload("KEY", SecretValue("first-value"), false))

        val rotated = RotateSecretUseCaseImpl(repository, cipher, clock)(admin, secret.id, SecretValue("second-value"))

        assertEquals(2, rotated.version)
        assertEquals("second-value", reveal(org, listOf(secret.id))[secret.id]?.reveal())
        assertEquals(admin.memberId, rotated.updatedBy)
    }

    @Test
    fun secretsAreFoundAndRevealedByName() = runTest {
        val orgSecret = create(admin, CreateSecretPayload("TOKEN", SecretValue("org-value-1"), false))
        val mine = create(member, CreateSecretPayload("TOKEN", SecretValue("my-value-12"), true))
        val find = FindSecretUseCaseImpl(repository)

        assertEquals(orgSecret.id, find(member, "TOKEN", personal = false).id)
        assertEquals(mine.id, find(member, "TOKEN", personal = true).id)
        assertEquals(member.memberId, mine.updatedBy)
        assertFailsWith<SecretNotFoundException> { find(otherMember, "TOKEN", personal = true) }
        assertEquals(mapOf("TOKEN" to "org-value-1"), reveal.byName(org, listOf("TOKEN", "TOKEN", "MISSING")).mapValues { it.value.reveal() })
        assertEquals(emptyMap(), reveal.byName(org, emptyList()))
        cipher.broken = true
        assertFailsWith<SecretUnreadableException> { reveal.byName(org, listOf("TOKEN")) }
    }

    @Test
    fun onlyAdminsAndOwnersChangeASecret() = runTest {
        val orgSecret = create(admin, CreateSecretPayload("ORG", SecretValue("org-value-1"), false))
        val mine = create(member, CreateSecretPayload("MINE", SecretValue("my-value-12"), true))
        val rotate = RotateSecretUseCaseImpl(repository, cipher, clock)
        val delete = DeleteSecretUseCaseImpl(repository)

        assertFailsWith<AdminRequiredException> { rotate(member, orgSecret.id, SecretValue("new-value-1")) }
        assertFailsWith<SecretNotFoundException> { rotate(otherMember, mine.id, SecretValue("new-value-1")) }
        assertFailsWith<SecretNotFoundException> { delete(otherMember, mine.id) }
        assertFailsWith<SecretNotFoundException> { delete(admin, Uuid.random()) }
        assertEquals(2, rotate(member, mine.id, SecretValue("new-value-1")).version, "The owner rotates")
        delete(admin, mine.id)
        assertNull(repository.get(org, mine.id), "An admin removes a member's secret")
    }

    @Test
    fun aRowThatVanishesDuringAChangeIsNotFound() = runTest {
        val secret = create(admin, CreateSecretPayload("GONE", SecretValue("gone-value"), false))
        repository.vanishOnWrite = true

        assertFailsWith<SecretNotFoundException> { RotateSecretUseCaseImpl(repository, cipher, clock)(admin, secret.id, SecretValue("next-value")) }
        assertFailsWith<SecretNotFoundException> { DeleteSecretUseCaseImpl(repository)(admin, secret.id) }
    }

    @Test
    fun anEnvelopeThatDoesNotOpenIsReported() = runTest {
        val secret = create(admin, CreateSecretPayload("BROKEN", SecretValue("broken-val"), false))
        cipher.broken = true

        assertFailsWith<SecretUnreadableException> { reveal(org, listOf(secret.id)) }
    }

    @Test
    fun adminsAreOwnersAndAdmins() {
        assertTrue(MemberRole.OWNER.isAdmin)
        assertTrue(MemberRole.ADMIN.isAdmin)
        assertEquals(false, MemberRole.MEMBER.isAdmin)
    }
}

/** Seals by reversing the text and appending the context: enough to check the use cases pass the right context. */
class ReversingCipher : SecretCipher {
    var broken = false

    override suspend fun seal(plaintext: ByteArray, context: String): String = plaintext.decodeToString().reversed() + "|" + context

    override suspend fun open(envelope: String, context: String): ByteArray? {
        if (broken || !envelope.endsWith("|$context")) return null
        return envelope.removeSuffix("|$context").reversed().encodeToByteArray()
    }
}

/** [SecretsRepository] in memory, with the same uniqueness and visibility rules as the SQL one. */
class InMemorySecretsRepository : SecretsRepository {
    private val rows = mutableMapOf<Uuid, StoredSecret>()
    var vanishOnWrite = false

    override suspend fun create(stored: StoredSecret): Boolean {
        val taken = rows.values.any {
            it.secret.orgId == stored.secret.orgId && it.secret.name == stored.secret.name &&
                it.secret.ownerMemberId == stored.secret.ownerMemberId
        }
        if (taken) return false
        rows[stored.secret.id] = stored
        return true
    }

    override suspend fun get(orgId: Uuid, id: Uuid): StoredSecret? = rows[id]?.takeIf { it.secret.orgId == orgId }

    override suspend fun getMany(orgId: Uuid, ids: Collection<Uuid>): List<StoredSecret> = ids.mapNotNull { get(orgId, it) }

    override suspend fun list(orgId: Uuid, memberId: Uuid?, afterName: String?, limit: Int): List<Secret> =
        rows.values.map { it.secret }
            .filter { it.orgId == orgId && (memberId == null || it.ownerMemberId == null || it.ownerMemberId == memberId) }
            .filter { afterName == null || it.name > afterName }
            .sortedBy { it.name }
            .take(limit)

    override suspend fun rotate(orgId: Uuid, id: Uuid, envelope: String, updatedAt: Instant, updatedBy: Uuid): Secret? {
        if (vanishOnWrite) return null
        val current = get(orgId, id) ?: return null
        val next = current.secret.copy(version = current.secret.version + 1, updatedAt = updatedAt, updatedBy = updatedBy)
        rows[id] = StoredSecret(next, envelope)
        return next
    }

    override suspend fun findByName(orgId: Uuid, ownerMemberId: Uuid?, name: String): Secret? =
        rows.values.map { it.secret }.firstOrNull { it.orgId == orgId && it.ownerMemberId == ownerMemberId && it.name == name }

    override suspend fun getOrgSecretsByName(orgId: Uuid, names: Collection<String>): List<StoredSecret> =
        rows.values.filter { it.secret.orgId == orgId && it.secret.ownerMemberId == null && it.secret.name in names }

    override suspend fun delete(orgId: Uuid, id: Uuid): Boolean {
        if (vanishOnWrite) return false
        return get(orgId, id)?.let { rows.remove(id) } != null
    }
}
