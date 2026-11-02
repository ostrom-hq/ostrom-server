// SPDX-License-Identifier: AGPL-3.0-only

package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.ApiToken
import dev.ostrom.server.identity.domain.CreateServiceKeyUseCaseImpl
import dev.ostrom.server.identity.domain.DeleteServiceKeyUseCaseImpl
import dev.ostrom.server.identity.domain.DeviceNotFoundException
import dev.ostrom.server.identity.domain.InvalidScopesException
import dev.ostrom.server.identity.domain.InvalidTokenLifetimeException
import dev.ostrom.server.identity.domain.InvalidTokenNameException
import dev.ostrom.server.identity.domain.ListDevicesUseCaseImpl
import dev.ostrom.server.identity.domain.ListServiceKeysUseCaseImpl
import dev.ostrom.server.identity.domain.MAX_SERVICE_KEYS_PER_ORG
import dev.ostrom.server.identity.domain.ServiceKeyNotFoundException
import dev.ostrom.server.identity.domain.ServiceKeysAdminRequiredException
import dev.ostrom.server.identity.domain.SignOutDeviceUseCaseImpl
import dev.ostrom.server.identity.domain.TokenKind
import dev.ostrom.server.identity.domain.TooManyServiceKeysException
import dev.ostrom.server.identity.fakes.CountingSecretGenerator
import dev.ostrom.server.identity.fakes.FakeHasher
import dev.ostrom.server.identity.fakes.InMemoryApiTokensRepository
import dev.ostrom.server.identity.fakes.InMemoryServiceKeysRepository
import dev.ostrom.server.identity.fakes.RecordingAuditLog
import dev.ostrom.server.identity.fakes.SettableClock
import dev.ostrom.server.identity.presentation.toCreatedServiceKeyResponse
import dev.ostrom.server.identity.presentation.toDeviceResponse
import dev.ostrom.server.identity.presentation.toServiceKeyResponse
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

class ServiceKeysAndDevicesTest {
    private val tokens = InMemoryApiTokensRepository()
    private val audit = RecordingAuditLog()
    private val clock = SettableClock()
    private val keys = InMemoryServiceKeysRepository(tokens)
    private val secrets = CountingSecretGenerator()
    private val create = CreateServiceKeyUseCaseImpl(tokens, keys, secrets, FakeHasher(), audit, clock)
    private val list = ListServiceKeysUseCaseImpl(keys, clock)
    private val delete = DeleteServiceKeyUseCaseImpl(keys, audit, clock)
    private val devices = ListDevicesUseCaseImpl(tokens, clock)
    private val signOut = SignOutDeviceUseCaseImpl(tokens, audit, clock)
    private val orgA = Uuid.random()
    private val orgB = Uuid.random()
    private val ada = Uuid.random()
    private val owner = OrgPrincipal(orgA, Uuid.random(), OrgRole.OWNER)
    private val admin = OrgPrincipal(orgA, Uuid.random(), OrgRole.ADMIN)
    private val adminOfB = OrgPrincipal(orgB, Uuid.random(), OrgRole.ADMIN)

    @Test
    fun anOwnerCreatesAKeyOfTheOrganizationShownOnceAndAudited() = runTest {
        val issued = create(owner, ada, "  CI deploy ", listOf("sessions:read", "receipts:read"), 30.days)
        val key = issued.token
        assertEquals("oss_secret1", issued.secret)
        assertEquals(TokenKind.SERVICE, key.kind)
        assertEquals(orgA, key.orgId, "The key is the organization's")
        assertEquals(ada, key.userId, "Its creator, recorded")
        assertEquals("CI deploy", key.name)
        assertEquals(clock.now + 30.days, key.expiresAt)
        assertEquals(key, tokens.findBySecretHash("h(oss_secret1)"), "Only the hash is the key to it")
        val entry = audit.entries.single()
        assertEquals(listOf(orgA, ada), listOf(entry.orgId, entry.actorId))
        assertEquals("service_key.created", entry.action)
        assertEquals("receipts:read sessions:read", entry.details["scopes"])

        val answer = issued.toCreatedServiceKeyResponse()
        assertEquals("oss_secret1", answer.secret)
        assertEquals(listOf("receipts:read", "sessions:read"), answer.scopes)
        assertNull(key.toServiceKeyResponse().secret, "Listed without its secret")
        assertNull(key.toServiceKeyResponse().lastUsedAt)
        assertEquals(key.expiresAt.toString(), answer.expiresAt)
    }

    @Test
    fun aKeyIsListedWithItsLastUseAndWithoutAnExpiryItDoesNotHave() = runTest {
        val key = create(owner, ada, "runner", listOf("cloud:write"), null).token.copy(lastUsedAt = clock.now)
        val answer = key.toServiceKeyResponse()
        assertNull(answer.expiresAt)
        assertEquals(clock.now.toString(), answer.lastUsedAt)
        val laptop = token(ada, TokenKind.CLI, "laptop").copy(lastUsedAt = clock.now)
        assertEquals(clock.now.toString(), laptop.toDeviceResponse().lastSeenAt)
    }

    @Test
    fun anAdminListsAndDeletesOnlyItsOrganizationsLiveKeys() = runTest {
        val forever = create(admin, ada, "runner", listOf("cloud:write"), null).token
        assertNull(forever.expiresAt)
        val short = create(admin, ada, "short", listOf("cloud:write"), 1.days).token
        val other = create(adminOfB, ada, "b", listOf("cloud:write"), null).token
        assertEquals(setOf(forever.id, short.id), list(admin).map { it.id }.toSet())
        assertEquals(listOf(other.id), list(adminOfB).map { it.id })

        clock.now += 2.days
        assertEquals(listOf(forever.id), list(owner).map { it.id }, "An expired key is not listed")
        assertFailsWith<ServiceKeyNotFoundException> { delete(admin, ada, short.id) }
        assertFailsWith<ServiceKeyNotFoundException> { delete(admin, ada, other.id) }
        delete(admin, ada, forever.id)
        assertEquals("service_key.deleted", audit.entries.last().action)
        assertTrue(list(admin).isEmpty())
        assertFailsWith<ServiceKeyNotFoundException> { delete(admin, ada, forever.id) }
    }

    @Test
    fun onlyAnOwnerOrAnAdminManagesKeys() = runTest {
        for (role in listOf(OrgRole.MEMBER, OrgRole.APPROVER, OrgRole.BILLING)) {
            val member = OrgPrincipal(orgA, Uuid.random(), role)
            assertFailsWith<ServiceKeysAdminRequiredException> { create(member, ada, "k", listOf("cloud:read"), null) }
            assertFailsWith<ServiceKeysAdminRequiredException> { list(member) }
            assertFailsWith<ServiceKeysAdminRequiredException> { delete(member, ada, Uuid.random()) }
        }
        assertTrue(audit.entries.isEmpty())
    }

    @Test
    fun aKeyTakesTheNameScopesAndLifetimeOfAPersonalToken() = runTest {
        assertFailsWith<InvalidTokenNameException> { create(owner, ada, "  ", listOf("cloud:read"), null) }
        assertFailsWith<InvalidTokenNameException> { create(owner, ada, "x".repeat(101), listOf("cloud:read"), null) }
        assertFailsWith<InvalidScopesException> { create(owner, ada, "k", emptyList(), null) }
        assertFailsWith<InvalidScopesException> { create(owner, ada, "k", listOf("gates:answer"), null) }
        assertFailsWith<InvalidTokenLifetimeException> { create(owner, ada, "k", listOf("cloud:read"), 23.hours) }
        assertFailsWith<InvalidTokenLifetimeException> { create(owner, ada, "k", listOf("cloud:read"), 367.days) }
        assertTrue(tokens.tokens.isEmpty())
    }

    @Test
    fun anOrganizationHoldsAtMostAHundredLiveKeys() = runTest {
        repeat(MAX_SERVICE_KEYS_PER_ORG) { create(owner, ada, "k$it", listOf("cloud:read"), null) }
        assertFailsWith<TooManyServiceKeysException> { create(owner, ada, "one more", listOf("cloud:read"), null) }
        create(adminOfB, ada, "elsewhere", listOf("cloud:read"), null)
    }

    @Test
    fun aPersonSeesAndSignsOutTheirOwnWorkstationsOnly() = runTest {
        val laptop = token(ada, TokenKind.CLI, "laptop")
        val personal = token(ada, TokenKind.PERSONAL, "script")
        val bobs = token(Uuid.random(), TokenKind.CLI, "bob's")
        assertEquals(listOf(laptop.id), devices(ada).map { it.id }, "Only the CLI tokens are devices")
        assertEquals("laptop", laptop.toDeviceResponse().name)
        assertNull(laptop.toDeviceResponse().lastSeenAt)

        assertFailsWith<DeviceNotFoundException> { signOut(ada, bobs.id) }
        assertFailsWith<DeviceNotFoundException> { signOut(ada, personal.id) }
        assertFailsWith<DeviceNotFoundException> { signOut(ada, Uuid.random()) }
        signOut(ada, laptop.id)
        assertEquals(listOf("token.revoked"), audit.entries.map { it.action })
        assertTrue(devices(ada).isEmpty())
        assertFailsWith<DeviceNotFoundException> { signOut(ada, laptop.id) }
    }

    private suspend fun token(userId: Uuid, kind: TokenKind, name: String): ApiToken {
        val token = ApiToken(
            Uuid.random(), userId, null, name, kind, setOf("profile:read"), "osc_hint", null, clock.now,
            null, null, null,
        )
        tokens.create(token, "h(${token.id})")
        return token
    }
}
