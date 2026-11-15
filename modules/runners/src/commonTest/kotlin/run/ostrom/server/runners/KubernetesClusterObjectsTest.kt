// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.cloud.domain.StorageClassLookup
import run.ostrom.server.runners.domain.ClaimObservation
import run.ostrom.server.runners.domain.ClusterReadForbiddenException
import run.ostrom.server.runners.domain.ManagedObjects
import run.ostrom.server.runners.domain.RunnerDelivery
import run.ostrom.server.runners.domain.RunnerDeliverySource
import run.ostrom.server.runners.domain.RuntimeUnreachableException
import run.ostrom.server.runners.domain.SessionProfile
import run.ostrom.server.runners.domain.StorageClassObservation
import run.ostrom.server.runners.domain.VolumeObservation
import run.ostrom.server.runners.fakes.FakeApi
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.infrastructure.ClusterWorkspaceStorageClasses
import run.ostrom.server.runners.infrastructure.KubernetesApiException
import run.ostrom.server.runners.infrastructure.KubernetesClusters
import run.ostrom.server.runners.infrastructure.KubernetesRuntimeDriver
import run.ostrom.server.runners.infrastructure.KubernetesSettings
import run.ostrom.server.runners.infrastructure.KubernetesTarget
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The Kubernetes driver's cluster-wide reads (TeamCode's `KubernetesClusterPortTest`): storage classes, the claim's
 * volume, PersistentVolumes, the objects labelled with the cluster, and the claim's access mode and cluster label.
 */
class KubernetesClusterObjectsTest {
    private val api = FakeApi()
    private val clusters = object : KubernetesClusters {
        override suspend fun <T> use(clusterId: String, block: suspend (KubernetesTarget) -> T): T {
            if (clusterId != CLUSTER) throw RuntimeUnreachableException("gone")
            return block(KubernetesTarget(api.client, "https://k8s/", "sa", 1))
        }
    }
    private var accessMode = "ReadWriteOncePod"
    private val delivery = RunnerDeliverySource {
        RunnerDelivery("runner:1", RunnerDelivery.DEFAULT_RUNNER_BINARY, "unused:1", "http://s", accessMode)
    }
    private val driver = KubernetesRuntimeDriver(clusters, KubernetesSettings(), delivery)
    private val profile =
        SessionProfile(
            clusterId = CLUSTER,
            namespace = "ostrom",
            image = "img:1",
            workspaceGibibytes = 5,
            storageClass = "fast",
        )
    private val session = session().copy(epoch = 2, profile = profile)
    private val hex = session.id.toHexString()
    private val classes = "/apis/storage.k8s.io/v1/storageclasses"

    @Test
    fun aClaimTakesTheRuntimesAccessModeAndTheClusterLabel() = runTest {
        driver.ensureWorkspace(session)
        accessMode = "ReadWriteOnce"
        driver.ensureWorkspace(session)

        fun claim(index: Int) = Json.parseToJsonElement(api.calls[index].body!!).jsonObject
        val modes = { index: Int ->
            claim(index)["spec"]!!.jsonObject["accessModes"]!!.jsonArray.single().jsonPrimitive.content
        }
        assertEquals("ReadWriteOncePod", modes(0))
        assertEquals("ReadWriteOnce", modes(1))
        val labels = claim(0)["metadata"]!!.jsonObject["labels"]!!.jsonObject
        assertEquals(CLUSTER, labels[KubernetesRuntimeDriver.CLUSTER_LABEL]!!.jsonPrimitive.content)
        assertTrue(driver.provesDeletion)
    }

    @Test
    fun aNamedStorageClassIsReadAndTheDefaultFoundByEitherAnnotation() = runTest {
        api.on("GET", "$classes/fast", HttpStatusCode.OK to """{"metadata":{"name":"fast"},"reclaimPolicy":"Retain"}""")
        api.on("GET", "$classes/gone", HttpStatusCode.NotFound to "{}")
        api.on(
            "GET",
            classes,
            HttpStatusCode.OK to """{"metadata":{"continue":"next"},"items":[{"metadata":{"name":"slow"}}]}""",
            HttpStatusCode.OK to """{"metadata":{},"items":[{"metadata":{"name":"std","annotations":
                {"storageclass.beta.kubernetes.io/is-default-class":"true"}}}]}""",
        )

        assertEquals(StorageClassObservation("fast", "Retain"), driver.storageClass(session))
        assertNull(driver.storageClass(CLUSTER, "gone"))
        assertEquals(StorageClassObservation("std", "Delete"), driver.storageClass(CLUSTER, null))
        assertEquals("next", api.calls.last().query["continue"], "The second page follows the first")
    }

    @Test
    fun aClusterWithoutDefaultClassHasNoneAndItsListingStopsAfterTwentyPages() = runTest {
        api.on(
            "GET",
            classes,
            HttpStatusCode.OK to """{"metadata":{"continue":""}}""",
            HttpStatusCode.OK to """{"metadata":{"continue":"more"},"items":["odd",{"metadata":{"name":"slow"}}]}""",
        )

        assertNull(driver.storageClass(CLUSTER, null), "A last page without items, and no default class")
        assertEquals(1, api.calls.size)
        api.calls.clear()
        assertNull(driver.storageClass(CLUSTER, null))
        assertEquals(20, api.calls.size, "A listing that never ends is cut")
        val unnamed = KubernetesRuntimeDriver.storageClass(Json.parseToJsonElement("{}").jsonObject)
        assertEquals(StorageClassObservation("", "Delete"), unnamed, "No policy is the API's default, Delete")
    }

    @Test
    fun theStorageClassesOfAProfileAreUnknownWhenTheyCannotBeRead() = runTest {
        val storage = ClusterWorkspaceStorageClasses(driver)
        api.on("GET", "$classes/fast", HttpStatusCode.OK to """{"metadata":{"name":"fast"}}""")
        api.on("GET", "$classes/gone", HttpStatusCode.NotFound to "{}")
        api.on("GET", "$classes/hidden", HttpStatusCode.Forbidden to "{}")
        val id = Uuid.parse(CLUSTER)

        assertEquals(StorageClassLookup.Found("fast", "Delete"), storage.find(id, "fast"))
        assertEquals(StorageClassLookup.Missing, storage.find(id, "gone"))
        assertEquals(StorageClassLookup.Unknown, storage.find(id, "hidden"), "A role without storage classes")
        assertEquals(StorageClassLookup.Unknown, storage.find(Uuid.random(), "fast"), "A cluster that does not open")
        assertEquals(StorageClassLookup.Unknown, ClusterWorkspaceStorageClasses(null).find(id, "fast"))
    }

    @Test
    fun theClaimNamesItsVolumeWhoseHandleAndPolicyAreRead() = runTest {
        val claim = "/api/v1/namespaces/ostrom/persistentvolumeclaims/ostrom-ws-$hex"
        api.on("GET", claim, HttpStatusCode.OK to """{"spec":{"volumeName":"pv-1"}}""", HttpStatusCode.NotFound to "{}")
        api.on(
            "GET",
            "/api/v1/persistentvolumes/pv-1",
            HttpStatusCode.OK to """{"metadata":{"name":"pv-1"},"status":{"phase":"Released"},"spec":{
                "persistentVolumeReclaimPolicy":"Delete","csi":{"volumeHandle":"vol-1"},
                "claimRef":{"namespace":"ostrom","name":"ostrom-ws-$hex"}}}""",
            HttpStatusCode.NotFound to "{}",
        )

        assertEquals(ClaimObservation("pv-1"), driver.workspaceClaim(session))
        assertNull(driver.workspaceClaim(session))
        assertEquals(
            VolumeObservation("pv-1", "Released", "Delete", "vol-1", "ostrom", "ostrom-ws-$hex"),
            driver.volume(session, "pv-1"),
        )
        assertNull(driver.volume(session, "pv-1"))
        assertNull(driver.workspaceClaim(session.copy(profile = null)))
        assertNull(driver.volume(session.copy(profile = null), "pv-1"))
        assertNull(driver.storageClass(session.copy(profile = null)))
    }

    @Test
    fun theVolumeOfAClaimIsFoundByItsClaimReferenceAndARefusedReadSaysSo() = runTest {
        val volumes = "/api/v1/persistentvolumes"
        api.on(
            "GET",
            volumes,
            HttpStatusCode.OK to """{"items":[
                {"metadata":{"name":"pv-other"},"spec":{"claimRef":{"namespace":"ostrom","name":"ostrom-ws-other"}}},
                {"metadata":{"name":"pv-team"},"spec":{"claimRef":{"namespace":"team","name":"ostrom-ws-$hex"}}},
                {"metadata":{"name":"pv-9"},"status":{"phase":"Bound"},"spec":{"persistentVolumeReclaimPolicy":"Delete",
                    "csi":{"volumeHandle":"vol-9"},"claimRef":{"namespace":"ostrom","name":"ostrom-ws-$hex"}}}]}""",
            HttpStatusCode.OK to """{"items":[]}""",
            HttpStatusCode.Forbidden to "{}",
        )

        assertEquals(
            VolumeObservation("pv-9", "Bound", "Delete", "vol-9", "ostrom", "ostrom-ws-$hex"),
            driver.claimedVolume(session),
        )
        assertNull(driver.claimedVolume(session))
        assertFailsWith<ClusterReadForbiddenException> { driver.claimedVolume(session) }
        api.on("GET", "$volumes/pv-9", HttpStatusCode.Forbidden to "{}")
        assertFailsWith<ClusterReadForbiddenException> { driver.volume(session, "pv-9") }
        assertNull(driver.claimedVolume(session.copy(profile = null)))
    }

    @Test
    fun aClaimReferenceBeyondTheListingsLastPageCannotBeReadRatherThanAbsent() = runTest {
        val endless = """{"metadata":{"continue":"more"},"items":[]}"""
        api.on("GET", "/api/v1/persistentvolumes", HttpStatusCode.OK to endless)

        assertFailsWith<ClusterReadForbiddenException> { driver.claimedVolume(session) }
        assertEquals(200, api.calls.size, "Every page the driver reads")
    }

    @Test
    fun aNamespacedReadTheRoleRefusesStaysAnApiFailure() = runTest {
        val claim = "/api/v1/namespaces/ostrom/persistentvolumeclaims/ostrom-ws-$hex"
        api.on("GET", claim, HttpStatusCode.Forbidden to "{}")
        api.on("GET", "/api/v1/namespaces/ostrom/pods", HttpStatusCode.Forbidden to "{}")

        assertFailsWith<KubernetesApiException> { driver.workspaceClaim(session) }
        assertFailsWith<KubernetesApiException> { driver.managed(CLUSTER, "ostrom") }
    }

    @Test
    fun aVolumeWithoutPolicyIsReadAsRetained() {
        assertEquals(
            VolumeObservation("", "Pending", "Retain"),
            KubernetesRuntimeDriver.volume(Json.parseToJsonElement("{}").jsonObject),
        )
    }

    @Test
    fun theManagedObjectsAreTheClustersLabelledOnesAndOrphanedVolumesTheWorkspacesReleased() = runTest {
        api.on(
            "GET",
            "/api/v1/namespaces/ostrom/pods",
            HttpStatusCode.OK to """{"items":[{"metadata":{"name":"p1"}}]}""",
        )
        api.on(
            "GET",
            "/api/v1/namespaces/ostrom/persistentvolumeclaims",
            HttpStatusCode.OK to """{"items":[{"metadata":{"name":"c1"}}]}""",
        )
        api.on(
            "GET",
            "/api/v1/persistentvolumes",
            HttpStatusCode.OK to """{"items":[
                {"metadata":{"name":"a"},"status":{"phase":"Released"},"spec":{"claimRef":{"name":"ostrom-ws-1"}}},
                {"metadata":{"name":"b"},"status":{"phase":"Failed"},"spec":{"claimRef":{"name":"ostrom-ws-2"}}},
                {"metadata":{"name":"c"},"status":{"phase":"Bound"},"spec":{"claimRef":{"name":"ostrom-ws-3"}}},
                {"metadata":{"name":"d"},"status":{"phase":"Released"},"spec":{"claimRef":{"name":"data"}}},
                {"metadata":{"name":"e"},"status":{"phase":"Released"},"spec":{}}]}""",
        )

        assertEquals(ManagedObjects(listOf("p1"), listOf("c1")), driver.managed(CLUSTER, "ostrom"))
        assertEquals(
            "app.kubernetes.io/managed-by=ostrom,ostrom.run/cluster=$CLUSTER",
            api.calls.first().query["labelSelector"],
        )
        assertEquals(listOf("a", "b"), driver.orphanedVolumes(CLUSTER).map { it.name })

        driver.deletePod(CLUSTER, "ostrom", "p1")
        api.on("DELETE", "/api/v1/namespaces/ostrom/persistentvolumeclaims/c1", HttpStatusCode.NotFound to "{}")
        driver.deleteClaim(CLUSTER, "ostrom", "c1")
        assertEquals("0", api.calls[api.calls.size - 2].query["gracePeriodSeconds"])
        assertEquals("DELETE /api/v1/namespaces/ostrom/persistentvolumeclaims/c1", api.lines().last())
    }

    private companion object {
        const val CLUSTER = "0192f6a1-7c3e-7d4a-9b2f-1a2b3c4d5e6f"
    }
}
