// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud

import run.ostrom.server.cloud.domain.CloudCluster
import run.ostrom.server.cloud.domain.CloudClustersRepository
import run.ostrom.server.cloud.domain.CloudProfile
import run.ostrom.server.cloud.domain.CloudProfilesRepository
import run.ostrom.server.cloud.domain.CloudRuntime
import run.ostrom.server.cloud.domain.CloudRuntimeRepository
import run.ostrom.server.cloud.domain.StoredCloudCluster
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.settings.InstanceAuditRecord
import run.ostrom.server.kernel.settings.InstanceAuditTrail
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

val ALICE: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000a11c")
val BOB: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000b0b")
val ORG_A: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000aa")
val ORG_B: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000bb")
val NOW: Instant = Instant.parse("2026-11-13T12:00:00Z")
const val PEM = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----"

/** A clock the tests move. */
class SettableClock(var now: Instant = NOW) : Clock {
    override fun now(): Instant = now

    /** Moves the clock. */
    fun advance(by: Duration) {
        now += by
    }
}

/** The runtime in memory; [lose] makes the next save lose its race, [duringRead] runs inside a read. */
class MemoryRuntime : CloudRuntimeRepository {
    var stored: CloudRuntime? = null
    var by: Uuid? = null
    var reads = 0
    var lose = false
    var duringRead: (suspend () -> Unit)? = null

    override suspend fun get(): CloudRuntime? {
        reads++
        val read = stored
        duringRead?.also { duringRead = null }?.invoke()
        return read
    }

    override suspend fun save(runtime: CloudRuntime, expectedUpdatedAt: Instant?, by: Uuid?): Boolean {
        if (lose || stored?.updatedAt != expectedUpdatedAt) return false
        stored = runtime
        this.by = by
        return true
    }
}

/** The clusters in memory. */
class MemoryClusters : CloudClustersRepository {
    val rows = linkedMapOf<Uuid, StoredCloudCluster>()

    override suspend fun list(): List<CloudCluster> = rows.values.map { it.cluster }.sortedBy { it.name }

    override suspend fun get(id: Uuid): StoredCloudCluster? = rows[id]

    override suspend fun version(id: Uuid): Int? = rows[id]?.cluster?.version

    override suspend fun insert(stored: StoredCloudCluster): Boolean {
        if (rows.values.any { it.cluster.name == stored.cluster.name }) return false
        rows[stored.cluster.id] = stored
        return true
    }

    override suspend fun update(stored: StoredCloudCluster): Boolean {
        val id = stored.cluster.id
        val taken = rows.values.any { it.cluster.name == stored.cluster.name && it.cluster.id != id }
        if (taken || rows[id]?.cluster?.version != stored.cluster.version - 1) return false
        rows[id] = stored
        return true
    }
}

/** The profiles in memory, every version kept; [lose] makes the next publish lose its race. */
class MemoryProfiles : CloudProfilesRepository {
    val versions = mutableListOf<CloudProfile>()
    var lose = false

    private fun current() = versions.groupBy { it.id }.values.map { all -> all.maxBy { it.version } }

    override suspend fun list(orgId: Uuid): List<CloudProfile> =
        current().filter { it.orgId == orgId }.sortedBy { it.name }

    override suspend fun get(orgId: Uuid, id: Uuid): CloudProfile? = current().firstOrNull {
        it.orgId == orgId && it.id == id
    }

    override suspend fun publish(profile: CloudProfile): Boolean {
        val latest = current().firstOrNull { it.id == profile.id }?.version ?: 0
        if (lose || latest != profile.version - 1) return false
        versions += profile
        return true
    }
}

/** The audit records written. */
class RecordingAudit : InstanceAuditTrail {
    val records = mutableListOf<InstanceAuditRecord>()

    override suspend fun record(record: InstanceAuditRecord) {
        records += record
    }
}

/** The warnings written, with their fields. */
class RecordingLogger : Logger {
    val events = mutableListOf<Triple<LogLevel, String, Map<String, Any?>>>()

    override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
        events += Triple(level, message, fields)
    }

    override fun with(fields: Map<String, Any?>): Logger = this
}
