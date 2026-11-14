// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud

import run.ostrom.server.cloud.domain.CloudAdminCaller
import run.ostrom.server.cloud.domain.CloudAdminGate
import run.ostrom.server.cloud.domain.CloudAdminRequiredException
import run.ostrom.server.cloud.domain.CloudClusterConflictException
import run.ostrom.server.cloud.domain.CloudClusterInput
import run.ostrom.server.cloud.domain.CloudClusterNotFoundException
import run.ostrom.server.cloud.domain.CloudClustersUseCaseImpl
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
    )
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
}
