// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.DeletionState
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.RuntimeNames
import run.ostrom.server.runners.domain.SessionFailure
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.domain.WorkspaceDeletion
import run.ostrom.server.runners.fakes.FAKE_CLUSTER
import run.ostrom.server.runners.fakes.ORG_B
import run.ostrom.server.runners.fakes.clusterProfile
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.infrastructure.LiveCloudObjectsRepositoryImpl
import run.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Against a real PostgreSQL: a workspace deletion's proof, and what the garbage collection keeps on a cluster. */
class CloudWorkspaceDeletionPostgresTest {
    private val t0 = Instant.parse("2026-10-30T10:00:00Z")

    @Test
    fun theProofIsStoredAndRead() = serverTest(listOf(RunnersModule)) { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val created = session(createdAt = t0).copy(profile = clusterProfile())
        repository.createWithinCap(created, 5)
        val blocked = WorkspaceDeletion(
            state = DeletionState.BLOCKED,
            claimDeletedAt = t0,
            volumeName = "pv-1",
            volumeHandle = "vol-1",
            problem = SessionFailure(WorkspaceDeletion.VOLUME_DELETION_STALLED, "Stalled.", retryable = true),
        )
        val stored = assertNotNull(repository.compareAndSet(created.copy(workspaceDeletion = blocked), t0))
        assertEquals(blocked, stored.workspaceDeletion)
        val proven = blocked.copy(state = DeletionState.PROVEN, volumeDeletedAt = t0 + 1.minutes, problem = null)
        assertEquals(proven, repository.compareAndSet(stored.copy(workspaceDeletion = proven), null)?.workspaceDeletion)
        assertEquals(proven, repository.getById(created.id)?.workspaceDeletion)
    }

    @Test
    fun theLiveObjectsOfAClusterAreEveryOrganizationsSessionsNotDoneWithIt() = serverTest(
        listOf(RunnersModule),
    ) { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val running = session(state = SessionState.RUNNING, createdAt = t0).copy(epoch = 3, profile = clusterProfile())
        val paused = session(state = SessionState.PAUSED, desired = DesiredState.PAUSED, orgId = ORG_B, createdAt = t0)
            .copy(epoch = 2, profile = clusterProfile().copy(namespace = "retired"))
        val deleting = session(state = SessionState.TERMINATED, desired = DesiredState.TERMINATED, createdAt = t0)
            .copy(
                profile = clusterProfile(),
                workspaceDeletion = WorkspaceDeletion(DeletionState.PENDING, claimDeletedAt = t0),
            )
        val done = session(state = SessionState.TERMINATED, desired = DesiredState.TERMINATED, createdAt = t0)
            .copy(
                profile = clusterProfile().copy(namespace = "old"),
                workspaceDeletion = WorkspaceDeletion(DeletionState.PROVEN),
            )
        val elsewhere = session(state = SessionState.RUNNING, createdAt = t0)
            .copy(epoch = 1, profile = clusterProfile().copy(clusterId = "another"))
        for (created in listOf(running, paused, deleting, done, elsewhere)) repository.createWithinCap(created, 10)

        val store = LiveCloudObjectsRepositoryImpl(server.koin.get<JdbcDatabase>())
        val live = store.liveObjects(FAKE_CLUSTER)

        assertEquals(setOf(RuntimeNames.workspace(running.id), RuntimeNames.workspace(paused.id)), live.claims)
        assertEquals(setOf(RuntimeNames.execution(running.id, 3)), live.pods)
        assertEquals(setOf("ostrom", "retired"), store.namespaces(FAKE_CLUSTER))
    }
}
