// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.sessions.SessionEndListener
import run.ostrom.server.runners.domain.BackgroundWork
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CollectCloudGarbageUseCaseImpl
import run.ostrom.server.runners.domain.DeletionState
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.GarbageCluster
import run.ostrom.server.runners.domain.GarbageClusters
import run.ostrom.server.runners.domain.GarbageReport
import run.ostrom.server.runners.domain.LiveCloudObjectsRepository
import run.ostrom.server.runners.domain.LiveObjects
import run.ostrom.server.runners.domain.ReconcileCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.RepositoryCloudUsageMeter
import run.ostrom.server.runners.domain.RuntimeNames
import run.ostrom.server.runners.domain.RuntimeUnreachableException
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.domain.VolumeObservation
import run.ostrom.server.runners.domain.WorkspaceDeletion
import run.ostrom.server.runners.fakes.FAKE_CLUSTER
import run.ostrom.server.runners.fakes.FakeClusterRuntime
import run.ostrom.server.runners.fakes.FakeRunnerTokens
import run.ostrom.server.runners.fakes.FixedPlans
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.clusterProfile
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.presentation.toResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A Kubernetes session's workspace deletion is proven down to the provider's volume, a storage class that keeps volumes
 * is refused, and a garbage collection deletes what no session accounts for (`runners.cloud.retention.v1`, TeamCode's
 * `deletionIsProvenAndRetainedVolumesAlert` and `garbageCollection`).
 */
class CloudWorkspaceDeletionTest {
    private val repository = InMemoryCloudSessionsRepository()
    private val cluster = FakeClusterRuntime()
    private val clock = SettableClock()
    private val t0 = clock.now
    private val ended = mutableListOf<List<String>>()
    private val reconcile = ReconcileCloudSessionUseCaseImpl(
        repository,
        cluster,
        FakeRunnerTokens(),
        FixedPlans(),
        RepositoryCloudUsageMeter(repository),
        clock,
        listOf(SessionEndListener { _, ids -> ended += ids }),
        backgroundWork = { BackgroundWork.DEFAULT_MAX_MINUTES },
    )

    private fun put(session: CloudSession) = session.also { repository.put(it) }

    private fun stored(session: CloudSession) = repository.sessions.getValue(session.id)

    private suspend fun deleted(): CloudSession {
        val running = put(
            session(state = SessionState.RUNNING, desired = DesiredState.TERMINATED)
                .copy(epoch = 1, readyAt = t0, lastHeartbeatAt = t0, profile = clusterProfile()),
        )
        cluster.ensureWorkspace(running)
        return running
    }

    /** Steps the session until it asks for no new look, or for one later than [until] (a step's delay by default). */
    private suspend fun drain(session: CloudSession, until: Instant = clock.now + 2.seconds): Instant? {
        repeat(MAX_STEPS) {
            val next = reconcile(stored(session)) ?: return null
            if (next > until) return next
        }
        error("The session never settled")
    }

    @Test
    fun aDeletionIsProvenDownToTheProviderVolumeWhoseHandleIsKept() = runTest {
        val session = deleted()

        assertEquals(t0, reconcile(session), "Terminated first, its workspace deleted by the next steps")
        val terminated = stored(session)
        assertEquals(SessionState.TERMINATED, terminated.state)
        assertEquals("cleanup_pending", terminated.phase)
        assertEquals(DeletionState.PENDING, terminated.workspaceDeletion?.state)
        assertEquals(listOf(listOf(session.id.toString())), ended, "What its agent asked ends once it is terminated")

        assertNull(drain(session))
        val proven = assertNotNull(stored(session).workspaceDeletion)
        assertEquals(DeletionState.PROVEN, proven.state)
        assertEquals("pv-1", proven.volumeName)
        assertEquals("vol-1", proven.volumeHandle, "The provider volume identifier is kept as evidence")
        assertEquals(t0, proven.claimDeletedAt)
        assertEquals(t0, proven.volumeDeletedAt)
        assertEquals("cleaned", stored(session).phase)
        assertEquals(CloudSession.TERMINATED_DELETED, stored(session).terminatedReason)
        assertTrue(cluster.claims.isEmpty() && cluster.volumes.isEmpty(), "No provider volume remains")
        assertEquals("proven", stored(session).toResponse().workspaceDeletion?.state)
        assertEquals("vol-1", stored(session).toResponse().workspaceDeletion?.volumeHandle)
        assertNull(reconcile(stored(session)), "Nothing left to do")
    }

    @Test
    fun aClaimNeverBoundOrWhoseVolumeIsAlreadyGoneIsProvenWithoutAVolumeToWaitFor() = runTest {
        val unbound = deleted()
        cluster.claims["ostrom/${RuntimeNames.workspace(unbound.id)}"] = null to true
        cluster.volumes.clear()
        // A deletion stored as not started (an older row) starts.
        repository.put(stored(unbound).copy(workspaceDeletion = WorkspaceDeletion()))
        assertNull(drain(unbound))
        val proven = assertNotNull(stored(unbound).workspaceDeletion)
        assertEquals(DeletionState.PROVEN, proven.state)
        assertNull(proven.volumeName)
        assertNull(proven.volumeDeletedAt)

        val gone = deleted()
        cluster.claims["ostrom/${RuntimeNames.workspace(gone.id)}"] = "pv-gone" to true
        assertNull(drain(gone))
        val noHandle = assertNotNull(stored(gone).workspaceDeletion)
        assertEquals(DeletionState.PROVEN, noHandle.state)
        assertEquals("pv-gone", noHandle.volumeName)
        assertNull(noHandle.volumeHandle)
        assertEquals(t0, noHandle.volumeDeletedAt)
    }

    @Test
    fun aClaimHeldByItsFinalizerIsDeletedAgainAndALostRecordIsTriedAgain() = runTest {
        val held = deleted()
        reconcile(held)
        reconcile(stored(held))
        val recorded = stored(held)
        val volume = assertNotNull(recorded.workspaceDeletion?.volumeName)
        cluster.claims["ostrom/${RuntimeNames.workspace(held.id)}"] = volume to true

        assertEquals(t0 + 2.seconds, reconcile(recorded))
        assertEquals(recorded, stored(held), "Its volume and handle are recorded already")
        assertTrue(cluster.claims.isEmpty())

        // A volume recorded without its handle (its claim was read before it was bound) gets it.
        val late = deleted()
        reconcile(late)
        val boundTo = assertNotNull(cluster.claims["ostrom/${RuntimeNames.workspace(late.id)}"]?.first)
        val pending = stored(late)
        repository.put(pending.copy(workspaceDeletion = pending.workspaceDeletion?.copy(volumeName = boundTo)))
        reconcile(stored(late))
        assertEquals(boundTo, stored(late).workspaceDeletion?.volumeName)
        assertNotNull(stored(late).workspaceDeletion?.volumeHandle)

        val raced = deleted()
        reconcile(raced)
        repository.loseNextRaces = 1
        assertEquals(clock.now, reconcile(stored(raced)), "Another writer came first: looked at again")
        assertEquals(1, cluster.claims.size, "Its claim is not deleted before its volume is recorded")
    }

    @Test
    fun aSessionWithoutProfileOnTheClusterRuntimeIsTerminatedAsBefore() = runTest {
        val plain = put(
            session(state = SessionState.RUNNING, desired = DesiredState.TERMINATED)
                .copy(epoch = 1, readyAt = t0, lastHeartbeatAt = t0),
        )

        assertNull(reconcile(plain))
        assertEquals(SessionState.TERMINATED, stored(plain).state)
        assertEquals("cleaned", stored(plain).phase)
        assertNull(stored(plain).workspaceDeletion)
    }

    @Test
    fun anUnknownDeletionStateIsReadAsNotStarted() {
        assertEquals(DeletionState.NONE, DeletionState.fromWire("archived"))
        assertEquals(DeletionState.PROVEN, DeletionState.fromWire("proven"))
    }

    @Test
    fun aRetainedVolumeBlocksTheProofUntilAnAdministratorDeletesIt() = runTest {
        val session = deleted()
        cluster.volumes["pv-1"] = cluster.volumes.getValue("pv-1").copy(reclaimPolicy = "Retain")

        assertEquals(t0 + 10.minutes, drain(session))
        val blocked = assertNotNull(stored(session).workspaceDeletion)
        assertEquals(DeletionState.BLOCKED, blocked.state)
        assertEquals(WorkspaceDeletion.VOLUME_RETAINED, blocked.problem?.code)
        assertFalse(blocked.problem!!.retryable)
        assertEquals(false, stored(session).toResponse().workspaceDeletion?.problem?.retryable)
        assertEquals(t0 + 10.minutes, reconcile(stored(session)), "Checked again every 10 minutes")

        cluster.volumes.remove("pv-1")
        assertNull(drain(session))
        assertEquals(DeletionState.PROVEN, stored(session).workspaceDeletion?.state)
        assertNull(stored(session).workspaceDeletion?.problem)
    }

    @Test
    fun aVolumeTheProvisionerDoesNotDeleteStallsAfterAnHourOrWhenItFails() = runTest {
        cluster.provisionerDeletes = false
        val session = deleted()

        reconcile(session)
        assertEquals(t0 + 2.seconds, reconcile(stored(session)), "Its volume recorded, its claim deleted")
        assertEquals(t0 + 2.seconds, reconcile(stored(session)), "Its volume awaited")
        assertEquals("deleting_volume", stored(session).phase)
        assertEquals(DeletionState.PENDING, stored(session).workspaceDeletion?.state)

        clock.now = t0 + 1.hours + 1.seconds
        assertEquals(clock.now + 10.minutes, reconcile(stored(session)))
        val stalled = assertNotNull(stored(session).workspaceDeletion)
        assertEquals(WorkspaceDeletion.VOLUME_DELETION_STALLED, stalled.problem?.code)
        assertTrue(stalled.problem!!.retryable)

        cluster.provision()
        assertNull(reconcile(stored(session)))
        assertEquals(DeletionState.PROVEN, stored(session).workspaceDeletion?.state)

        val failed = deleted()
        cluster.provisionerDeletes = false
        reconcile(failed)
        reconcile(stored(failed))
        val volume = stored(failed).workspaceDeletion!!.volumeName!!
        cluster.volumes[volume] = cluster.volumes.getValue(volume).copy(phase = "Failed")
        drain(failed, until = clock.now + 1.minutes)
        assertEquals(WorkspaceDeletion.VOLUME_DELETION_STALLED, stored(failed).workspaceDeletion?.problem?.code)
    }

    @Test
    fun aCleanupWhoseClusterDoesNotOpenIsTriedAgainEveryTenMinutes() = runTest {
        val session = deleted()
        reconcile(session)
        cluster.unreachable = true

        assertEquals(t0 + 10.minutes, reconcile(stored(session)))
        assertEquals(DeletionState.PENDING, stored(session).workspaceDeletion?.state)
    }

    @Test
    fun aFirstStartRefusesAStorageClassThatIsGoneOrKeepsVolumes() = runTest {
        cluster.storageClasses["keep"] = "Retain"
        val retaining = put(session().copy(profile = clusterProfile("keep")))
        val missing = put(session().copy(profile = clusterProfile("gone")))

        reconcile(retaining)
        reconcile(missing)

        assertEquals(SessionState.FAILED, stored(retaining).state)
        assertEquals("storage_class_retains_volumes", stored(retaining).failure?.code)
        assertEquals(SessionState.FAILED, stored(missing).state)
        assertEquals("storage_class_missing", stored(missing).failure?.code)
        assertTrue(cluster.claims.isEmpty(), "No claim is made")
        cluster.defaultClass = null
        val noDefault = put(session().copy(profile = clusterProfile()))
        reconcile(noDefault)
        assertEquals("storage_class_missing", stored(noDefault).failure?.code)
        // Asked only before the claim is made: a start whose claim exists, or a resume, is not refused.
        val claimed = put(session().copy(profile = clusterProfile("keep")))
        cluster.ensureWorkspace(claimed)
        val resuming = put(session(state = SessionState.RESUMING).copy(epoch = 1, profile = clusterProfile("keep")))
        cluster.ensureWorkspace(resuming)
        reconcile(claimed)
        reconcile(resuming)
        assertNull(stored(claimed).failure)
        assertNull(stored(resuming).failure)
    }

    @Test
    fun aFirstStartOnADeletingClassMakesItsClaimAndAFailedOneProvesItsDeletion() = runTest {
        val creating = put(session().copy(profile = clusterProfile()))
        reconcile(creating)
        assertEquals(1, cluster.claims.size)
        assertEquals("none", stored(creating).toResponse().workspaceDeletion?.state)

        cluster.pods.clear()
        clock.now = t0 + 2.minutes
        assertEquals(clock.now, reconcile(stored(creating)))
        assertEquals(SessionState.FAILED, stored(creating).state)
        assertEquals("start_failed", stored(creating).failure?.code)
        assertNull(drain(creating))
        assertEquals(SessionState.FAILED, stored(creating).state)
        assertEquals(DeletionState.PROVEN, stored(creating).workspaceDeletion?.state)
        assertTrue(cluster.claims.isEmpty())

        // Deleted afterwards: its progress is kept.
        repository.put(stored(creating).copy(desiredState = DesiredState.TERMINATED))
        assertNull(reconcile(stored(creating)))
        assertEquals(SessionState.TERMINATED, stored(creating).state)
        assertEquals(DeletionState.PROVEN, stored(creating).workspaceDeletion?.state)
        assertEquals("cleaned", stored(creating).phase)
    }

    @Test
    fun aDeletionBlockedByItsClusterIsProvenOnceItOpens() = runTest {
        val session = deleted()
        val blocked = put(
            stored(session).copy(
                state = SessionState.TERMINATED,
                phase = "cleanup_blocked",
                terminatedReason = CloudSession.TERMINATED_DELETED,
            ),
        )

        assertEquals(t0, reconcile(blocked))
        assertEquals("cleanup_pending", stored(session).phase)
        assertNull(drain(session))
        assertEquals(DeletionState.PROVEN, stored(session).workspaceDeletion?.state)
    }

    @Test
    fun theGarbageCollectionDeletesWhatNoSessionAccountsForAndCountsRetainedVolumes() = runTest {
        val live = put(session(state = SessionState.RUNNING).copy(epoch = 1, profile = clusterProfile()))
        cluster.ensureWorkspace(live)
        cluster.pods["ostrom/${RuntimeNames.execution(live.id, 1)}"] = true
        cluster.plantOrphans("ostrom")
        cluster.plantOrphans("retired")
        // Neither a volume of another namespace's claim nor one of a live claim is this cluster's orphan.
        val foreignClaim = "${RuntimeNames.WORKSPACE_PREFIX}c"
        cluster.volumes["pv-foreign"] =
            VolumeObservation("pv-foreign", "Released", "Retain", "vol-f", "elsewhere", foreignClaim)
        cluster.volumes["pv-live"] =
            VolumeObservation("pv-live", "Released", "Retain", "vol-l", "ostrom", RuntimeNames.workspace(live.id))
        val recorded = mutableListOf<Pair<Int?, Instant>>()
        val clusters = object : GarbageClusters {
            override suspend fun clusters() = listOf(GarbageCluster(FAKE_CLUSTER, listOf("ostrom")))

            override suspend fun recordGarbageCollection(clusterId: String, orphanedVolumes: Int?, at: Instant) {
                recorded += orphanedVolumes to at
            }
        }
        val liveObjects = LiveObjects(
            claims = setOf(RuntimeNames.workspace(live.id)),
            pods = setOf(RuntimeNames.execution(live.id, 1)),
        )
        val collect = CollectCloudGarbageUseCaseImpl(store(liveObjects, "ostrom", "retired"), cluster, clusters, clock)

        assertEquals(
            GarbageReport(deletedPods = 2, deletedClaims = 2, orphanedVolumes = 1),
            collect(clusters.clusters().single()),
        )
        assertEquals(listOf<Pair<Int?, Instant>>(1 to t0), recorded)
        assertTrue("ostrom/${RuntimeNames.execution(live.id, 1)}" in cluster.pods, "A live pod is kept")
        assertTrue("ostrom/${RuntimeNames.workspace(live.id)}" in cluster.claims, "A live claim is kept")
        assertTrue("ostrom/ostrom-s-older-e1" in cluster.pods, "An object without the cluster label is never deleted")
        assertTrue("ostrom/${RuntimeNames.WORKSPACE_PREFIX}older" in cluster.claims)
        assertTrue("ostrom/ostrom-s-orphan-e1" !in cluster.pods)
        assertTrue("retired/ostrom-s-orphan-e1" !in cluster.pods, "A namespace a session still holds is collected")
    }

    @Test
    fun aNamespaceThatFailsOrVolumesThatCannotBeReadStopNothingElseOfTheCollection() = runTest {
        cluster.plantOrphans("ostrom")
        cluster.plantOrphans("retired")
        cluster.plantOrphans("closed")
        cluster.forbiddenNamespaces += "retired"
        cluster.undeletableNamespaces += "closed"
        cluster.clusterReadsForbidden = true
        val recorded = mutableListOf<Int?>()
        val clusters = object : GarbageClusters {
            override suspend fun clusters() = listOf(GarbageCluster(FAKE_CLUSTER, listOf("ostrom", "closed")))

            override suspend fun recordGarbageCollection(clusterId: String, orphanedVolumes: Int?, at: Instant) {
                recorded += orphanedVolumes
            }
        }
        val nothingLive = store(LiveObjects(emptySet(), emptySet()), "retired")
        val collect = CollectCloudGarbageUseCaseImpl(nothingLive, cluster, clusters, clock)

        val report = collect(clusters.clusters().single())

        assertEquals(GarbageReport(1, 1, null, skippedNamespaces = 2), report)
        assertEquals(listOf<Int?>(null), recorded, "Volumes that cannot be read are not counted")
        assertTrue("ostrom/ostrom-s-orphan-e1" !in cluster.pods, "The other namespaces are collected")
        assertTrue("retired/ostrom-s-orphan-e1" in cluster.pods)

        cluster.unreachable = true
        assertFailsWith<RuntimeUnreachableException> { collect(clusters.clusters().single()) }
    }

    @Test
    fun aClusterWithoutClusterRoleBlocksTheProofOnItsVolumeAndChecksNoStorageClass() = runTest {
        val session = deleted()
        reconcile(session)
        cluster.clusterReadsForbidden = true

        assertEquals(t0 + 2.seconds, reconcile(stored(session)), "The claim is deleted all the same")
        assertNull(stored(session).workspaceDeletion?.volumeHandle)
        assertEquals(t0 + 10.minutes, reconcile(stored(session)))
        val blocked = assertNotNull(stored(session).workspaceDeletion)
        assertEquals(DeletionState.BLOCKED, blocked.state)
        assertEquals(WorkspaceDeletion.VOLUME_UNREADABLE, blocked.problem?.code)
        assertTrue(blocked.problem!!.retryable)
        val again = stored(session)
        assertEquals(t0 + 10.minutes, reconcile(again))
        assertEquals(again, stored(session), "Nothing new to write")

        cluster.clusterReadsForbidden = false
        assertNull(drain(session))
        assertEquals(DeletionState.PROVEN, stored(session).workspaceDeletion?.state)

        cluster.clusterReadsForbidden = true
        cluster.storageClasses["keep"] = "Retain"
        val starting = put(session().copy(profile = clusterProfile("keep")))
        reconcile(starting)
        assertNull(stored(starting).failure, "A class that cannot be read is not checked")
        assertTrue(cluster.claims.isNotEmpty())
    }

    @Test
    fun aClaimBoundInTheRaceWithItsDeletionHasItsVolumeFoundByItsClaimReference() = runTest {
        val raced = deleted()
        val key = "ostrom/${RuntimeNames.workspace(raced.id)}"
        val bound = assertNotNull(cluster.claims[key]?.first)
        cluster.claims[key] = null to true
        reconcile(raced)

        assertEquals(t0 + 2.seconds, reconcile(stored(raced)), "Read unbound, deleted")
        assertNull(stored(raced).workspaceDeletion?.volumeName)
        assertEquals(t0 + 2.seconds, reconcile(stored(raced)), "Its volume found, awaited")
        val found = assertNotNull(stored(raced).workspaceDeletion)
        assertEquals(DeletionState.PENDING, found.state)
        assertEquals(bound, found.volumeName)
        assertNotNull(found.volumeHandle)

        cluster.volumes.remove(bound)
        assertNull(drain(raced))
        assertEquals(DeletionState.PROVEN, stored(raced).workspaceDeletion?.state)
        assertEquals(t0, stored(raced).workspaceDeletion?.volumeDeletedAt)
    }

    private fun store(live: LiveObjects, vararg namespaces: String) = object : LiveCloudObjectsRepository {
        override suspend fun namespaces(clusterId: String) = namespaces.toSet()

        override suspend fun liveObjects(clusterId: String) = live
    }

    private companion object {
        const val MAX_STEPS = 20
    }
}
