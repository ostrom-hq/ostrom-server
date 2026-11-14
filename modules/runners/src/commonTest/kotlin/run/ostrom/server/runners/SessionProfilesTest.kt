// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.BackgroundWork
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CatalogSessionProfiles
import run.ostrom.server.runners.domain.CloudProfileCatalog
import run.ostrom.server.runners.domain.ClusterUnavailableException
import run.ostrom.server.runners.domain.CreateCloudSessionPayload
import run.ostrom.server.runners.domain.CreateCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.ImageNotAllowedException
import run.ostrom.server.runners.domain.InvalidCloudSessionRequestException
import run.ostrom.server.runners.domain.NamespaceNotAllowedException
import run.ostrom.server.runners.domain.NoSessionProfiles
import run.ostrom.server.runners.domain.ProfileCluster
import run.ostrom.server.runners.domain.ProfileOption
import run.ostrom.server.runners.domain.ProfileOrigin
import run.ostrom.server.runners.domain.ProfileRequiredException
import run.ostrom.server.runners.domain.ProfileUnassignedException
import run.ostrom.server.runners.domain.ReconcileCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.RepositoryCloudUsageMeter
import run.ostrom.server.runners.domain.RunnerDelivery
import run.ostrom.server.runners.domain.RunnerDeliverySource
import run.ostrom.server.runners.domain.SessionProfile
import run.ostrom.server.runners.domain.SessionProfileNotFoundException
import run.ostrom.server.runners.domain.SessionResources
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.FakeRunnerTokens
import run.ostrom.server.runners.fakes.FakeRuntimeDriver
import run.ostrom.server.runners.fakes.FixedPlans
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** `runners.cloud.profiles.v1`: a Kubernetes session's profile, chosen at creation and checked at each start. */
class SessionProfilesTest {
    private val catalog = FakeCatalog()
    private var serverUrl = "https://ostrom.acme.dev"
    private val delivery = RunnerDeliverySource { RunnerDelivery("runner:1", "/ostrom", "runner:1", serverUrl) }
    private val legacy = SessionProfile(clusterId = CLUSTER, namespace = "o", image = "r:1", workspaceGibibytes = 10)
    private val profiles = CatalogSessionProfiles(catalog, delivery) { legacy }
    private val alice = Caller(ORG_A, "alice")
    private val admin = Caller(ORG_A, "root", admin = true)

    @Test
    fun anAssignedProfileIsSnapshottedWithTheClustersDefaultStorageClass() = runTest {
        val chosen = profiles.choose(alice, WEB.id, null)
        assertEquals(
            SessionProfile(
                origin = ProfileOrigin(WEB.id.toString(), 3),
                clusterId = CLUSTER,
                namespace = "sessions",
                image = "ghcr.io/acme/web:1",
                imagePullSecrets = listOf("ghcr"),
                resources = SessionResources(2000, 4096),
                workspaceGibibytes = 20,
                storageClass = "standard",
            ),
            chosen,
        )
        catalog.add(WEB.copy(storageClass = "fast"))
        assertEquals("fast", profiles.choose(alice, WEB.id, null).storageClass, "The profile's, when it names one")
    }

    @Test
    fun anotherOrganizationsOrAnUnassignedProfileIsRefused() = runTest {
        assertFailsWith<SessionProfileNotFoundException> { profiles.choose(alice, Uuid.random(), null) }
        val stranger = Caller(Uuid.random(), "alice")
        assertFailsWith<SessionProfileNotFoundException> { profiles.choose(stranger, WEB.id, null) }
        assertFailsWith<ProfileUnassignedException> { profiles.choose(alice, DATA.id, null) }
        assertEquals("ghcr.io/acme/data:2", profiles.choose(admin, DATA.id, null).image, "Owners and admins use all")
    }

    @Test
    fun withoutAProfileTheOnlyOneTheOwnerMayUseIsTaken() = runTest {
        assertEquals(WEB.id.toString(), profiles.choose(alice, null, null).origin?.id)
        assertFailsWith<ProfileRequiredException> { profiles.choose(admin, null, null) }
        assertFailsWith<ProfileRequiredException> { profiles.choose(Caller(ORG_A, "carol"), null, null) }
    }

    @Test
    fun anImageIsTheProfilesOrAnAllowedOverride() = runTest {
        assertEquals("ghcr.io/acme/web:1", profiles.choose(alice, WEB.id, " ghcr.io/acme/web:1 ").image)
        assertEquals("ghcr.io/acme/web:1", profiles.choose(alice, WEB.id, "  ").image, "A blank one is none")
        assertEquals("ghcr.io/acme/web:pr-7", profiles.choose(alice, WEB.id, "ghcr.io/acme/web:pr-7").image)
        assertEquals("docker.io/acme/tools:3", profiles.choose(alice, WEB.id, "docker.io/acme/tools:3").image)
        assertFailsWith<ImageNotAllowedException> { profiles.choose(alice, WEB.id, "ghcr.io/acme-evil/web:1") }
        assertFailsWith<ImageNotAllowedException> { profiles.choose(alice, WEB.id, "docker.io/acme/tools:3x") }
        assertFailsWith<InvalidCloudSessionRequestException> { profiles.choose(alice, WEB.id, "ghcr.io/acme/web:1 x") }
        assertTrue(CatalogSessionProfiles.matchesImagePattern("a.b/*", "a.b/c"))
        assertFalse(CatalogSessionProfiles.matchesImagePattern("a.b/*", "aXb/c"), "Everything but * is literal")
    }

    @Test
    fun anImagePatternMatchesAsAGlobWithoutBacktracking() {
        fun matches(pattern: String, image: String) = CatalogSessionProfiles.matchesImagePattern(pattern, image)
        assertTrue(matches("ghcr.io/acme/web:1", "ghcr.io/acme/web:1"), "No star: the exact image")
        assertFalse(matches("ghcr.io/acme/web:1", "ghcr.io/acme/web:10"))
        assertTrue(matches("*", ""))
        assertTrue(matches("ghcr.io/*/web:*", "ghcr.io/acme/web:pr-7"))
        assertTrue(matches("a*b*c", "abc"), "A star matches an empty run")
        assertTrue(matches("a*b*b*c", "a-b-b-b-c"), "Each middle at its first place still leaves room")
        assertFalse(matches("a*a", "a"), "The prefix and the suffix never share a character")
        assertFalse(matches("ghcr.io/*", "docker.io/acme"), "Another prefix")
        assertFalse(matches("*:1", "ghcr.io/acme:2"), "Another suffix")
        assertFalse(matches("a*x*c", "abbc"), "A middle missing")
        assertFalse(matches("a*bc*c", "abc"), "A middle only found inside the suffix")
        assertFalse(matches("a.*", "aXb"), "A dot is a dot")
        // `a*a*…*a*b` against `aaa…a` is exponential for a backtracking regex (`a.*a.*…b`): here, a few passes.
        val pathological = "a" + "*a".repeat(30) + "*b"
        val image = "a".repeat(512)
        assertFalse(matches(pathological, image))
        assertTrue(matches(pathological, image + "b"))
    }

    @Test
    fun theProfilesClusterMustBeReachableAndAllowItsNamespace() = runTest {
        catalog.clusters.remove(CLUSTER)
        assertFailsWith<ClusterUnavailableException> { profiles.choose(alice, WEB.id, null) }
        catalog.clusters[CLUSTER] = ProfileCluster(listOf("other"), null)
        assertFailsWith<NamespaceNotAllowedException> { profiles.choose(alice, WEB.id, null) }
    }

    @Test
    fun aStartChecksTheClusterAgain() = runTest {
        val running = session().copy(profile = profiles.choose(alice, WEB.id, null))
        assertNull(profiles.startFailure(running))
        assertEquals("cluster_unavailable", profiles.startFailure(running.copy(profile = null))?.code)
        catalog.clusters[CLUSTER] = ProfileCluster(listOf("other"), null)
        assertEquals("namespace_not_allowed", profiles.startFailure(running)?.code)
        catalog.clusters.remove(CLUSTER)
        assertEquals("cluster_unavailable", profiles.startFailure(running)?.code)
        assertNull(NoSessionProfiles.choose(alice, WEB.id, "x"))
        assertNull(NoSessionProfiles.startFailure(running))
    }

    @Test
    fun aStartWithoutAServerUrlForTheRunnerIsRefused() = runTest {
        val running = session().copy(profile = profiles.choose(alice, WEB.id, null))
        serverUrl = " "
        val failure = profiles.startFailure(running)
        assertEquals("server_url_missing", failure?.code)
        assertTrue(failure?.message.orEmpty().contains("OSTROM_PUBLIC_URL"), "It says what to set")
        assertTrue(failure?.retryable == true)
    }

    @Test
    fun theSessionsOfTheFormerVariablesAreGivenTheirClustersProfile() = runTest {
        assertEquals(legacy, profiles.legacy())
        assertNull(CatalogSessionProfiles(catalog, delivery).legacy(), "None without the former variables")
        assertNull(NoSessionProfiles.legacy())
    }

    @Test
    fun aStartAskedAgainUnderItsIdFindsTheFirstSessionBeforeAnyRule() = runTest {
        val repository = InMemoryCloudSessionsRepository()
        val create = CreateCloudSessionUseCaseImpl(
            repository, FixedPlans(), RepositoryCloudUsageMeter(repository), SettableClock(), profiles,
        )
        val id = Uuid.random()
        val first = create(alice, CreateCloudSessionPayload(null, "claude", null, id = id))
        // The profile is no longer usable, yet the node restarted after a crash finds its session.
        catalog.clusters.remove(CLUSTER)
        assertEquals(first, create(alice, CreateCloudSessionPayload(null, "claude", null, id = id)))
        assertFailsWith<InvalidCloudSessionRequestException> {
            create(Caller(ORG_A, "bob"), CreateCloudSessionPayload(null, "claude", null, id = id))
        }
        assertEquals(1, repository.sessions.size)
    }

    @Test
    fun aCreatedSessionKeepsItsProfileAndAnUnusableOneCreatesNothing() = runTest {
        val repository = InMemoryCloudSessionsRepository()
        val create = CreateCloudSessionUseCaseImpl(
            repository, FixedPlans(), RepositoryCloudUsageMeter(repository), SettableClock(), profiles,
        )
        val created = create(alice, CreateCloudSessionPayload(null, "claude", null, image = "ghcr.io/acme/web:pr-1"))
        assertEquals("ghcr.io/acme/web:pr-1", repository.sessions.getValue(created.id).profile?.image)
        assertFailsWith<ProfileUnassignedException> {
            create(alice, CreateCloudSessionPayload(null, "claude", null, profileId = DATA.id))
        }
        assertEquals(1, repository.sessions.size)
    }

    @Test
    fun aFirstStartOnAGoneClusterFailsAndAResumeStaysPaused() = runTest {
        val repository = InMemoryCloudSessionsRepository()
        val driver = FakeRuntimeDriver()
        val clock = SettableClock()
        val reconcile = ReconcileCloudSessionUseCaseImpl(
            repository, driver, FakeRunnerTokens(), FixedPlans(), RepositoryCloudUsageMeter(repository), clock,
            profiles = profiles,
        ) { BackgroundWork.DEFAULT_MAX_MINUTES }
        val profile = profiles.choose(alice, WEB.id, null)
        serverUrl = ""
        val noUrl = session().copy(profile = profile).also { repository.put(it) }
        assertNull(reconcile(noUrl))
        assertEquals("server_url_missing", repository.sessions.getValue(noUrl.id).failure?.code)
        assertEquals(SessionState.FAILED, repository.sessions.getValue(noUrl.id).state, "No pod started for nothing")
        serverUrl = "https://ostrom.acme.dev"
        catalog.clusters.remove(CLUSTER)
        val first = session().copy(profile = profile).also { repository.put(it) }
        assertNull(reconcile(first))
        val failed = repository.sessions.getValue(first.id)
        assertEquals(SessionState.FAILED, failed.state)
        assertEquals("cluster_unavailable", failed.failure?.code)

        val resume = session(state = SessionState.RESUMING).copy(profile = profile, epoch = 2)
        repository.put(resume)
        assertNull(reconcile(resume))
        val paused = repository.sessions.getValue(resume.id)
        assertEquals(SessionState.PAUSED, paused.state)
        assertEquals(DesiredState.PAUSED, paused.desiredState, "Not resumed again by itself")
        assertEquals("cluster_unavailable", paused.failure?.code)
        assertTrue(driver.calls.isEmpty(), "Nothing asked of a cluster that is not there")
    }

    private class FakeCatalog : CloudProfileCatalog {
        private val all = linkedMapOf(WEB.id to WEB, DATA.id to DATA)
        val clusters = mutableMapOf(CLUSTER to ProfileCluster(listOf("sessions"), "standard"))

        fun add(profile: ProfileOption) {
            all[profile.id] = profile
        }

        override suspend fun profiles(orgId: Uuid): List<ProfileOption> =
            if (orgId == ORG_A) all.values.toList() else emptyList()

        override suspend fun profile(orgId: Uuid, id: Uuid): ProfileOption? = all[id]?.takeIf { orgId == ORG_A }

        override suspend fun cluster(id: String): ProfileCluster? = clusters[id]
    }

    private companion object {
        const val CLUSTER = "0192f6a1-7c3e-7d4a-9b2f-1a2b3c4d5e6f"
        val WEB = ProfileOption(
            id = Uuid.random(),
            version = 3,
            assignedMemberIds = setOf("alice"),
            clusterId = CLUSTER,
            namespace = "sessions",
            image = "ghcr.io/acme/web:1",
            allowedImageOverrides = listOf("ghcr.io/acme/web:*", "docker.io/acme/tools:3"),
            imagePullSecrets = listOf("ghcr"),
            resources = SessionResources(2000, 4096),
            workspaceGibibytes = 20,
            storageClass = null,
        )
        val DATA = WEB.copy(
            id = Uuid.random(),
            version = 1,
            assignedMemberIds = emptySet(),
            image = "ghcr.io/acme/data:2",
        )
    }
}
