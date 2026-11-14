// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud

import run.ostrom.server.cloud.domain.CloudAdminCaller
import run.ostrom.server.cloud.domain.CloudAdminGate
import run.ostrom.server.cloud.domain.CloudAdminRequiredException
import run.ostrom.server.cloud.domain.CloudClusterConflictException
import run.ostrom.server.cloud.domain.CloudClusterInput
import run.ostrom.server.cloud.domain.CloudClusterNotFoundException
import run.ostrom.server.cloud.domain.CloudClustersRepository
import run.ostrom.server.cloud.domain.CloudClustersUseCaseImpl
import run.ostrom.server.cloud.domain.IN_CLUSTER_CA_FILE
import run.ostrom.server.cloud.domain.IN_CLUSTER_TOKEN_FILE
import run.ostrom.server.cloud.domain.StoredCloudCluster
import run.ostrom.server.cloud.domain.InvalidCloudClusterException
import run.ostrom.server.kernel.settings.InstanceAdministrators
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class CloudClustersTest {
    /** The files the use case reads, by path: the pod's service account, or a former variable's file. */
    private val files = mutableMapOf<String, String>()
    private val repository = MemoryClusters()
    private val audit = RecordingAudit()
    private val logger = RecordingLogger()
    private val cipher = AesGcmSecretCipher(ByteArray(32) { 7 })
    private val clusters = CloudClustersUseCaseImpl(
        repository,
        CloudAdminGate(InstanceAdministrators { it == ALICE }),
        cipher,
        audit,
        SettableClock(),
        logger,
    ) { files[it] }
    private val alice = CloudAdminCaller(ALICE, person = true)
    private val input = CloudClusterInput(
        name = " prod ",
        apiUrl = "https://k8s.acme.dev:6443/",
        caCertificate = PEM,
        serviceAccountToken = "eyJ.secret.token",
        allowedNamespaces = listOf("sessions", " team-a "),
        maxRunningPods = 50,
        defaultStorageClass = " ",
        version = 0,
    )

    @Test
    fun anAdministratorConnectsAClusterWhoseCredentialsAreSealedAndNeverShown() = runTest {
        assertFailsWith<CloudAdminRequiredException> { clusters.list(CloudAdminCaller(BOB, true)) }
        assertFailsWith<CloudAdminRequiredException> { clusters.create(CloudAdminCaller(ALICE, false), input) }

        val cluster = clusters.create(alice, input)

        assertEquals("prod", cluster.name)
        assertEquals("https://k8s.acme.dev:6443", cluster.apiUrl)
        assertEquals(listOf("sessions", "team-a"), cluster.allowedNamespaces)
        assertNull(cluster.defaultStorageClass)
        assertEquals(1, cluster.version)
        assertTrue(cluster.hasCaCertificate && cluster.hasServiceAccountToken)
        val stored = repository.rows.getValue(cluster.id)
        assertFalse("secret" in stored.sealedServiceAccountToken)
        assertFalse("secret" in stored.toString() + input.toString())
        val credentials = clusters.credentials(cluster.id)
        assertEquals("eyJ.secret.token", credentials?.serviceAccountToken)
        assertEquals(PEM, credentials?.caCertificate)
        assertFalse("secret" in credentials.toString())
        assertEquals(listOf(cluster), clusters.list(alice))
        assertEquals(mapOf("name" to "prod", "version" to "1", "credentials" to "replaced"), audit.records[0].details)
        assertFailsWith<CloudClusterConflictException> { clusters.create(alice, input) }
    }

    @Test
    fun aMetadataOrLinkLocalAddressIsRefusedInEveryNumericSpelling() = runTest {
        for (url in listOf(
            "https://169.254.169.254",
            "https://2852039166",
            "https://0xA9FEA9FE",
            "https://0251.0376.0251.0376",
            "https://169.254.43518",
            "https://[::ffff:169.254.169.254]",
            "https://metadata.google.internal",
            "http://k8s.acme.dev",
        )) {
            val refused = assertFailsWith<InvalidCloudClusterException> {
                clusters.create(alice, input.copy(apiUrl = url))
            }
            assertTrue("apiUrl" in refused.message.orEmpty(), url)
        }
    }

    @Test
    fun everyOtherValueIsCheckedAndNamed() = runTest {
        suspend fun refused(value: CloudClusterInput) =
            assertFailsWith<InvalidCloudClusterException> { clusters.create(alice, value) }.message.orEmpty()

        assertTrue("name" in refused(input.copy(name = " ")))
        assertTrue("name" in refused(input.copy(name = "a".repeat(101))))
        assertTrue("caCertificate is a PEM" in refused(input.copy(caCertificate = "not a pem")))
        assertTrue("caCertificate is a PEM" in refused(input.copy(caCertificate = PEM + "a".repeat(65_536))))
        assertTrue("serviceAccountToken is a bearer" in refused(input.copy(serviceAccountToken = "has space")))
        assertTrue("needed for a new apiUrl" in refused(input.copy(caCertificate = null)))
        assertTrue("needed for a new apiUrl" in refused(input.copy(serviceAccountToken = " ")))
        assertTrue("allowedNamespaces" in refused(input.copy(allowedNamespaces = emptyList())))
        assertTrue("allowedNamespaces" in refused(input.copy(allowedNamespaces = listOf("a", "a"))))
        assertTrue("allowedNamespaces" in refused(input.copy(allowedNamespaces = listOf("Not_A_Label"))))
        assertTrue("maxRunningPods" in refused(input.copy(maxRunningPods = 0)))
        assertTrue("maxRunningPods" in refused(input.copy(maxRunningPods = 100_001)))
        assertTrue("allowedNamespaces" in refused(input.copy(allowedNamespaces = (1..101).map { "n$it" })))
        assertTrue("defaultStorageClass" in refused(input.copy(defaultStorageClass = "Bad Class")))
        assertTrue("version" in refused(input.copy(version = 3)))
        assertEquals("fast", clusters.create(alice, input.copy(defaultStorageClass = "fast")).defaultStorageClass)
    }

    @Test
    fun aChangeKeepsTheCredentialsUnlessTheServerChangesThenNeedsBoth() = runTest {
        val cluster = clusters.create(alice, input)
        val kept = input.copy(caCertificate = null, serviceAccountToken = null, version = 1, maxRunningPods = 10)

        assertFailsWith<CloudClusterNotFoundException> { clusters.update(alice, Uuid.random(), kept) }
        assertFailsWith<CloudAdminRequiredException> { clusters.update(CloudAdminCaller(BOB, true), cluster.id, kept) }
        assertFailsWith<CloudClusterConflictException> { clusters.update(alice, cluster.id, kept.copy(version = 0)) }
        val changed = clusters.update(alice, cluster.id, kept)
        assertEquals(2, changed.version)
        assertEquals(10, changed.maxRunningPods)
        assertEquals("eyJ.secret.token", clusters.credentials(cluster.id)?.serviceAccountToken)
        assertEquals("kept", audit.records.last().details["credentials"])

        val moved = kept.copy(apiUrl = "https://other.acme.dev", version = 2)
        val refused = assertFailsWith<InvalidCloudClusterException> { clusters.update(alice, cluster.id, moved) }
        assertTrue("needed for a new apiUrl" in refused.message.orEmpty())
        val half = assertFailsWith<InvalidCloudClusterException> {
            clusters.update(alice, cluster.id, moved.copy(caCertificate = PEM))
        }
        assertTrue("needed for a new apiUrl" in half.message.orEmpty())
        val renewed = clusters.update(alice, cluster.id, moved.copy(caCertificate = PEM, serviceAccountToken = "new"))
        assertEquals("https://other.acme.dev", renewed.apiUrl)
        assertEquals("new", clusters.credentials(cluster.id)?.serviceAccountToken)
        assertEquals("replaced", audit.records.last().details["credentials"])
        // A token alone, on the same server, replaces only the token.
        clusters.update(alice, cluster.id, moved.copy(version = 3, serviceAccountToken = "newer"))
        assertEquals("newer", clusters.credentials(cluster.id)?.serviceAccountToken)

        val other = clusters.create(alice, input.copy(name = "staging"))
        val taken = kept.copy(name = "prod", version = 1)
        assertFailsWith<CloudClusterConflictException> { clusters.update(alice, other.id, taken) }
    }

    @Test
    fun credentialsThatNoLongerOpenAreNone() = runTest {
        val cluster = clusters.create(alice, input)
        val stored = repository.rows.getValue(cluster.id)

        assertNull(clusters.credentials(Uuid.random()))
        repository.rows[cluster.id] = stored.copy(sealedCaCertificate = "v1:AAAA")
        assertNull(clusters.credentials(cluster.id))
        repository.rows[cluster.id] = stored.copy(sealedServiceAccountToken = "v1:AAAA")
        assertNull(clusters.credentials(cluster.id))
        assertEquals(listOf(cluster.id.toString(), cluster.id.toString()), logger.events.map { it.third["cluster"] })
    }

    private fun legacy(vararg values: Pair<String, String>): (String) -> String? =
        (mapOf("OSTROM_RUNNERS_DRIVER" to "kubernetes") + values)::get

    @Test
    fun thePodsOwnServiceAccountIsImportedAsAnInClusterClusterReadAtEachUse() = runTest {
        val cluster = clusters.importLegacy(legacy("OSTROM_RUNNERS_KUBERNETES_STORAGE_CLASS" to "fast"))!!

        assertEquals("imported", cluster.name)
        assertEquals("https://kubernetes.default.svc", cluster.apiUrl)
        assertEquals(listOf("ostrom"), cluster.allowedNamespaces)
        assertEquals("fast", cluster.defaultStorageClass)
        assertTrue(cluster.inCluster)
        assertFalse(cluster.hasCaCertificate || cluster.hasServiceAccountToken)
        val stored = repository.get(cluster.id)!!
        assertEquals("" to "", stored.sealedCaCertificate to stored.sealedServiceAccountToken, "Nothing stored")
        assertEquals("cloud.cluster.imported", audit.records.single().event)
        assertEquals("in_cluster", audit.records.single().details["credentials"])

        assertNull(clusters.credentials(cluster.id), "The pod's files are not readable yet")
        files[IN_CLUSTER_CA_FILE] = PEM
        assertNull(clusters.credentials(cluster.id), "No token yet")
        files[IN_CLUSTER_TOKEN_FILE] = "token-0"
        files[IN_CLUSTER_CA_FILE] = " "
        assertNull(clusters.credentials(cluster.id), "A blank CA")
        files[IN_CLUSTER_CA_FILE] = PEM
        files[IN_CLUSTER_TOKEN_FILE] = "token-1\n"
        assertEquals("token-1", clusters.credentials(cluster.id)?.serviceAccountToken)
        files[IN_CLUSTER_TOKEN_FILE] = "token-2"
        assertEquals("token-2", clusters.credentials(cluster.id)?.serviceAccountToken, "The rotated token is used")
        assertEquals(PEM, clusters.credentials(cluster.id)?.caCertificate)

        assertNull(clusters.importLegacy(legacy("OSTROM_RUNNERS_KUBERNETES_NAMESPACE" to "ostrom")), "Once")
        assertTrue(logger.events.last().second.startsWith("Ignored deprecated variables"))
    }

    @Test
    fun explicitFilesAreReadOnceAndSealed() = runTest {
        files["/etc/ca"] = PEM
        files["/etc/token"] = " long-lived "
        val cluster = clusters.importLegacy(
            legacy(
                "OSTROM_RUNNERS_KUBERNETES_API_URL" to "https://k8s.acme.dev:6443",
                "OSTROM_RUNNERS_KUBERNETES_NAMESPACE" to "sessions",
                "OSTROM_RUNNERS_KUBERNETES_CA_FILE" to "/etc/ca",
                "OSTROM_RUNNERS_KUBERNETES_TOKEN_FILE" to "/etc/token",
            ),
        )!!
        assertFalse(cluster.inCluster)
        assertTrue(cluster.hasCaCertificate && cluster.hasServiceAccountToken)
        files.clear()
        assertEquals("long-lived", clusters.credentials(cluster.id)?.serviceAccountToken)
        assertEquals("stored", audit.records.single().details["credentials"])
    }

    @Test
    fun unreadableFilesImportAClusterWhoseCredentialsAnAdministratorEnters() = runTest {
        val cluster = clusters.importLegacy(legacy("OSTROM_RUNNERS_KUBERNETES_TOKEN_FILE" to "/missing"))!!
        assertFalse(cluster.inCluster || cluster.hasServiceAccountToken)
        assertNull(clusters.credentials(cluster.id))
        assertTrue(logger.events.last().second.contains("an administrator enters"))
        val caOnly = input.copy(name = "imported", apiUrl = cluster.apiUrl, version = 1, serviceAccountToken = null)
        assertTrue(clusters.update(alice, cluster.id, caOnly).hasCaCertificate)
        assertNull(clusters.credentials(cluster.id), "Its token is still to be entered")
        val both = input.copy(name = "imported", apiUrl = cluster.apiUrl, version = 2)
        val entered = clusters.update(alice, cluster.id, both)
        assertTrue(entered.hasServiceAccountToken)
        assertEquals("eyJ.secret.token", clusters.credentials(cluster.id)?.serviceAccountToken)
    }

    @Test
    fun aReadableCaWithoutItsTokenStoresNeither() = runTest {
        files["/etc/ca"] = PEM
        val cluster = clusters.importLegacy(
            legacy("OSTROM_RUNNERS_KUBERNETES_CA_FILE" to "/etc/ca", "OSTROM_RUNNERS_KUBERNETES_TOKEN_FILE" to "/none"),
        )!!
        assertFalse(cluster.hasCaCertificate || cluster.inCluster)
        assertEquals("missing", audit.records.single().details["credentials"])
    }

    @Test
    fun savingACaAndATokenTurnsAnInClusterClusterIntoAStoredOne() = runTest {
        val cluster = clusters.importLegacy(legacy())!!
        val same = input.copy(
            name = "imported",
            apiUrl = cluster.apiUrl,
            version = 1,
            allowedNamespaces = listOf("ostrom"),
        )
        val kept = clusters.update(alice, cluster.id, same.copy(caCertificate = null, serviceAccountToken = null))
        assertTrue(kept.inCluster, "Saved without credentials, it still reads the pod's")
        val next = same.copy(version = 2)
        val halves = listOf(next.copy(serviceAccountToken = null), next.copy(caCertificate = null))
        for (half in halves) {
            assertEquals(
                "caCertificate and serviceAccountToken are given together for an in-cluster cluster.",
                assertFailsWith<InvalidCloudClusterException> { clusters.update(alice, cluster.id, half) }.message,
            )
        }
        val stored = clusters.update(alice, cluster.id, same.copy(version = 2))
        assertFalse(stored.inCluster)
        assertTrue(stored.hasCaCertificate && stored.hasServiceAccountToken)
        assertEquals("eyJ.secret.token", clusters.credentials(cluster.id)?.serviceAccountToken)
    }

    @Test
    fun nothingIsImportedWithoutTheKubernetesDriverOrWithAnInvalidValue() = runTest {
        assertNull(clusters.importLegacy { null })
        assertTrue(logger.events.isEmpty(), "Nothing set, nothing said")
        assertNull(clusters.importLegacy(mapOf("OSTROM_RUNNERS_KUBERNETES_NAMESPACE" to "x")::get))
        assertTrue(logger.events.single().second.startsWith("Ignored deprecated variables"))
        assertNull(clusters.importLegacy(legacy("OSTROM_RUNNERS_KUBERNETES_API_URL" to "http://k8s")))
        assertTrue(logger.events.last().second.startsWith("Did not import"))
        assertTrue(repository.list().isEmpty())
        clusters.create(alice, input)
        assertNull(clusters.importLegacy(legacy()), "A cluster exists: nothing imported")
    }

    @Test
    fun aClusterImportedByAnotherReplicaFirstIsNotImportedAgain() = runTest {
        val racing = object : CloudClustersRepository by repository {
            override suspend fun insert(stored: StoredCloudCluster): Boolean = false
        }
        val other = CloudClustersUseCaseImpl(
            racing, CloudAdminGate(InstanceAdministrators { false }), cipher, audit, SettableClock(), logger,
        ) { null }
        assertNull(other.importLegacy(legacy()))
        assertTrue(audit.records.isEmpty())
        assertNull(other.legacyCluster(legacy()), "Lost, and no marked cluster to read again")
    }

    @Test
    fun theFormerVariablesClusterIsImportedForTheirSessionsWhateverClustersExist() = runTest {
        assertNull(clusters.legacyCluster { null }, "Not Kubernetes: none")
        // An administrator's cluster made before the upgrade, named as the import would be.
        clusters.create(alice, input.copy(name = "imported"))
        val imported = clusters.legacyCluster(legacy("OSTROM_RUNNERS_KUBERNETES_NAMESPACE" to "sessions"))!!
        assertTrue(imported.imported && imported.inCluster)
        assertEquals(listOf("sessions"), imported.allowedNamespaces)
        assertEquals("imported-legacy", imported.name, "The administrator's name is kept")
        assertEquals(imported, clusters.legacyCluster(legacy()), "Imported once, found again at each ask")
        assertEquals(imported, clusters.legacyCluster { null }, "Found whatever the variables say now")
        assertFalse(repository.list().single { it.name == "imported" }.imported)
        assertEquals(1, audit.records.count { it.event == "cloud.cluster.imported" })
    }

    @Test
    fun aClusterImportedByAnotherReplicaIsFoundAfterTheLostRace() = runTest {
        val winner = clusters.importLegacy(legacy())!!
        var lists = 0
        val racing = object : CloudClustersRepository by repository {
            // The first read sees no cluster yet: the other replica's insert lands just after.
            override suspend fun list() = if (lists++ == 0) emptyList() else repository.list()

            override suspend fun insert(stored: StoredCloudCluster): Boolean = false
        }
        val other = CloudClustersUseCaseImpl(
            racing, CloudAdminGate(InstanceAdministrators { false }), cipher, audit, SettableClock(), logger,
        ) { null }
        assertEquals(winner, other.legacyCluster(legacy()))
    }
}
