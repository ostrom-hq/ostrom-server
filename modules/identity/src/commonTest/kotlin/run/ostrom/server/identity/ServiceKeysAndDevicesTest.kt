// SPDX-License-Identifier: AGPL-3.0-only

package run.ostrom.server.identity

import run.ostrom.server.identity.domain.ApiToken
import run.ostrom.server.identity.domain.AuditEntry
import run.ostrom.server.identity.domain.CI_SERVICE_KEY_SCOPES
import run.ostrom.server.identity.domain.CreateServiceKeyUseCaseImpl
import run.ostrom.server.identity.domain.DeleteServiceKeyUseCaseImpl
import run.ostrom.server.identity.domain.DeviceNotFoundException
import run.ostrom.server.identity.domain.InvalidServiceKeyScopesException
import run.ostrom.server.identity.domain.InvalidTokenLifetimeException
import run.ostrom.server.identity.domain.InvalidTokenNameException
import run.ostrom.server.identity.domain.ListDevicesUseCaseImpl
import run.ostrom.server.identity.domain.ListServiceKeysUseCaseImpl
import run.ostrom.server.identity.domain.MAX_SERVICE_KEYS_PER_ORG
import run.ostrom.server.identity.domain.RESERVED_SERVICE_KEY_SCOPES
import run.ostrom.server.identity.domain.SERVICE_KEY_SCOPES
import run.ostrom.server.identity.domain.ServiceKeyAuditImpl
import run.ostrom.server.identity.domain.ServiceKeyNotFoundException
import run.ostrom.server.identity.domain.ServiceKeysAdminRequiredException
import run.ostrom.server.identity.domain.SignOutDeviceUseCaseImpl
import run.ostrom.server.identity.domain.TokenKind
import run.ostrom.server.identity.domain.TooManyServiceKeysException
import run.ostrom.server.identity.fakes.CountingSecretGenerator
import run.ostrom.server.identity.fakes.FakeHasher
import run.ostrom.server.identity.fakes.InMemoryApiTokensRepository
import run.ostrom.server.identity.fakes.InMemoryElevatedTokensRepository
import run.ostrom.server.identity.fakes.InMemoryServiceKeysRepository
import run.ostrom.server.identity.fakes.RecordingAuditLog
import run.ostrom.server.identity.fakes.SettableClock
import run.ostrom.server.identity.presentation.toCreatedServiceKeyResponse
import run.ostrom.server.identity.presentation.toDeviceResponse
import run.ostrom.server.identity.presentation.toServiceKeyResponse
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.ServiceKeyCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class ServiceKeysAndDevicesTest {
    private val tokens = InMemoryApiTokensRepository()
    private val audit = RecordingAuditLog()
    private val clock = SettableClock()
    private val keys = InMemoryServiceKeysRepository(tokens)
    private val secrets = CountingSecretGenerator()
    private val create = CreateServiceKeyUseCaseImpl(tokens, keys, secrets, FakeHasher(), audit, clock)
    private val list = ListServiceKeysUseCaseImpl(keys, clock)
    private val delete = DeleteServiceKeyUseCaseImpl(keys, audit, clock)
    private val elevated = InMemoryElevatedTokensRepository(tokens)
    private val devices = ListDevicesUseCaseImpl(tokens, elevated, clock)
    private val signOut = SignOutDeviceUseCaseImpl(tokens, elevated, audit, clock)
    private val orgA = Uuid.random()
    private val orgB = Uuid.random()
    private val ada = Uuid.random()
    private val owner = OrgPrincipal(orgA, Uuid.random(), OrgRole.OWNER)
    private val admin = OrgPrincipal(orgA, Uuid.random(), OrgRole.ADMIN)
    private val adminOfB = OrgPrincipal(orgB, Uuid.random(), OrgRole.ADMIN)

    @Test
    fun anOwnerCreatesAKeyOfTheOrganizationShownOnceAndAudited() = runTest {
        val issued = create(owner, ada, "  CI deploy ", listOf("sessions:write", "rules:read"), 30.days)
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
        assertEquals("rules:read sessions:write", entry.details["scopes"])

        val answer = issued.toCreatedServiceKeyResponse()
        assertEquals("oss_secret1", answer.secret)
        assertEquals(listOf("rules:read", "sessions:write"), answer.scopes)
        assertNull(key.toServiceKeyResponse().secret, "Listed without its secret")
        assertNull(key.toServiceKeyResponse().lastUsedAt)
        assertEquals(key.expiresAt.toString(), answer.expiresAt)
    }

    @Test
    fun aKeyIsListedWithItsLastUseAndWithoutAnExpiryItDoesNotHave() = runTest {
        val key = create(owner, ada, "runner", listOf("sessions:write"), null).token.copy(lastUsedAt = clock.now)
        val answer = key.toServiceKeyResponse()
        assertNull(answer.expiresAt)
        assertEquals(clock.now.toString(), answer.lastUsedAt)
        val laptop = token(ada, TokenKind.CLI, "laptop").copy(lastUsedAt = clock.now)
        assertEquals(clock.now.toString(), laptop.toDeviceResponse().lastSeenAt)
    }

    @Test
    fun anAdminListsAndDeletesOnlyItsOrganizationsLiveKeys() = runTest {
        val forever = create(admin, ada, "runner", listOf("sessions:write"), null).token
        assertNull(forever.expiresAt)
        val short = create(admin, ada, "short", listOf("sessions:write"), 1.days).token
        val other = create(adminOfB, ada, "b", listOf("sessions:write"), null).token
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
            assertFailsWith<ServiceKeysAdminRequiredException> { create(member, ada, "k", listOf("rules:read"), null) }
            assertFailsWith<ServiceKeysAdminRequiredException> { list(member) }
            assertFailsWith<ServiceKeysAdminRequiredException> { delete(member, ada, Uuid.random()) }
        }
        assertTrue(audit.entries.isEmpty())
    }

    @Test
    fun aKeyTakesTheNameAndLifetimeOfAPersonalTokenAndOnlyTheScopesACiCalls() = runTest {
        assertFailsWith<InvalidTokenNameException> { create(owner, ada, "  ", listOf("rules:read"), null) }
        assertFailsWith<InvalidTokenNameException> { create(owner, ada, "x".repeat(101), listOf("rules:read"), null) }
        assertFailsWith<InvalidTokenNameException> { create(owner, ada, "ci\nOwner", listOf("rules:read"), null) }
        assertFailsWith<InvalidServiceKeyScopesException> { create(owner, ada, "k", emptyList(), null) }
        // The catalogue, nothing else: never a governance write, a token, a member or billing, nor a TeamCode scope
        // for a feature Ostrom does not serve yet.
        val outside = listOf("gates:answer", "orgs:write", "billing:read", "a:b", "skills:publish", "admin:cloud")
        for (scope in outside) {
            assertFailsWith<InvalidServiceKeyScopesException>(scope) {
                create(owner, ada, "k", listOf("rules:read", scope), null)
            }
        }
        assertFailsWith<InvalidTokenLifetimeException> { create(owner, ada, "k", listOf("rules:read"), 23.hours) }
        assertFailsWith<InvalidTokenLifetimeException> { create(owner, ada, "k", listOf("rules:read"), 367.days) }
        assertTrue(tokens.tokens.isEmpty())
    }

    @Test
    fun aKeyHoldsTheCatalogueOfTheCiOrOfAMemberButNeverBoth() = runTest {
        for (scope in SERVICE_KEY_SCOPES) {
            assertEquals(setOf(scope), create(owner, ada, "k", listOf(scope), 30.days).token.scopes)
        }
        val member = listOf("inference:use", "mcp:use", "usage:read", "cloud:write", "artifacts:read", "team:read")
        assertEquals(member.toSet(), create(admin, ada, "wide", member, 30.days).token.scopes)
        for (mixed in listOf(listOf("sessions:write", "team:read"), listOf("rules:read", "inference:use"))) {
            assertFailsWith<InvalidServiceKeyScopesException> { create(owner, ada, "mixed", mixed, null) }
        }
        assertEquals(setOf("sessions:write", "rules:read"), CI_SERVICE_KEY_SCOPES)
        assertEquals(setOf("team:read", "cloud:admin"), RESERVED_SERVICE_KEY_SCOPES)
    }

    @Test
    fun aKeyThatNeverExpiresIsItsOwnAuditEvent() = runTest {
        create(owner, ada, "forever", listOf("rules:read"), null)
        create(owner, ada, "month", listOf("rules:read"), 30.days)
        assertEquals(listOf("service_key.created_no_expiry", "service_key.created"), audit.entries.map { it.action })
    }

    @Test
    fun eachCallOfAKeyIsAnEventOfItsOrganizationInTheKeysName() = runTest {
        ServiceKeyAuditImpl(audit).record(ServiceKeyCall("key-1", orgA, "reportCheck", "/api/v1/checks"))
        val entry = audit.entries.single()
        val expected = AuditEntry(orgA, null, "service_key.used", "service_key", "key-1")
        assertEquals(expected, entry.copy(details = emptyMap()))
        assertEquals(mapOf("operation" to "reportCheck", "path" to "/api/v1/checks"), entry.details)
    }

    @Test
    fun anOrganizationHoldsAtMostAHundredLiveKeys() = runTest {
        repeat(MAX_SERVICE_KEYS_PER_ORG) { create(owner, ada, "k$it", listOf("rules:read"), null) }
        assertFailsWith<TooManyServiceKeysException> { create(owner, ada, "one more", listOf("rules:read"), null) }
        create(adminOfB, ada, "elsewhere", listOf("rules:read"), null)
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

    @Test
    fun theTokensOfAnAdminLoginAreListedInTheOrganizationTheRequestNames() = runTest {
        val bea = Uuid.random()
        val laptop = token(ada, TokenKind.CLI, "laptop")
        val adas = token(ada, TokenKind.ADMIN, "ada --admin", orgA)
        val beas = token(bea, TokenKind.ADMIN, "bea --admin", orgA)
        val inB = token(ada, TokenKind.ADMIN, "ada in B", orgB)
        val adaMember = OrgPrincipal(orgA, Uuid.random(), OrgRole.MEMBER)

        assertEquals(listOf(laptop.id), devices(ada).map { it.id }, "No organization named: no elevated token")
        assertEquals(setOf(laptop.id, adas.id, beas.id), devices(ada, owner).map { it.id }.toSet(), "An owner")
        assertEquals(setOf(adas.id, beas.id), devices(bea, admin).map { it.id }.toSet(), "An admin")
        assertEquals(listOf(laptop.id, adas.id), devices(ada, adaMember).map { it.id }, "A member: their own")
        assertEquals(listOf(laptop.id, inB.id), devices(ada, adminOfB).map { it.id }, "Never another organization's")
    }

    @Test
    fun aMemberSignsOutTheirOwnElevatedTokenAudited() = runTest {
        val adas = token(ada, TokenKind.ADMIN, "ada --admin", orgA)
        val adaMember = OrgPrincipal(orgA, Uuid.random(), OrgRole.MEMBER)
        assertFailsWith<DeviceNotFoundException> { signOut(ada, adas.id) }
        signOut(ada, adas.id, adaMember)
        val revoked = audit.entries.single()
        assertEquals(listOf(orgA, ada), listOf(revoked.orgId, revoked.actorId))
        assertEquals("token.revoked" to "admin", revoked.action to revoked.details["kind"])
        assertEquals(ada.toString(), revoked.details["owner"])
        assertFailsWith<DeviceNotFoundException> { signOut(ada, adas.id, adaMember) }
    }

    @Test
    fun aMemberNeverSignsOutAnotherMembersElevatedToken() = runTest {
        val beas = token(Uuid.random(), TokenKind.ADMIN, "bea --admin", orgA)
        val adaMember = OrgPrincipal(orgA, Uuid.random(), OrgRole.MEMBER)
        assertFailsWith<DeviceNotFoundException> { signOut(ada, beas.id, adaMember) }
        assertTrue(audit.entries.isEmpty())
        assertEquals(listOf(beas.id), devices(ada, owner).map { it.id })
    }

    @Test
    fun anOwnerOrAnAdminSignsOutAnyMembersElevatedTokenAuditedWithItsOwner() = runTest {
        val bea = Uuid.random()
        val first = token(bea, TokenKind.ADMIN, "bea --admin", orgA)
        val second = token(bea, TokenKind.ADMIN, "bea's other --admin", orgA)
        signOut(ada, first.id, owner)
        signOut(ada, second.id, admin)
        assertEquals(listOf(ada, ada), audit.entries.map { it.actorId }, "The actor")
        assertEquals(listOf(bea.toString(), bea.toString()), audit.entries.map { it.details["owner"] }, "The owner")
        assertEquals(listOf(orgA, orgA), audit.entries.map { it.orgId })
        assertTrue(devices(ada, owner).isEmpty())
    }

    @Test
    fun anotherOrganizationsOrAnotherKindOfTokenIsUnknown() = runTest {
        val inB = token(Uuid.random(), TokenKind.ADMIN, "in B", orgB)
        val key = token(ada, TokenKind.SERVICE, "ci", orgA)
        val personal = token(ada, TokenKind.PERSONAL, "script")
        assertFailsWith<DeviceNotFoundException> { signOut(ada, inB.id, owner) }
        assertFailsWith<DeviceNotFoundException> { signOut(ada, key.id, owner) }
        assertFailsWith<DeviceNotFoundException> { signOut(ada, personal.id, owner) }
        assertFailsWith<DeviceNotFoundException> { signOut(ada, Uuid.random(), owner) }
        assertTrue(audit.entries.isEmpty())
        val laptop = token(ada, TokenKind.CLI, "laptop")
        signOut(ada, laptop.id, owner)
        assertEquals(null to "cli", audit.entries.single().orgId to audit.entries.single().details["kind"])
    }

    @Test
    fun anAdminLoginsTokenShowsItsOrganizationScopesAndExpiry() = runTest {
        val elevated = token(ada, TokenKind.ADMIN, "ada --admin", orgA)
            .copy(scopes = setOf("policy:write", "gates:policy"), expiresAt = clock.now + 8.hours)
        val shown = elevated.toDeviceResponse()
        assertEquals("admin" to orgA.toString(), shown.kind to shown.orgId)
        assertEquals(listOf("gates:policy", "policy:write"), shown.scopes)
        assertEquals((clock.now + 8.hours).toString(), shown.expiresAt)
        val unbound = elevated.copy(orgId = null, expiresAt = null).toDeviceResponse()
        assertEquals(null to null, unbound.orgId to unbound.expiresAt)

        val cli = token(ada, TokenKind.CLI, "laptop").toDeviceResponse()
        assertEquals("cli", cli.kind)
        assertEquals(listOf(null, null, null), listOf(cli.orgId, cli.scopes, cli.expiresAt))
    }

    private suspend fun token(userId: Uuid, kind: TokenKind, name: String, orgId: Uuid? = null): ApiToken {
        val token = ApiToken(
            Uuid.random(), userId, orgId, name, kind, setOf("profile:read"), "osc_hint", null, clock.now,
            null, null, null,
        )
        tokens.create(token, "h(${token.id})")
        return token
    }
}
