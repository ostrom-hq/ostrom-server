// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud

import run.ostrom.server.cloud.domain.CloudAdminCaller
import run.ostrom.server.cloud.domain.CloudAdminGate
import run.ostrom.server.cloud.domain.CloudAdminRequiredException
import run.ostrom.server.cloud.domain.CloudRuntime
import run.ostrom.server.cloud.domain.CloudRuntimeConflictException
import run.ostrom.server.cloud.domain.CloudRuntimeUseCaseImpl
import run.ostrom.server.cloud.domain.InvalidCloudRuntimeException
import run.ostrom.server.kernel.settings.InstanceAdministrators
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CloudRuntimeTest {
    private val repository = MemoryRuntime()
    private val audit = RecordingAudit()
    private val logger = RecordingLogger()
    private val clock = SettableClock()
    private val gate = CloudAdminGate(InstanceAdministrators { it == ALICE })
    private val runtime = CloudRuntimeUseCaseImpl(repository, gate, audit, logger, clock)
    private val alice = CloudAdminCaller(ALICE, person = true)
    private val on =
        CloudRuntime(enabled = true, runnerImage = "ghcr.io/acme/runner:1", serverUrl = "https://api.acme.dev/")

    @Test
    fun onlyAPersonWhoAdministersTheInstanceReadsOrSaves() = runTest {
        assertFailsWith<CloudAdminRequiredException> { runtime.get(CloudAdminCaller(BOB, person = true)) }
        assertFailsWith<CloudAdminRequiredException> { runtime.get(CloudAdminCaller(ALICE, person = false)) }
        assertFailsWith<CloudAdminRequiredException> { runtime.get(CloudAdminCaller(null, person = true)) }
        assertFailsWith<CloudAdminRequiredException> { runtime.save(CloudAdminCaller(BOB, true), on, null) }
        assertEquals(CloudRuntime(), runtime.get(alice))
    }

    @Test
    fun aSaveReplacesTheRuntimeOverTheOneReadOnly() = runTest {
        val saved = runtime.save(alice, on, null)

        assertEquals("https://api.acme.dev", saved.serverUrl)
        assertEquals(NOW, saved.updatedAt)
        assertEquals(saved, runtime.get(alice))
        assertEquals(ALICE, repository.by)
        assertEquals(mapOf("enabled" to "true"), audit.records.single().details)
        assertFailsWith<CloudRuntimeConflictException> { runtime.save(alice, on, null) }
        clock.advance(1.seconds)
        val off = runtime.save(alice, on.copy(enabled = false), saved.updatedAt)
        assertFalse(off.enabled)
        assertFailsWith<CloudRuntimeConflictException> { runtime.save(alice, on, saved.updatedAt) }
    }

    @Test
    fun aValueTheServerRefusesIsNamed() = runTest {
        suspend fun refused(value: CloudRuntime) =
            assertFailsWith<InvalidCloudRuntimeException> { runtime.save(alice, value, null) }.message.orEmpty()

        assertTrue("runnerImage" in refused(on.copy(runnerImage = "bad image")))
        assertTrue("runnerBinary" in refused(on.copy(runnerBinary = "relative/ostrom")))
        assertTrue("serverUrl" in refused(on.copy(serverUrl = "http://api.acme.dev")))
        assertTrue("serverUrl" in refused(on.copy(serverUrl = "https://api.acme.dev/path")))
        assertTrue("serverUrl" in refused(on.copy(serverUrl = "https://169.254.169.254")))
        assertTrue("workspaceAccessMode" in refused(on.copy(workspaceAccessMode = "ReadWriteMany")))
        assertTrue("pausedRetentionDays" in refused(on.copy(pausedRetentionDays = 0)))
        assertTrue("failedWorkspaceRetentionDays" in refused(on.copy(failedWorkspaceRetentionDays = 3651)))
        assertTrue("maxRunningSessionsPerMember" in refused(on.copy(maxRunningSessionsPerMember = 0)))
        assertTrue("maxRetainedSessionsPerMember" in refused(on.copy(maxRetainedSessionsPerMember = 10_001)))
        assertTrue("transcriptRetentionDays" in refused(on.copy(transcriptRetentionDays = 0)))
        assertTrue("memberTransfers" in refused(on.copy(memberTransfers = "always")))
        assertTrue("needed to turn" in refused(on.copy(runnerImage = " ")))
        // Optional values unset are accepted, and a blank binary is the default one.
        val saved = runtime.save(
            alice,
            CloudRuntime(pausedRetentionDays = null, transcriptRetentionDays = null, runnerBinary = " "),
            null,
        )
        assertEquals(CloudRuntime.DEFAULT_RUNNER_BINARY, saved.runnerBinary)
    }

    @Test
    fun everyReplicaReadsTheRuntimeAtMostEveryThirtySeconds() = runTest {
        assertEquals(CloudRuntime(), runtime.current())
        val reads = repository.reads
        repository.stored = on.copy(updatedAt = NOW)
        assertEquals(CloudRuntime(), runtime.current())
        assertEquals(reads, repository.reads)
        clock.advance(30.seconds)
        assertTrue(runtime.current().enabled)
        // This replica's save is seen at once.
        val saved = runtime.save(alice, on.copy(enabled = false), NOW)
        assertEquals(saved, runtime.current())
    }

    @Test
    fun aReadASaveOvertakesIsNotKept() = runTest {
        // The read takes the empty runtime, then a save on this replica lands before the read keeps it.
        repository.duringRead = { runtime.save(alice, on, null) }

        assertEquals(CloudRuntime(), runtime.current())

        assertTrue(runtime.current().enabled)
    }

    @Test
    fun theFormerVariablesSeedTheRuntimeOnceAndAreThenIgnored() = runTest {
        runtime.seed { null }
        assertNull(repository.stored)
        val environment =
            mapOf("OSTROM_RUNNERS_DRIVER" to " Kubernetes ", "OSTROM_RUNNERS_SERVER_URL" to "https://api.acme.dev")

        runtime.seed(environment::get)
        runtime.seed(environment::get)

        val seeded = repository.stored
        assertEquals(true, seeded?.enabled)
        // The image the runners module used when the variable was unset.
        assertEquals("ghcr.io/ostrom-hq/ostrom-runner:latest", seeded?.runnerImage)
        assertNull(repository.by)
        assertEquals("cloud.runtime.imported", audit.records.single().event)
        assertTrue("Imported" in logger.events[0].second)
        assertTrue("Ignored" in logger.events[1].second)
        assertEquals("OSTROM_RUNNERS_DRIVER,OSTROM_RUNNERS_SERVER_URL", logger.events[1].third["variables"])
        assertTrue(runtime.current().enabled)
    }

    @Test
    fun anInvalidSeedIsStoredOffKeepingWhatIsValid() = runTest {
        val environment =
            mapOf("OSTROM_RUNNERS_IMAGE" to "ghcr.io/acme/runner:2", "OSTROM_RUNNERS_SERVER_URL" to "ftp://x")
        runtime.seed(environment::get)

        assertEquals(CloudRuntime(runnerImage = "ghcr.io/acme/runner:2", updatedAt = NOW), repository.stored)
        val other = MemoryRuntime().apply { lose = true }
        CloudRuntimeUseCaseImpl(other, gate, audit, logger, clock).seed(mapOf("OSTROM_RUNNERS_DRIVER" to "none")::get)
        assertNull(other.stored)
        assertEquals(1, audit.records.size)
        // A driver of none, with an invalid image: stored off, without it.
        val third = MemoryRuntime()
        CloudRuntimeUseCaseImpl(third, gate, audit, logger, clock)
            .seed(mapOf("OSTROM_RUNNERS_DRIVER" to "kubernetes", "OSTROM_RUNNERS_IMAGE" to "bad image")::get)
        assertEquals(CloudRuntime(updatedAt = NOW), third.stored)
    }
}
