// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.cloud.domain.CloudAdminCaller
import run.ostrom.server.cloud.domain.CloudCluster
import run.ostrom.server.cloud.domain.CloudClusterCredentials
import run.ostrom.server.cloud.domain.CloudClusterInput
import run.ostrom.server.cloud.domain.CloudClustersUseCase
import run.ostrom.server.cloud.domain.CloudProfile
import run.ostrom.server.cloud.domain.CloudProfileSpec
import run.ostrom.server.cloud.domain.CloudProfilesRepository
import run.ostrom.server.cloud.domain.CloudResources
import run.ostrom.server.cloud.domain.CloudRuntime
import run.ostrom.server.cloud.domain.CloudRuntimeSource
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.runners.domain.ProfileCluster
import run.ostrom.server.runners.domain.RuntimeUnreachableException
import run.ostrom.server.runners.domain.SessionProfile
import run.ostrom.server.runners.domain.SessionResources
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.infrastructure.CloudKubernetesClusters
import run.ostrom.server.runners.infrastructure.CloudProfileCatalogImpl
import run.ostrom.server.runners.infrastructure.LegacyKubernetesImport
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The runners module's adapters to the console's clusters and profiles, and the one-time import. */
class CloudClustersAdaptersTest {
    private val clusters = FakeClusters()

    @Test
    fun aClusterIsReachedWithItsCurrentCredentialsAndOneClientPerVersionAndCa() = runTest {
        val made = mutableListOf<String>()
        val adapter = CloudKubernetesClusters(clusters) { ca -> made += ca; HttpClient(MockEngine { respondOk() }) }
        val id = ID.toString()
        assertFailsWith<RuntimeUnreachableException> { adapter.use("not-an-id") {} }
        assertFailsWith<RuntimeUnreachableException> { adapter.use(Uuid.random().toString()) {} }
        val first = adapter.use(id) { it }
        assertEquals("https://k8s.acme.dev" to "token-1", first.apiUrl to first.token)
        clusters.token = "token-2"
        val second = adapter.use(id) { it }
        assertEquals("token-2", second.token, "The token is read at each operation")
        assertSame(first.client, second.client)
        clusters.ca = "CA-2"
        assertNotSame(first.client, adapter.use(id) { it.client }, "A new CA, a new client")
        clusters.version = 2
        assertEquals(2, adapter.use(id) { it.version }, "The cluster's version, for what is applied once per version")
        assertEquals(listOf("CA-1", "CA-2", "CA-2"), made)
        assertEquals(1, adapter.clients(), "One client kept per cluster")
        assertTrue(first.toString().contains("token=***"))
    }

    @Test
    fun aReplacedClientIsClosedByItsLastOperationNeverUnderOne() = runTest {
        val clients = mutableListOf<HttpClient>()
        val adapter = CloudKubernetesClusters(clusters) { HttpClient(MockEngine { respondOk() }).also(clients::add) }
        val id = ID.toString()
        val inFlight = CompletableDeferred<Unit>()
        val shorter = CompletableDeferred<Unit>()
        val running = async {
            adapter.use(id) { target ->
                inFlight.await()
                target.client.isActive
            }
        }
        val other = async { adapter.use(id) { shorter.await() } }
        runCurrent()
        // Replaced while the first operation still runs, then used by a second one, which closes nothing.
        clusters.version = 2
        assertEquals(2, adapter.use(id) { it.version }, "The cluster's version, for what is applied once per version")
        assertTrue(clients[0].isActive, "Not closed under the operations still running")
        shorter.complete(Unit)
        other.await()
        assertTrue(clients[0].isActive, "Not closed while one operation still runs")
        inFlight.complete(Unit)
        assertTrue(running.await(), "The operation ran on an open client")
        assertFalse(clients[0].isActive, "Closed by its last operation")
        // An idle client replaced is closed at once; the current one stays open.
        clusters.version = 3
        adapter.use(id) {}
        assertFalse(clients[1].isActive)
        assertTrue(clients[2].isActive)
    }

    @Test
    fun concurrentOperationsMakeOneClientAndLeakNone() = runTest {
        val clients = mutableListOf<HttpClient>()
        val adapter = CloudKubernetesClusters(clusters) { HttpClient(MockEngine { respondOk() }).also(clients::add) }
        val gate = CompletableDeferred<Unit>()
        val operations = List(8) { async { adapter.use(ID.toString()) { gate.await() } } }
        runCurrent()
        gate.complete(Unit)
        operations.awaitAll()
        assertEquals(1, clients.size, "No client made by a lost race")
    }

    @Test
    fun aCaThatDoesNotParseIsAnUnreachableClusterNotACrash() = runTest {
        val adapter = CloudKubernetesClusters(clusters) { throw IllegalArgumentException("It does not parse.") }
        val refused = assertFailsWith<RuntimeUnreachableException> { adapter.use(ID.toString()) {} }
        assertEquals("The cluster's CA does not parse: an administrator enters it again.", refused.message)
        assertEquals(0, adapter.clients())
    }

    @Test
    fun anOperationThatFailsStillReleasesItsClient() = runTest {
        val clients = mutableListOf<HttpClient>()
        val adapter = CloudKubernetesClusters(clusters) { HttpClient(MockEngine { respondOk() }).also(clients::add) }
        assertFailsWith<IllegalStateException> { adapter.use(ID.toString()) { error("boom") } }
        clusters.version = 2
        adapter.use(ID.toString()) {}
        assertFalse(clients[0].isActive, "Released by the failed operation, so closed when replaced")
    }

    @Test
    fun theCatalogReadsTheProfilesAndOnlyReachableClusters() = runTest {
        val spec = CloudProfileSpec(
            clusterId = ID.toString(),
            namespace = "sessions",
            image = "ghcr.io/acme/web:1",
            allowedImageOverrides = listOf("ghcr.io/acme/*"),
            imagePullSecrets = listOf("ghcr"),
            resources = CloudResources(1500, 3072),
            workspaceSizeGibibytes = 30,
            storageClass = "fast",
        )
        val profile = CloudProfile(Uuid.random(), ORG_A, "web", 4, spec, setOf("alice"), AT, AT)
        val repository = object : CloudProfilesRepository {
            override suspend fun list(orgId: Uuid) = listOf(profile).filter { it.orgId == orgId }

            override suspend fun get(orgId: Uuid, id: Uuid) = profile.takeIf { it.orgId == orgId && it.id == id }

            override suspend fun publish(profile: CloudProfile) = error("unused")
        }
        val catalog = CloudProfileCatalogImpl(repository, clusters)
        val option = catalog.profiles(ORG_A).single()
        assertEquals(SessionResources(1500, 3072), option.resources)
        assertEquals(listOf(4, 30), listOf(option.version, option.workspaceGibibytes))
        assertEquals(option, catalog.profile(ORG_A, profile.id))
        assertNull(catalog.profile(ORG_A, Uuid.random()))
        assertEquals(ProfileCluster(listOf("ostrom", "sessions"), "standard"), catalog.cluster(ID.toString()))
        assertNull(catalog.cluster("bad"))
        clusters.reachable = false
        assertNull(catalog.cluster(ID.toString()))
    }

    @Test
    fun theFormerVariablesClusterIsImportedAtStartAndGivenToTheSessionsWithoutAProfile() = runTest {
        val logger = RecordingLogger()
        val runtime = Runtime("ghcr.io/ostrom-hq/ostrom-runner:7")
        val environment = mapOf(
            "OSTROM_RUNNERS_DRIVER" to "kubernetes",
            "OSTROM_RUNNERS_SERVER_URL" to "http://ostrom.svc",
            "OSTROM_RUNNERS_WORKSPACE_SIZE" to "20Gi",
            "OSTROM_RUNNERS_KUBERNETES_IMAGE_PULL_SECRET" to " ghcr ",
        )
        val import = LegacyKubernetesImport(clusters, runtime, "f", logger, environment::get)
        import.start()
        assertEquals(1, clusters.imports, "Imported at start when no cluster exists")
        assertEquals(1, logger.events.size, "The profile fields")
        assertEquals(
            SessionProfile(
                clusterId = ID.toString(),
                namespace = "ostrom",
                image = "ghcr.io/ostrom-hq/ostrom-runner:7",
                imagePullSecrets = listOf("ghcr"),
                workspaceGibibytes = 20,
                storageClass = "standard",
            ),
            import.profile(),
        )
        val named = mapOf("OSTROM_RUNNERS_KUBERNETES_NAMESPACE" to " sessions ")
        val moved = LegacyKubernetesImport(clusters, runtime, "f", logger, named::get).profile()
        assertEquals("sessions", moved?.namespace, "The former namespace, whatever the cluster allows first")
        clusters.imported = false
        assertNull(import.profile(), "No cluster of the former variables: no profile")
        val quiet = RecordingLogger()
        LegacyKubernetesImport(clusters, runtime, "f", quiet, mapOf("OSTROM_RUNNERS_DRIVER" to "kubernetes")::get)
            .start()
        assertEquals(0, quiet.events.size, "Nothing ignored, nothing said")
    }

    @Test
    fun theFormerWorkspaceSizeIsReadInGibibytes() = runTest {
        for ((raw, expected) in listOf(null to 10, "1Ti" to 1024, "500Mi" to 10, "99999999999Gi" to 10)) {
            val runtime = Runtime(if (raw == null) " " else null) // A blank image is none.
            val environment = mapOf("OSTROM_RUNNERS_KUBERNETES_IMAGE_PULL_SECRET" to " ") +
                listOfNotNull(raw?.let { "OSTROM_RUNNERS_WORKSPACE_SIZE" to it })
            val profile = LegacyKubernetesImport(clusters, runtime, "ghcr.io/x/runner:1", RecordingLogger()) {
                environment[it]
            }.profile()
            assertEquals(expected, profile?.workspaceGibibytes, "$raw")
            assertEquals("ghcr.io/x/runner:1", profile?.image)
            assertEquals(emptyList(), profile?.imagePullSecrets, "A blank one is none")
        }
    }

    private class Runtime(private val image: String?) : CloudRuntimeSource {
        override suspend fun current(): CloudRuntime = CloudRuntime(runnerImage = image)
    }

    private class RecordingLogger : Logger {
        val events = mutableListOf<Triple<LogLevel, String, Map<String, Any?>>>()

        override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
            events += Triple(level, message, fields)
        }

        override fun with(fields: Map<String, Any?>): Logger = this
    }

    private class FakeClusters : CloudClustersUseCase {
        var token = "token-1"
        var ca = "CA-1"
        var version = 1
        var reachable = true
        var imported = true
        var imports = 0

        private fun cluster() = CloudCluster(
            ID, "imported", "https://k8s.acme.dev", listOf("ostrom", "sessions"), 10, "standard", version,
            hasCaCertificate = true, hasServiceAccountToken = true, createdAt = AT, updatedAt = AT,
        )

        override suspend fun credentials(id: Uuid): CloudClusterCredentials? =
            CloudClusterCredentials(cluster(), ca, token).takeIf { id == ID && reachable }

        override suspend fun importLegacy(environment: (String) -> String?): CloudCluster? {
            imports++
            return null
        }

        override suspend fun legacyCluster(environment: (String) -> String?): CloudCluster? =
            cluster().copy(allowedNamespaces = listOf("ostrom")).takeIf { imported }

        override suspend fun list(caller: CloudAdminCaller): List<CloudCluster> = error("unused")

        override suspend fun create(caller: CloudAdminCaller, input: CloudClusterInput): CloudCluster = error("unused")

        override suspend fun update(caller: CloudAdminCaller, id: Uuid, input: CloudClusterInput): CloudCluster =
            error("unused")
    }

    private companion object {
        val ID: Uuid = Uuid.parse("0192f6a1-7c3e-7d4a-9b2f-1a2b3c4d5e6f")
        val AT: Instant = Instant.parse("2026-11-14T08:00:00Z")
    }
}
