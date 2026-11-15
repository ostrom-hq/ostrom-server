// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.cloud.domain.CloudCluster
import run.ostrom.server.cloud.domain.CloudClustersRepository
import run.ostrom.server.cloud.domain.StoredCloudCluster
import run.ostrom.server.kernel.database.ScheduledWork
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.runners.domain.CollectCloudGarbageUseCase
import run.ostrom.server.runners.domain.GarbageCluster
import run.ostrom.server.runners.domain.GarbageClusters
import run.ostrom.server.runners.domain.GarbageReport
import run.ostrom.server.runners.domain.RuntimeUnreachableException
import run.ostrom.server.runners.infrastructure.CloudGarbageClusters
import run.ostrom.server.runners.infrastructure.CloudGarbageWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The clusters' garbage collection runs every 10 minutes on one replica, and a cluster that fails stops no other. */
class CloudGarbageWorkerTest {
    private class RecordingLogger : Logger {
        val lines = mutableListOf<Pair<String, Map<String, Any?>>>()
        override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
            lines += message to fields
        }
        override fun with(fields: Map<String, Any?>): Logger = this
    }

    private val logger = RecordingLogger()
    private val asked = mutableListOf<Triple<String, Duration, Duration>>()
    private var due = true
    private val work = object : ScheduledWork {
        override suspend fun runDue(
            name: String,
            interval: Duration,
            timeout: Duration,
            block: suspend () -> Unit,
        ): Boolean {
            asked += Triple(name, interval, timeout)
            if (due) block()
            return due
        }
    }
    private var listing: () -> List<GarbageCluster> =
        { listOf(GarbageCluster("a", emptyList()), GarbageCluster("b", emptyList())) }
    private val clusters = object : GarbageClusters {
        override suspend fun clusters() = listing()

        override suspend fun recordGarbageCollection(clusterId: String, orphanedVolumes: Int?, at: Instant) = Unit
    }
    private val collected = mutableListOf<String>()
    private val collect = CollectCloudGarbageUseCase { cluster ->
        if (cluster.id == "a") throw RuntimeUnreachableException("gone")
        collected += cluster.id
        GarbageReport(0, 0, 0)
    }
    private val worker = CloudGarbageWorker(work, clusters, collect, logger)

    @Test
    fun eachClusterIsCollectedAndOneThatFailsIsReported() = runTest {
        worker.pass()

        assertEquals(listOf(Triple("runners/cloud-garbage", 10.minutes, 4.minutes)), asked)
        assertEquals(listOf("b"), collected)
        assertEquals("cloud garbage collection failed for a cluster", logger.lines.single().first)
        assertEquals("a", logger.lines.single().second["cluster_id"])

        due = false
        worker.pass()
        assertEquals(listOf("b"), collected, "Not due: nothing runs")
    }

    @Test
    fun aClusterWithNamespacesSkippedIsReported() = runTest {
        val partial = CloudGarbageWorker(work, clusters, { GarbageReport(1, 0, null, skippedNamespaces = 2) }, logger)

        partial.pass()

        assertEquals(2, logger.lines.size, "One warning per cluster")
        assertEquals("cloud garbage collection skipped namespaces of a cluster", logger.lines.first().first)
        assertEquals(2, logger.lines.first().second["namespaces"])
    }

    @Test
    fun theLoopAsksTheLeaseEveryMinute() = runTest {
        due = false
        val loop = launch { worker.run() }
        runCurrent()
        advanceTimeBy(1.minutes)
        runCurrent()
        loop.cancel()

        assertEquals(2, asked.size)
    }

    @Test
    fun aPassThatFailsIsReportedAndACancellationGoesThrough() = runTest {
        listing = { error("database down") }
        worker.pass()
        assertEquals("cloud garbage collection failed", logger.lines.single().first)

        listing = { throw CancellationException("stopped") }
        assertFailsWith<CancellationException> { worker.pass() }
    }

    @Test
    fun theConsolesClustersAreVisitedAndTheirReportRecorded() = runTest {
        val id = Uuid.random()
        val at = Instant.parse("2026-11-14T10:00:00Z")
        val recorded = mutableListOf<Triple<Uuid, Int?, Instant>>()
        val stored = CloudCluster(id, "prod", "https://k8s", listOf("ostrom"), 10, null, 1, true, true, at, at)
        val repository = object : CloudClustersRepository {
            override suspend fun list() = listOf(stored)
            override suspend fun get(id: Uuid): StoredCloudCluster? = null
            override suspend fun version(id: Uuid): Int? = null
            override suspend fun insert(stored: StoredCloudCluster) = false
            override suspend fun update(stored: StoredCloudCluster) = false
            override suspend fun recordGarbageCollection(id: Uuid, orphanedVolumes: Int?, at: Instant) {
                recorded += Triple(id, orphanedVolumes, at)
            }
        }
        val adapter = CloudGarbageClusters(repository)

        assertEquals(listOf(GarbageCluster(id.toString(), listOf("ostrom"))), adapter.clusters())
        adapter.recordGarbageCollection(id.toString(), 3, at)
        adapter.recordGarbageCollection("not-a-cluster", 3, at)
        assertEquals(listOf(Triple<Uuid, Int?, Instant>(id, 3, at)), recorded)
    }
}
