// SPDX-License-Identifier: Apache-2.0

package run.ostrom.server.identity

import run.ostrom.server.identity.domain.ADMIN_SCOPES
import run.ostrom.server.identity.domain.ApiToken
import run.ostrom.server.identity.domain.CLI_SCOPES
import run.ostrom.server.identity.domain.DeviceLimitException
import run.ostrom.server.identity.domain.DeviceLoginIdentity
import run.ostrom.server.identity.domain.DeviceMemberNotFoundException
import run.ostrom.server.identity.domain.DeviceNotFoundException
import run.ostrom.server.identity.domain.DevicePollResult
import run.ostrom.server.identity.domain.DeviceSignInRequest
import run.ostrom.server.identity.domain.DevicesAdminRequiredException
import run.ostrom.server.identity.domain.InvalidDeviceNameException
import run.ostrom.server.identity.domain.InvalidInstallationException
import run.ostrom.server.identity.domain.InvalidOsVersionException
import run.ostrom.server.identity.domain.InvalidPlatformException
import run.ostrom.server.identity.domain.ListDevicesUseCaseImpl
import run.ostrom.server.identity.domain.ListMemberDevicesUseCaseImpl
import run.ostrom.server.identity.domain.MAX_REGISTERED_DEVICES
import run.ostrom.server.identity.domain.NoMemberDirectory
import run.ostrom.server.identity.domain.PollDeviceAuthorizationUseCaseImpl
import run.ostrom.server.identity.domain.RenameDeviceUseCaseImpl
import run.ostrom.server.identity.domain.AnswerDeviceAuthorizationUseCaseImpl
import run.ostrom.server.identity.domain.SignOutDeviceUseCaseImpl
import run.ostrom.server.identity.domain.StartDeviceAuthorizationUseCaseImpl
import run.ostrom.server.identity.domain.TokenKind
import run.ostrom.server.identity.domain.validatedInstallation
import run.ostrom.server.identity.fakes.CountingSecretGenerator
import run.ostrom.server.identity.fakes.FakeHasher
import run.ostrom.server.identity.fakes.FakeMemberDirectory
import run.ostrom.server.identity.fakes.InMemoryApiTokensRepository
import run.ostrom.server.identity.fakes.InMemoryDeviceAuthorizationsRepository
import run.ostrom.server.identity.fakes.InMemoryElevatedTokensRepository
import run.ostrom.server.identity.fakes.RecordingAuditLog
import run.ostrom.server.identity.fakes.ScriptedRateLimiter
import run.ostrom.server.identity.fakes.SettableClock
import run.ostrom.server.identity.presentation.toDeviceResponse
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** `devices.v1`: one device per installation of the CLI (TeamCode's devices), from the login to its removal. */
class DeviceIdentityTest {
    private val tokens = InMemoryApiTokensRepository()
    private val authorizations = InMemoryDeviceAuthorizationsRepository(tokens)
    private val devices = authorizations.devices
    private val secrets = CountingSecretGenerator()
    private val hasher = FakeHasher()
    private val limiter = ScriptedRateLimiter()
    private val audit = RecordingAuditLog()
    private val clock = SettableClock()
    private val directory = FakeMemberDirectory()
    private val elevated = InMemoryElevatedTokensRepository(tokens)
    private val start = StartDeviceAuthorizationUseCaseImpl(authorizations, secrets, hasher, limiter, clock)
    private val answer = AnswerDeviceAuthorizationUseCaseImpl(authorizations, devices, limiter, audit, clock)
    private val poll = PollDeviceAuthorizationUseCaseImpl(authorizations, tokens, secrets, hasher, audit, clock)
    private val list = ListDevicesUseCaseImpl(tokens, elevated, devices, clock)
    private val listMember = ListMemberDevicesUseCaseImpl(directory, list)
    private val rename = RenameDeviceUseCaseImpl(devices, directory, list, audit)
    private val signOut = SignOutDeviceUseCaseImpl(tokens, elevated, devices, directory, audit, clock)
    private val ada = Uuid.random()
    private val bea = Uuid.random()
    private val orgA = Uuid.random()
    private val adaMemberId = Uuid.random()
    private val owner = OrgPrincipal(orgA, Uuid.random(), OrgRole.OWNER)
    private val admin = OrgPrincipal(orgA, Uuid.random(), OrgRole.ADMIN)
    private val member = OrgPrincipal(orgA, adaMemberId, OrgRole.MEMBER)
    private val installation = "6f1c0c3e-9a40-4a39-8e0e-2b1f3f8a1a11"
    private val other = "0b9d5f55-1b9c-4a6a-8c43-3c1c2b2f9e22"

    init {
        directory.members[orgA to adaMemberId.toString()] = ada
        directory.members[orgA to owner.memberId.toString()] = bea
        directory.members[orgA to admin.memberId.toString()] = Uuid.random()
    }

    /** Signs a person in from [id], as the CLI does: start, approve, poll. */
    private suspend fun login(
        who: Uuid,
        id: String? = installation,
        name: String? = "Work laptop",
        platform: String? = "macos",
        osVersion: String? = "15.1",
    ): ApiToken {
        val started = start("host", "10.0.0.1", null, DeviceLoginIdentity(id, name, platform, osVersion))
        answer(who, started.authorization.userCode, approve = true)
        clock.now += 6.seconds
        val approved = assertIs<DevicePollResult.Approved>(poll(started.deviceCode))
        // The issue answers the token as made; the device it belongs to is what is stored.
        return checkNotNull(tokens.get(who, approved.token.token.id))
    }

    @Test
    fun theFirstLoginOfAnInstallationRegistersAndLaterOnesReuseTheDevice() = runTest {
        val first = login(ada)
        val device = devices.devices.values.single()
        assertEquals(device.id, first.deviceId, "The token is a sign-in of the device")
        assertEquals(
            listOf("Work laptop", "macos", "15.1", ada),
            listOf(device.name, device.platform, device.osVersion, device.userId),
        )
        assertEquals(installation, device.installationId)
        val registered = audit.entries.single { it.action == "device.registered" }
        assertEquals(
            listOf(null, ada, "device", device.id.toString()),
            listOf(registered.orgId, registered.actorId, registered.targetType, registered.targetId),
        )
        assertEquals(mapOf("platform" to "macos"), registered.details, "Metadata only")

        clock.now += 1.hours
        val again = login(ada, name = "Renamed by the CLI", platform = "linux", osVersion = null)
        val reused = devices.devices.values.single()
        assertEquals(device.id, again.deviceId)
        assertNotEquals(first.id, again.id, "Independent sessions of one device")
        assertEquals("Work laptop", reused.name, "The name is kept")
        assertEquals(
            listOf("linux", null),
            listOf(reused.platform, reused.osVersion),
            "What it reports replaces what was",
        )
        assertEquals(clock.now, reused.lastLoginAt)
        assertEquals(device.createdAt, reused.createdAt)
        assertEquals(1, audit.entries.count { it.action == "device.registered" }, "Registered once")

        val quiet = login(ada, platform = null, osVersion = null)
        assertEquals(device.id, quiet.deviceId)
        assertEquals(
            listOf("linux", null),
            devices.devices.values.single().let { listOf(it.platform, it.osVersion) },
            "An older client keeps what was",
        )
    }

    @Test
    fun theSameInstallationUnderAnotherPersonIsAnotherDevice() = runTest {
        val adas = login(ada)
        val beas = login(bea)
        assertNotEquals(adas.deviceId, beas.deviceId)
        assertEquals(2, devices.devices.size)
    }

    @Test
    fun aLoginWithoutInstallationOrAsAnAdminRegistersNothing() = runTest {
        val legacy = login(ada, id = null, name = "ignored", platform = "linux", osVersion = "6.1")
        assertNull(legacy.deviceId)
        val started = start("host", "ip", null, DeviceLoginIdentity(null, "ignored", "linux", "6.1"))
        assertNull(started.authorization.installationId)
        assertNull(started.authorization.deviceName)
        assertNull(started.authorization.platform)

        val admin = start("host", "ip", ADMIN_SCOPES.toList(), DeviceLoginIdentity(installation, "x", "macos", null))
        assertNull(admin.authorization.installationId, "An admin login never registers a device")
        start("host", "ip", ADMIN_SCOPES.toList(), DeviceLoginIdentity("NOT-A-UUID", "x", "macos", null))
        answer(ada, admin.authorization.userCode, approve = true) { orgA }
        clock.now += 6.seconds
        assertIs<DevicePollResult.Approved>(poll(admin.deviceCode))
        assertTrue(devices.devices.isEmpty())
        assertTrue(audit.entries.none { it.action == "device.registered" })
    }

    @Test
    fun theStartKeepsWhatTheCliSaidOfItsInstallation() = runTest {
        val started = start("host", "ip", null, DeviceLoginIdentity(installation, "  Desk  ", "ios", "18.0 beta"))
        val kept = started.authorization
        assertEquals(
            listOf(installation, "Desk", "ios", "18.0 beta"),
            listOf(kept.installationId, kept.deviceName, kept.platform, kept.osVersion),
        )
        // Without a name, the client's name is the device's.
        val unnamed = login(ada, name = null)
        assertEquals("host", devices.devices[unnamed.deviceId]?.name)
    }

    @Test
    fun whatTheCliSaysIsCheckedBeforeAnythingIsStored() = runTest {
        assertFailsWith<InvalidInstallationException> { start(
            "h",
            "ip",
            null,
            DeviceLoginIdentity("6F1C0C3E-9A40-4A39-8E0E-2B1F3F8A1A11"),
        ) }
        assertFailsWith<InvalidInstallationException> { start("h", "ip", null, DeviceLoginIdentity("not-a-uuid")) }
        for (name in listOf("", "   ", "a".repeat(121), "line\nbreak", "tab\u0007")) {
            assertFailsWith<InvalidDeviceNameException>(
                name,
            ) { start("h", "ip", null, DeviceLoginIdentity(installation, name)) }
        }
        assertFailsWith<InvalidPlatformException> { start(
            "h",
            "ip",
            null,
            DeviceLoginIdentity(installation, platform = "beos"),
        ) }
        for (version in listOf("", " ", "x".repeat(65), "a\tb", "a\nb", "a\u00a0b")) {
            assertFailsWith<InvalidOsVersionException>(version) {
                start("h", "ip", null, DeviceLoginIdentity(installation, platform = "linux", osVersion = version))
            }
        }
        assertFailsWith<InvalidOsVersionException>("Only with a platform") {
            start("h", "ip", null, DeviceLoginIdentity(installation, osVersion = "15.1"))
        }
        assertTrue(authorizations.authorizations.isEmpty(), "Nothing stored")
        assertTrue(limiter.keys.isEmpty(), "No rate-limit token spent")
        assertEquals(DeviceLoginIdentity(installation, "a".repeat(120), "linux", "x".repeat(64)), validatedInstallation(
            DeviceLoginIdentity(installation, "a".repeat(120), "linux", "x".repeat(64)),
        ))
        assertNull(validatedInstallation(null))
    }

    @Test
    fun theFiftyFirstInstallationIsRefusedAtTheApprovalAndAnExistingOneIsNot() = runTest {
        repeat(MAX_REGISTERED_DEVICES) { index ->
            val id = "00000000-0000-4000-8000-%012d".format(index)
            login(ada, id = id)
        }
        assertEquals(MAX_REGISTERED_DEVICES, devices.count(ada))
        val started = start("host", "ip", null, DeviceLoginIdentity(other, "One too many"))
        assertFailsWith<DeviceLimitException> { answer(ada, started.authorization.userCode, approve = true) }
        assertEquals(MAX_REGISTERED_DEVICES, devices.count(ada), "Nothing registered")
        // Denying it is always possible, and so is signing in on a device already registered.
        answer(ada, started.authorization.userCode, approve = false)
        val known = login(ada, id = "00000000-0000-4000-8000-000000000003")
        assertEquals(MAX_REGISTERED_DEVICES, devices.count(ada))
        assertTrue(known.deviceId != null)
        // Somebody else is not limited by it.
        login(bea, id = other)
    }

    @Test
    fun theLimitAtTheTokenIssueIsRefusedToo() = runTest {
        val started = start("host", "ip", null, DeviceLoginIdentity(other))
        answer(ada, started.authorization.userCode, approve = true)
        repeat(MAX_REGISTERED_DEVICES) { index ->
            devices.signIn(
                ada,
                DeviceSignInRequest("00000000-0000-4000-8000-%012d".format(index), "d", null, null),
                clock.now,
            )
        }
        clock.now += 6.seconds
        assertFailsWith<DeviceLimitException> { poll(started.deviceCode) }
    }

    @Test
    fun theListShowsRegisteredDevicesWithTheirSessionsThenLegacyTokensThenElevatedOnes() = runTest {
        val laptop = login(ada)
        clock.now += 1.hours
        val laptopAgain = login(ada)
        clock.now += 1.hours
        val desk = login(ada, id = other, name = "Desk", platform = null, osVersion = null)
        val legacy = login(ada, id = null)
        val adas = elevatedToken(ada, "ada --admin")
        tokens.touch(laptopAgain.id, clock.now)
        // A device whose sign-ins are all gone is still listed.
        tokens.revoke(ada, desk.id, clock.now)

        val shown = list(ada, member, laptopAgain.id)
        assertEquals(
            listOf(desk.deviceId, laptop.deviceId, legacy.id, adas.id),
            shown.map { it.id },
            "Latest sign-in first",
        )
        val first = shown[1]
        assertEquals(2, first.activeSessions)
        assertEquals(true, first.current)
        assertEquals(clock.now, first.lastSeenAt, "The newest sign-in's last call")
        assertEquals(listOf("Work laptop", "macos", "15.1"), listOf(first.name, first.platform, first.osVersion))
        assertEquals(devices.devices[laptop.deviceId]?.createdAt, first.createdAt)
        assertEquals(devices.devices[laptop.deviceId]?.lastLoginAt, first.lastLoginAt)
        assertEquals(0, shown[0].activeSessions, "Still listed without a live sign-in")
        assertEquals(false, shown[0].current)
        assertNull(shown[2].activeSessions)
        assertNull(shown[2].current)
        assertEquals("admin", shown[3].toDeviceResponse().kind)
        assertEquals(
            listOf(desk.deviceId, laptop.deviceId, legacy.id),
            list(ada).map { it.id },
            "No elevated token without an organization",
        )
        assertEquals(
            true,
            list(ada, null, legacy.id).first { it.id == legacy.id }.current,
            "A token listed on its own can be the current one",
        )
        assertEquals(false, list(ada, null, null).first().current)
        assertTrue(list(ada).first().toDeviceResponse().kind == "cli")
    }

    @Test
    fun anOwnerOrAnAdminReadsAMembersDevicesAndAMemberDoesNot() = runTest {
        val laptop = login(ada)
        val adas = elevatedToken(ada, "ada --admin")
        val shown = listMember(owner, adaMemberId.toString())
        assertEquals(listOf(laptop.deviceId, adas.id), shown.map { it.id })
        assertTrue(shown.all { it.current != true }, "Never the caller's own")
        assertEquals(listOf(laptop.deviceId, adas.id), listMember(admin, adaMemberId.toString()).map { it.id })
        assertFailsWith<DevicesAdminRequiredException> { listMember(member, adaMemberId.toString()) }
        for (role in listOf(OrgRole.APPROVER, OrgRole.BILLING)) {
            assertFailsWith<DevicesAdminRequiredException> { listMember(
                owner.copy(role = role),
                adaMemberId.toString(),
            ) }
        }
        assertFailsWith<DeviceMemberNotFoundException> { listMember(owner, Uuid.random().toString()) }
        assertFailsWith<DeviceMemberNotFoundException> { listMember(owner, "nope") }
        assertFailsWith<DeviceMemberNotFoundException> { listMember(
            owner.copy(orgId = Uuid.random()),
            adaMemberId.toString(),
        ) }
    }

    @Test
    fun aDeviceIsRenamedByItsOwnerOrByAnOwnerOrAdminOfAnOrganizationItsOwnerIsIn() = runTest {
        val laptop = login(ada)
        val id = checkNotNull(laptop.deviceId)
        val renamed = rename(ada, id, "  Support laptop ", null, laptop.id)
        assertEquals("Support laptop", renamed.name)
        assertEquals(true, renamed.current)
        val own = audit.entries.last()
        assertEquals(
            listOf(null, ada, "device.renamed", id.toString()),
            listOf(own.orgId, own.actorId, own.action, own.targetId),
        )
        assertTrue(own.details.isEmpty(), "Metadata only: never the name")

        val byOwner = rename(bea, id, "Ada's", owner, laptop.id)
        assertEquals("Ada's", devices.devices.getValue(id).name)
        assertEquals(false, byOwner.current, "Never the caller's own")
        val audited = audit.entries.last()
        assertEquals(listOf(orgA, bea), listOf(audited.orgId, audited.actorId))
        assertEquals(ada.toString(), audited.details["owner"])
        rename(Uuid.random(), id, "By an admin", admin)
        assertEquals("By an admin", devices.devices.getValue(id).name)
        assertEquals("Plain", rename(ada, id, "Plain").name, "Neither an organization nor a token named")
    }

    @Test
    fun renamingWhatIsNotYoursOrNotADeviceIsNotFound() = runTest {
        val laptop = login(ada)
        val id = checkNotNull(laptop.deviceId)
        val legacy = login(ada, id = null)
        for (attempt in listOf<suspend () -> Unit>(
            { rename(bea, id, "x", null) },
            { rename(bea, id, "x", member) },
            { rename(bea, id, "x", owner.copy(orgId = Uuid.random())) },
            { rename(bea, id, "x", owner.copy(role = OrgRole.BILLING)) },
            { rename(ada, Uuid.random(), "x", null) },
            { rename(ada, legacy.id, "x", null) },
        )) {
            val error = assertFailsWith<DeviceNotFoundException> { attempt() }
            assertEquals("device_not_found", error.type)
        }
        assertEquals("Work laptop", devices.devices.getValue(id).name)
        devices.refuseRename = true
        assertFailsWith<DeviceNotFoundException> { rename(ada, id, "Lost to a removal", null) }
        // The name is checked first, whoever asks.
        assertFailsWith<InvalidDeviceNameException> { rename(bea, Uuid.random(), " ", null) }
    }

    @Test
    fun removingADeviceSignsEverySessionOutAndForgetsIt() = runTest {
        val first = login(ada)
        val second = login(ada)
        val kept = login(ada, id = other)
        val id = checkNotNull(first.deviceId)
        signOut(ada, id)
        assertTrue(devices.devices.keys.none { it == id })
        for (token in listOf(first, second)) {
            assertEquals(clock.now, tokens.get(ada, token.id)?.revokedAt, "Signed out with the device")
        }
        assertNull(tokens.get(ada, kept.id)?.revokedAt)
        val removed = audit.entries.last()
        assertEquals(
            listOf("device.removed", "device", id.toString(), ada),
            listOf(removed.action, removed.targetType, removed.targetId, removed.actorId),
        )
        assertTrue(removed.details.isEmpty())
        assertFailsWith<DeviceNotFoundException> { signOut(ada, id) }
        // Its next login registers the installation again, under a new id.
        assertNotEquals(id, login(ada).deviceId)
    }

    @Test
    fun anOwnerOrAnAdminRemovesAMembersDeviceAndNobodyElseDoes() = runTest {
        val id = checkNotNull(login(ada).deviceId)
        assertFailsWith<DeviceNotFoundException> { signOut(bea, id) }
        assertFailsWith<DeviceNotFoundException> { signOut(bea, id, member) }
        assertFailsWith<DeviceNotFoundException> { signOut(bea, id, owner.copy(orgId = Uuid.random())) }
        assertEquals(1, devices.devices.size)
        signOut(bea, id, owner)
        assertTrue(devices.devices.isEmpty())
        val audited = audit.entries.last()
        assertEquals(listOf(orgA, bea, "device.removed"), listOf(audited.orgId, audited.actorId, audited.action))
        assertEquals(ada.toString(), audited.details["owner"])
        val another = checkNotNull(login(ada).deviceId)
        devices.refuseRemove = true
        assertFailsWith<DeviceNotFoundException> { signOut(ada, another) }
    }

    @Test
    fun aLoginLostToAConcurrentPollOnADeviceGivesNoTokenAndNoDevice() = runTest {
        val started = start("host", "ip", null, DeviceLoginIdentity(installation))
        answer(ada, started.authorization.userCode, approve = true)
        authorizations.refuseConsume = true
        assertEquals(DevicePollResult.Expired, poll(started.deviceCode))
        assertTrue(devices.devices.isEmpty())
        assertTrue(audit.entries.none { it.action == "device.registered" })
    }

    @Test
    fun legacyTokensStillMakeRoomAtTheLimit() = runTest {
        val first = login(ada, id = null)
        repeat(49) { clock.now += 1.seconds; login(ada, id = null) }
        clock.now += 1.seconds
        login(ada)
        assertEquals(50, tokens.listLive(ada, setOf(TokenKind.CLI), clock.now).size)
        assertNotEquals(null, tokens.get(ada, first.id)?.revokedAt, "The oldest is signed out")
    }

    @Test
    fun withoutTheOrgsModuleNoOneIsAMemberOfAnything() = runTest {
        assertFalse(NoMemberDirectory.isActiveMember(orgA, adaMemberId.toString()))
        assertNull(NoMemberDirectory.userIdOf(orgA, adaMemberId.toString()))
        assertFalse(NoMemberDirectory.hasUser(orgA, ada))
    }

    private suspend fun elevatedToken(userId: Uuid, name: String): ApiToken {
        val token = ApiToken(
            Uuid.random(), userId, orgA, name, TokenKind.ADMIN, CLI_SCOPES, "osg_hint", null, clock.now,
            clock.now + 8.hours, null, null,
        )
        tokens.create(token, "h(${token.id})")
        return token
    }
}
