// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.cloud.CloudModule
import run.ostrom.server.cloud.domain.CloudClusterAccess
import run.ostrom.server.cloud.domain.CloudClustersRepository
import run.ostrom.server.cloud.domain.CloudProfile
import run.ostrom.server.cloud.domain.CloudProfileSpec
import run.ostrom.server.cloud.domain.CloudProfilesRepository
import run.ostrom.server.cloud.domain.StoredCloudCluster
import run.ostrom.server.kernel.settings.InstanceAdministrators
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.CreateCloudSessionPayload
import run.ostrom.server.runners.domain.CreateCloudSessionUseCase
import run.ostrom.server.runners.domain.NoSessionProfiles
import run.ostrom.server.runners.domain.ProfileRequiredException
import run.ostrom.server.runners.domain.ProfileUnassignedException
import run.ostrom.server.runners.domain.ReconcileCloudSessionUseCase
import run.ostrom.server.runners.domain.SessionProfileNotFoundException
import run.ostrom.server.runners.domain.SessionProfiles
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.ALICE
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.ORG_B
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.fakes.testIdentitiesWith
import run.ostrom.server.runners.infrastructure.RunnersConfig
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.runners.presentation.CloudSessionProfileDto
import run.ostrom.server.runners.presentation.CloudSessionResponse
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** `runners.cloud.profiles.v1` on PostgreSQL: the snapshot column, the former variables' cluster and the refusals. */
class SessionProfilesPostgresTest {
    private val overrides = module {
        includes(testIdentitiesWith(emptyMap()))
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        single<InstanceAdministrators> { InstanceAdministrators { false } }
    }
    private val t0 = Instant.parse("2026-11-14T08:00:00Z")

    // A server whose sessions run on Kubernetes, with the former variables still set.
    private val kubernetes = module {
        includes(
            testIdentitiesWith(
                mapOf(
                    "OSTROM_RUNNERS_DRIVER" to "kubernetes",
                    "OSTROM_RUNNERS_KUBERNETES_STORAGE_CLASS" to "fast",
                    "OSTROM_RUNNERS_WORKSPACE_SIZE" to "20Gi",
                    "OSTROM_PUBLIC_URL" to "https://ostrom.acme.dev",
                ),
            ),
        )
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        single<InstanceAdministrators> { InstanceAdministrators { false } }
    }

    @Test
    fun aSessionOfTheFormerVariablesIsGivenTheirInClusterClusterAtItsNextStep() = serverTest(
        listOf(CloudModule, RunnersModule),
        kubernetes,
    ) { server ->
        val koin = server.koin
        val sessions = koin.get<CloudSessionsRepository>()
        val clusters = koin.get<CloudClustersRepository>()
        assertEquals(RunnersConfig.DriverKind.KUBERNETES, koin.get<RunnersConfig>().driver)
        val cluster = clusters.list().single()
        assertTrue(cluster.inCluster && cluster.imported, "Imported and marked at the start: no cluster existed")
        assertEquals("fast", cluster.defaultStorageClass)
        // Sessions created before cloud profiles, in two organizations, whatever happened since the import.
        val ofA = session(state = SessionState.PAUSED, createdAt = t0)
        val ofB = session(orgId = ORG_B, state = SessionState.PAUSED, createdAt = t0)
        assertTrue(sessions.createWithinCap(ofA, 5) && sessions.createWithinCap(ofB, 5))
        val reconcile = koin.get<ReconcileCloudSessionUseCase>()
        for (legacy in listOf(ofA, ofB)) {
            reconcile(checkNotNull(sessions.get(legacy.orgId, legacy.id)))
            val profile = sessions.get(legacy.orgId, legacy.id)?.profile
            assertEquals(cluster.id.toString(), profile?.clusterId)
            assertEquals(20, profile?.workspaceGibibytes)
            assertNull(profile?.origin)
        }
        assertEquals(1, clusters.list().size, "Imported once")
        // How long its cluster has been unreachable is kept between two steps.
        val marked = checkNotNull(sessions.get(ORG_A, ofA.id)).copy(clusterUnreachableSince = t0)
        assertEquals(t0, sessions.compareAndSet(marked, null)?.clusterUnreachableSince)
        assertEquals(t0, sessions.get(ORG_A, ofA.id)?.clusterUnreachableSince)
    }

    @Test
    fun everySessionOfTheFormerVariablesIsGivenItsProfileAtTheNextStartOnce() = serverTest(
        listOf(CloudModule, RunnersModule),
        kubernetes,
    ) { server ->
        val koin = server.koin
        val sessions = koin.get<CloudSessionsRepository>()
        val cluster = koin.get<CloudClustersRepository>().list().single()
        // Before the upgrade: paused for months in two organizations, never stepped again; one ended long ago.
        val ofA = session(state = SessionState.PAUSED, createdAt = t0)
        val ofB = session(orgId = ORG_B, state = SessionState.PAUSED, createdAt = t0)
        val ended = session(state = SessionState.TERMINATED, createdAt = t0)
        for (legacy in listOf(ofA, ofB, ended)) assertTrue(sessions.createWithinCap(legacy, 5))
        val first = sessions.withoutProfile(null, 1).single()
        val rest = sessions.withoutProfile(first.id, 10)
        assertEquals(setOf(ofA.id, ofB.id), (listOf(first) + rest).map { it.id }.toSet(), "By id after the cursor")

        RunnersModule.initialize(koin)

        for (legacy in listOf(ofA, ofB)) {
            val given = checkNotNull(sessions.get(legacy.orgId, legacy.id))
            assertEquals(cluster.id.toString(), given.profile?.clusterId)
            assertEquals(20, given.profile?.workspaceGibibytes)
            assertEquals(SessionState.PAUSED, given.state, "Given its profile, nothing else")
        }
        assertNull(sessions.get(ORG_A, ended.id)?.profile, "A terminated session has nothing left to run")
        assertEquals(emptyList(), sessions.withoutProfile(null, 10))
        val generations = listOf(ofA, ofB).map { sessions.get(it.orgId, it.id)?.generation }
        RunnersModule.initialize(koin)
        assertEquals(generations, listOf(ofA, ofB).map { sessions.get(it.orgId, it.id)?.generation }, "Then a no-op")
    }

    @Test
    fun anUnsetDriverKeepsAnUpgradedServerOffKubernetes() = serverTest(
        listOf(CloudModule, RunnersModule),
        overrides,
    ) { server ->
        val koin = server.koin
        assertEquals(RunnersConfig.DriverKind.NONE, koin.get<RunnersConfig>().driver, "Nothing switched by itself")
        assertEquals(NoSessionProfiles, koin.get<SessionProfiles>())
        assertEquals(emptyList(), koin.get<CloudClustersRepository>().list(), "Nothing imported")
    }

    @Test
    fun aSessionIsCreatedOnItsProfileOrRefused() = serverTest(
        listOf(CloudModule, RunnersModule),
        kubernetes,
    ) { server ->
        val koin = server.koin
        val create = koin.get<CreateCloudSessionUseCase>()
        val alice = Caller(ORG_A, ALICE.toString())
        assertFailsWith<ProfileRequiredException> { create(alice, CreateCloudSessionPayload(null, "claude", null)) }

        val clusterId = Uuid.random()
        val cluster = run.ostrom.server.cloud.domain.CloudCluster(
            clusterId, "prod", "https://k8s.acme.dev", listOf("sessions"), 10, "standard", 1,
            hasCaCertificate = true, hasServiceAccountToken = true, createdAt = t0, updatedAt = t0,
        )
        val cipher = koin.get<SecretCipher>()
        val ca = cipher.seal("PEM".encodeToByteArray(), "cloud-cluster:$clusterId:ca")
        val token = cipher.seal("t".encodeToByteArray(), "cloud-cluster:$clusterId:token")
        koin.get<CloudClustersRepository>().insert(StoredCloudCluster(cluster, ca, token))
        assertEquals("t", koin.get<CloudClusterAccess>().credentials(clusterId)?.serviceAccountToken)
        val spec = CloudProfileSpec(clusterId.toString(), "sessions", "ghcr.io/acme/web:1")
        val assigned = CloudProfile(Uuid.random(), ORG_A, "web", 1, spec, setOf(ALICE.toString(), "alice"), t0, t0)
        val other = assigned.copy(id = Uuid.random(), name = "data", assignedMemberIds = emptySet())
        val profiles = koin.get<CloudProfilesRepository>()
        assertTrue(profiles.publish(assigned) && profiles.publish(other))

        val created = create(alice, CreateCloudSessionPayload(null, "claude", null))
        val stored = koin.get<CloudSessionsRepository>().get(ORG_A, created.id)?.profile
        assertEquals(assigned.id.toString() to 1, stored?.origin?.id to stored?.origin?.version)
        assertEquals("standard", stored?.storageClass)
        assertNull(koin.get<SessionProfiles>().startFailure(created))
        assertFailsWith<ProfileUnassignedException> {
            create(alice, CreateCloudSessionPayload(null, "claude", null, profileId = other.id))
        }
        assertFailsWith<SessionProfileNotFoundException> {
            create(Caller(ORG_B, "carol"), CreateCloudSessionPayload(null, "claude", null, profileId = assigned.id))
        }

        // Through the route: a malformed id names no profile; the session says what it runs on.
        suspend fun post(body: String) = server.client.post("/api/v1/cloud/sessions") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val malformed = post("""{"agent":"claude","profileId":"not-an-id"}""")
        assertEquals(HttpStatusCode.NotFound, malformed.status)
        assertTrue("cloud_profile_not_found" in malformed.bodyAsText())
        val unassigned = post("""{"agent":"claude","profileId":"${other.id}"}""")
        assertEquals(HttpStatusCode.Forbidden, unassigned.status)
        assertTrue("profile_unassigned" in unassigned.bodyAsText())
        val view = post("""{"agent":"claude","profileId":"${assigned.id}"}""").body<CloudSessionResponse>()
        assertEquals(CloudSessionProfileDto(assigned.id.toString(), 1), view.profile)
        assertEquals("ghcr.io/acme/web:1", view.image)
        val backFilled = session(state = SessionState.PAUSED, ownerId = view.owner, createdAt = t0).copy(
            profile = run.ostrom.server.runners.domain.SessionProfile(
                clusterId = clusterId.toString(), namespace = "sessions", image = "runner:1", workspaceGibibytes = 10,
            ),
        )
        assertTrue(koin.get<CloudSessionsRepository>().createWithinCap(backFilled, 50))
        val read = server.client.get("/api/v1/cloud/sessions/${backFilled.id}") { bearerAuth("alice") }
            .body<CloudSessionResponse>()
        assertNull(read.profile, "A back-filled session runs on no profile")
        assertEquals("runner:1", read.image)
    }
}
