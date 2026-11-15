// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.infrastructure

import run.ostrom.server.cloud.domain.CloudCluster
import run.ostrom.server.cloud.domain.CloudClustersRepository
import run.ostrom.server.cloud.domain.StoredCloudCluster
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.http.OstromJson
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [CloudClustersRepository] on PostgreSQL through Exposed (`cloud_clusters`, the instance's, no organization).
 *
 * @param database The serving role's pool.
 */
class CloudClustersRepositoryImpl(private val database: JdbcDatabase) : CloudClustersRepository {
    override suspend fun list(): List<CloudCluster> = database.query {
        CloudClusters.selectAll().orderBy(CloudClusters.name to SortOrder.ASC).map { it.toStored().cluster }
    }

    override suspend fun get(id: Uuid): StoredCloudCluster? = database.query {
        CloudClusters.selectAll().where { CloudClusters.id eq id }.firstOrNull()?.toStored()
    }

    override suspend fun version(id: Uuid): Int? = database.query {
        CloudClusters.select(CloudClusters.version).where { CloudClusters.id eq id }
            .map { it[CloudClusters.version] }.firstOrNull()
    }

    // A name taken is no row inserted, as a lost race is: the use case answers a conflict for both.
    override suspend fun insert(stored: StoredCloudCluster): Boolean = database.query {
        CloudClusters.insertIgnore {
            it[id] = stored.cluster.id
            saved(it, stored)
            it[createdAt] = stored.cluster.createdAt
            it[imported] = stored.cluster.imported
        }.insertedCount == 1
    }

    // The garbage collection's report and the creation are left as they are; another cluster's name refuses the save.
    override suspend fun update(stored: StoredCloudCluster): Boolean = database.query {
        val c = CloudClusters
        val other = c.alias("o")
        val nameFree = notExists(
            other.select(other[c.id])
                .where { (other[c.name] eq stored.cluster.name) and (other[c.id] neq stored.cluster.id) },
        )
        c.update({ (c.id eq stored.cluster.id) and (c.version eq stored.cluster.version - 1) and nameFree }) {
            saved(it, stored)
        } == 1
    }

    override suspend fun recordGarbageCollection(id: Uuid, orphanedVolumes: Int?, at: Instant) {
        database.query {
            CloudClusters.update({ CloudClusters.id eq id }) {
                it[CloudClusters.orphanedVolumes] = orphanedVolumes
                it[lastGarbageCollectionAt] = at
            }
        }
    }
}

// The columns an administrator's save writes, on an insert as on an update.
private fun saved(row: UpdateBuilder<*>, stored: StoredCloudCluster) = with(stored.cluster) {
    val c = CloudClusters
    row[c.name] = name
    row[c.apiUrl] = apiUrl
    row[c.sealedCa] = stored.sealedCaCertificate
    row[c.sealedToken] = stored.sealedServiceAccountToken
    row[c.allowedNamespaces] = OstromJson.encodeToString(NAMES, allowedNamespaces)
    row[c.maxRunningPods] = maxRunningPods
    row[c.defaultStorageClass] = defaultStorageClass
    row[c.version] = version
    row[c.updatedAt] = updatedAt
    row[c.inCluster] = inCluster
}

private fun ResultRow.toStored(): StoredCloudCluster {
    val c = CloudClusters
    val ca = this[c.sealedCa]
    val token = this[c.sealedToken]
    val cluster = CloudCluster(
        id = this[c.id],
        name = this[c.name],
        apiUrl = this[c.apiUrl],
        allowedNamespaces = OstromJson.decodeFromString(NAMES, this[c.allowedNamespaces]),
        maxRunningPods = this[c.maxRunningPods],
        defaultStorageClass = this[c.defaultStorageClass],
        version = this[c.version],
        // An administrator's cluster always has both (a new one, or a new apiUrl, brings them); the imported one
        // may have none: in-cluster, or its files unreadable at the import.
        hasCaCertificate = ca.isNotEmpty(),
        hasServiceAccountToken = token.isNotEmpty(),
        createdAt = this[c.createdAt],
        updatedAt = this[c.updatedAt],
        inCluster = this[c.inCluster],
        imported = this[c.imported],
        orphanedVolumes = this[c.orphanedVolumes],
        lastGarbageCollectionAt = this[c.lastGarbageCollectionAt],
    )
    return StoredCloudCluster(cluster, ca, token)
}

private val NAMES = ListSerializer(String.serializer())
