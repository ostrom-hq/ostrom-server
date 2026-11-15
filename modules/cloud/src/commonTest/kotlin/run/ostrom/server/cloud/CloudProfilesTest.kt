// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud

import run.ostrom.server.cloud.domain.CloudCluster
import run.ostrom.server.cloud.domain.CloudProfileConflictException
import run.ostrom.server.cloud.domain.CloudProfileInput
import run.ostrom.server.cloud.domain.CloudProfileNotFoundException
import run.ostrom.server.cloud.domain.CloudProfileSpec
import run.ostrom.server.cloud.domain.CloudProfilesUseCaseImpl
import run.ostrom.server.cloud.domain.CloudResources
import run.ostrom.server.cloud.domain.InvalidCloudProfileException
import run.ostrom.server.cloud.domain.InvalidEffectiveMemberException
import run.ostrom.server.cloud.domain.ProfilesMemberForbiddenException
import run.ostrom.server.cloud.domain.StorageClassLookup
import run.ostrom.server.cloud.domain.StorageClassMissingException
import run.ostrom.server.cloud.domain.StorageClassRetainsVolumesException
import run.ostrom.server.cloud.domain.StoredCloudCluster
import run.ostrom.server.cloud.domain.WorkspaceStorageClasses
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.secrets.domain.AdminRequiredException
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.MemberRole
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

class CloudProfilesTest {
    private val clusters = MemoryClusters()
    private val repository = MemoryProfiles()
    private val clock = SettableClock()
    private val profiles = CloudProfilesUseCaseImpl(repository, clusters, clock, EveryoneIsAMember)
    private val cluster = CloudCluster(
        Uuid.random(), "prod", "https://k8s.acme.dev", listOf("sessions"), 10, null, 1, true, true, NOW, NOW,
    )
    private val admin = Caller(ORG_A, ALICE, MemberRole.ADMIN)
    private val member = Caller(ORG_A, BOB, MemberRole.MEMBER)
    private val input = CloudProfileInput(
        name = " Default ",
        version = 0,
        spec = CloudProfileSpec(
            clusterId = cluster.id.toString().uppercase(),
            namespace = " sessions ",
            image = " ghcr.io/acme/session:3 ",
            allowedImageOverrides = listOf("ghcr.io/acme/*", "ghcr.io/acme/*"),
            imagePullSecrets = listOf("regcred"),
            storageClass = " ",
            agentVersion = " ",
        ),
        assignedMemberIds = setOf(" $BOB ", " "),
    )

    private suspend fun connect() {
        clusters.insert(StoredCloudCluster(cluster, "v1:ca", "v1:token"))
    }

    @Test
    fun anAdminPublishesVersionsAMemberSeesTheProfilesAssignedToThem() = runTest {
        connect()
        assertFailsWith<AdminRequiredException> { profiles.create(member, input) }

        val first = profiles.create(admin, input)
        clock.advance(1.minutes)
        val second = profiles.update(admin, first.id, input.copy(version = 1, assignedMemberIds = emptySet()))

        assertEquals("Default", first.name)
        assertEquals(cluster.id.toString(), first.spec.clusterId)
        assertEquals("ghcr.io/acme/session:3", first.spec.image)
        assertEquals(listOf("ghcr.io/acme/*"), first.spec.allowedImageOverrides)
        assertEquals(setOf(BOB.toString()), first.assignedMemberIds)
        assertEquals(2, second.version)
        assertEquals(NOW + 1.minutes, second.updatedAt)
        assertEquals(2, repository.versions.size)
        assertEquals(listOf(second), profiles.list(admin))
        assertEquals(emptyList(), profiles.list(member))
        profiles.update(admin, first.id, input.copy(version = 2))
        assertEquals(listOf(3), profiles.list(member).map { it.version })
        assertEquals(emptyList(), profiles.list(Caller(ORG_B, ALICE, MemberRole.OWNER)))
    }

    /** `runners.cloud.profiles.member.v1`: an administrator reads the profiles a member can create sessions from. */
    @Test
    fun anAdministratorListsAnotherMembersProfilesByIdOrLogin() = runTest {
        connect()
        val carol = Uuid.parse("00000000-0000-0000-0000-00000000ca01")
        val gone = Uuid.parse("00000000-0000-0000-0000-00000000de1e")
        val directory = object : MemberDirectory {
            override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean =
                orgId == ORG_A && memberId != gone.toString()

            override suspend fun roleOf(orgId: Uuid, memberId: String): OrgRole? =
                if (memberId == carol.toString()) OrgRole.OWNER else OrgRole.MEMBER
        }
        val logins = object : MemberLogins {
            private val ids = mapOf("alice" to ALICE, "bob" to BOB, "carol" to carol)

            override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> =
                ids.filterKeys { it in logins }

            override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> = emptyMap()
        }
        val named = CloudProfilesUseCaseImpl(repository, clusters, clock, directory, logins = logins)
        val bobs = named.create(admin, input)
        val others = named.create(admin, input.copy(name = "Other", assignedMemberIds = emptySet()))

        // The caller themselves, by id or login, as without the parameter.
        assertEquals(listOf(bobs), named.list(member, BOB.toString()))
        assertEquals(listOf(bobs), named.list(member, "bob"))
        assertEquals(setOf(bobs, others), named.list(admin, "alice").toSet())
        // Another member: the profiles assigned to them, every one for an owner or an admin.
        assertEquals(listOf(bobs), named.list(admin, "bob"))
        assertEquals(setOf(bobs, others), named.list(admin, carol.toString()).toSet())
        // A member never names someone else, known or not.
        assertFailsWith<ProfilesMemberForbiddenException> { named.list(member, "alice") }
        assertFailsWith<ProfilesMemberForbiddenException> { named.list(member, "ghost") }
        // Nobody by that login, a member who left: not a member to list for.
        assertFailsWith<InvalidEffectiveMemberException> { named.list(admin, "ghost") }
        assertFailsWith<InvalidEffectiveMemberException> { named.list(admin, gone.toString()) }
        // Without the orgs module's logins, a login names nobody.
        assertFailsWith<InvalidEffectiveMemberException> { profiles.list(admin, "bob") }
    }

    @Test
    fun anotherOrganizationsProfileOrAStaleVersionIsRefused() = runTest {
        connect()
        val profile = profiles.create(admin, input)

        assertFailsWith<CloudProfileNotFoundException> {
            profiles.update(Caller(ORG_B, ALICE, MemberRole.OWNER), profile.id, input.copy(version = 1))
        }
        assertFailsWith<AdminRequiredException> { profiles.update(member, profile.id, input.copy(version = 1)) }
        assertFailsWith<CloudProfileConflictException> { profiles.update(admin, profile.id, input) }
        assertFailsWith<CloudProfileConflictException> { profiles.create(admin, input.copy(version = 1)) }
        repository.lose = true
        assertFailsWith<CloudProfileConflictException> { profiles.create(admin, input) }
        assertFailsWith<CloudProfileConflictException> { profiles.update(admin, profile.id, input.copy(version = 1)) }
    }

    @Test
    fun aSpecTheServerRefusesIsNamed() = runTest {
        connect()
        val spec = input.spec
        suspend fun refused(value: CloudProfileInput) =
            assertFailsWith<InvalidCloudProfileException> { profiles.create(admin, value) }.message.orEmpty()
        fun with(change: CloudProfileSpec) = input.copy(spec = change)

        assertTrue("name" in refused(input.copy(name = " ")))
        assertTrue("name" in refused(input.copy(name = "a".repeat(101))))
        assertTrue("clusterId" in refused(with(spec.copy(clusterId = "not-an-id"))))
        assertTrue("clusterId" in refused(with(spec.copy(clusterId = Uuid.random().toString()))))
        assertTrue("namespace" in refused(with(spec.copy(namespace = "other"))))
        assertTrue("spec.image" in refused(with(spec.copy(image = "bad image"))))
        assertTrue("allowedImageOverrides" in refused(with(spec.copy(allowedImageOverrides = listOf("bad image*")))))
        assertTrue("allowedImageOverrides" in refused(with(spec.copy(allowedImageOverrides = listOf("ghcr.io/acme*")))))
        assertTrue("allowedImageOverrides" in refused(with(spec.copy(allowedImageOverrides = listOf("bad image/*")))))
        assertTrue(
            "allowedImageOverrides" in refused(with(spec.copy(allowedImageOverrides = (1..51).map { "i$it" }))),
        )
        assertTrue("imagePullSecrets" in refused(with(spec.copy(imagePullSecrets = listOf("Bad_Name")))))
        assertTrue("imagePullSecrets" in refused(with(spec.copy(imagePullSecrets = (1..21).map { "s$it" }))))
        assertTrue("resources" in refused(with(spec.copy(resources = CloudResources(cpuMillis = 50)))))
        assertTrue("resources" in refused(with(spec.copy(resources = CloudResources(memoryMebibytes = 64)))))
        assertTrue("resources" in refused(with(spec.copy(resources = CloudResources(cpuMillis = 64_001)))))
        assertTrue("resources" in refused(with(spec.copy(resources = CloudResources(memoryMebibytes = 262_145)))))
        assertTrue("workspaceSizeGibibytes" in refused(with(spec.copy(workspaceSizeGibibytes = 0))))
        assertTrue("workspaceSizeGibibytes" in refused(with(spec.copy(workspaceSizeGibibytes = 2049))))
        assertTrue("storageClass" in refused(with(spec.copy(storageClass = "Bad Class"))))
        assertTrue("agentKind" in refused(with(spec.copy(agentKind = "other"))))
        assertTrue("idle" in refused(with(spec.copy(minIdleTimeoutMinutes = 0))))
        assertTrue("idle" in refused(with(spec.copy(minIdleTimeoutMinutes = 1441))))
        assertTrue("idle" in refused(with(spec.copy(maxIdleTimeoutMinutes = 0, minIdleTimeoutMinutes = 1))))
        assertTrue("idle" in refused(with(spec.copy(maxIdleTimeoutMinutes = 1441))))
        assertTrue("idle" in refused(with(spec.copy(idleTimeoutMinutes = 100, maxIdleTimeoutMinutes = 60))))
        assertTrue("idle" in refused(with(spec.copy(idleTimeoutMinutes = 2, minIdleTimeoutMinutes = 5))))
        assertTrue(
            "assignedMemberIds" in refused(input.copy(assignedMemberIds = (1..1001).map { "m$it" }.toSet())),
        )
        val chosen = with(spec.copy(storageClass = "fast", agentVersion = "2.1", agentKind = "codex"))
        assertEquals("fast", profiles.create(admin, chosen).spec.storageClass)
    }

    @Test
    fun onlyTheOrganizationsMembersAreAssigned() = runTest {
        connect()
        val directory = object : MemberDirectory {
            override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean =
                orgId == ORG_A && memberId == BOB.toString()
        }
        val checked = CloudProfilesUseCaseImpl(repository, clusters, clock, directory)
        val base = input.copy(assignedMemberIds = setOf(BOB.toString()))

        assertEquals(setOf(BOB.toString()), checked.create(admin, base).assignedMemberIds)
        val refused = assertFailsWith<InvalidCloudProfileException> {
            checked.create(admin, base.copy(assignedMemberIds = setOf(BOB.toString(), "someone-else")))
        }
        assertTrue("not a member" in refused.message.orEmpty())
    }

    @Test
    fun aStorageClassThatIsGoneOrKeepsVolumesIsRefusedOnPublish() = runTest {
        connect()
        val asked = mutableListOf<String?>()
        var answer: StorageClassLookup = StorageClassLookup.Found("fast", "Delete")
        val storage = WorkspaceStorageClasses { clusterId, name ->
            assertEquals(cluster.id, clusterId)
            asked += name
            answer
        }
        val checked = CloudProfilesUseCaseImpl(repository, clusters, clock, EveryoneIsAMember, storage)
        val named = input.copy(spec = input.spec.copy(storageClass = "fast"))

        val created = checked.create(admin, named)
        answer = StorageClassLookup.Missing
        assertEquals(
            "storage_class_missing",
            assertFailsWith<StorageClassMissingException> {
                checked.update(admin, created.id, named.copy(version = 1))
            }.type,
        )
        answer = StorageClassLookup.Found("fast", "Retain")
        val retains = assertFailsWith<StorageClassRetainsVolumesException> { checked.create(admin, input) }
        assertEquals("storage_class_retains_volumes", retains.type)
        assertTrue("reclaimPolicy Retain" in retains.message.orEmpty())
        answer = StorageClassLookup.Unknown
        checked.create(admin, input.copy(name = "Unread"))
        assertEquals(listOf("fast", "fast", null, null), asked, "The profile's class, else the cluster's default")
    }

    @Test
    fun withoutTheOrgsModuleNobodyIsAMember() = runTest {
        assertFalse(NoMembers.isActiveMember(ORG_A, BOB.toString()))
    }

    private object EveryoneIsAMember : MemberDirectory {
        override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean = true
    }
}
