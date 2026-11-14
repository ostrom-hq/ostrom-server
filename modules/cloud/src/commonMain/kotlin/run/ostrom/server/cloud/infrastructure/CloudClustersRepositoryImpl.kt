// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.infrastructure

import run.ostrom.server.cloud.domain.CloudCluster
import run.ostrom.server.cloud.domain.CloudClustersRepository
import run.ostrom.server.cloud.domain.StoredCloudCluster
import run.ostrom.server.kernel.http.OstromJson
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.uuid.Uuid

/** [CloudClustersRepository] on PostgreSQL (`cloud_clusters`, the instance's, no organization). */
class CloudClustersRepositoryImpl(private val driver: Driver) : CloudClustersRepository {
    override suspend fun list(): List<CloudCluster> =
        driver.fetchAll("SELECT $COLUMNS FROM cloud_clusters ORDER BY name").getOrThrow().map { it.toStored().cluster }

    override suspend fun get(id: Uuid): StoredCloudCluster? = driver.fetchAll(
        Statement.create("SELECT $COLUMNS FROM cloud_clusters WHERE id = :id").bind("id", id),
    ).getOrThrow().firstOrNull()?.toStored()

    override suspend fun version(id: Uuid): Int? = driver.fetchAll(
        Statement.create("SELECT version FROM cloud_clusters WHERE id = :id").bind("id", id),
    ).getOrThrow().firstOrNull()?.get("version")?.asInt()

    // A name taken is no row inserted, as a lost race is: the use case answers a conflict for both.
    override suspend fun insert(stored: StoredCloudCluster): Boolean = driver.execute(
        bind(
            Statement.create(
                "INSERT INTO cloud_clusters ($COLUMNS) VALUES (:id, :name, :api_url, :ca, :token, :namespaces, " +
                    ":pods, :storage, :version, :created_at, :updated_at, :in_cluster, :imported) " +
                    "ON CONFLICT DO NOTHING",
            ),
            stored,
        ).bind("created_at", stored.cluster.createdAt).bind("imported", stored.cluster.imported),
    ).getOrThrow() == 1L

    // Only the names the statement has: the driver refuses a value bound to none.
    override suspend fun update(stored: StoredCloudCluster): Boolean = driver.execute(
        bind(
            Statement.create(
                "UPDATE cloud_clusters SET name = :name, api_url = :api_url, sealed_ca = :ca, sealed_token = :token, " +
                    "allowed_namespaces = :namespaces, max_running_pods = :pods, default_storage_class = :storage, " +
                    "version = :version, updated_at = :updated_at, in_cluster = :in_cluster " +
                    "WHERE id = :id AND version = :previous " +
                    "AND NOT EXISTS (SELECT 1 FROM cloud_clusters o WHERE o.name = :name AND o.id <> :id)",
            ),
            stored,
        ).bind("previous", stored.cluster.version - 1),
    ).getOrThrow() == 1L

    private fun bind(statement: Statement, stored: StoredCloudCluster): Statement = with(stored.cluster) {
        statement.bind("id", id).bind("name", name).bind("api_url", apiUrl)
            .bind("ca", stored.sealedCaCertificate).bind("token", stored.sealedServiceAccountToken)
            .bind("namespaces", OstromJson.encodeToString(NAMES, allowedNamespaces))
            .bind("pods", maxRunningPods).bind("storage", defaultStorageClass).bind("version", version)
            .bind("updated_at", updatedAt).bind("in_cluster", inCluster)
    }

    private fun ResultSet.Row.toStored(): StoredCloudCluster {
        val ca = get("sealed_ca").asString()
        val token = get("sealed_token").asString()
        val cluster = CloudCluster(
            id = get("id").asUuid(),
            name = get("name").asString(),
            apiUrl = get("api_url").asString(),
            allowedNamespaces = OstromJson.decodeFromString(NAMES, get("allowed_namespaces").asString()),
            maxRunningPods = get("max_running_pods").asInt(),
            defaultStorageClass = get("default_storage_class").asStringOrNull(),
            version = get("version").asInt(),
            // An administrator's cluster always has both (a new one, or a new apiUrl, brings them); the imported one
            // may have none: in-cluster, or its files unreadable at the import.
            hasCaCertificate = ca.isNotEmpty(),
            hasServiceAccountToken = token.isNotEmpty(),
            createdAt = get("created_at").asInstant(),
            updatedAt = get("updated_at").asInstant(),
            inCluster = get("in_cluster").asBoolean(),
            imported = get("imported").asBoolean(),
        )
        return StoredCloudCluster(cluster, ca, token)
    }
}

private const val COLUMNS = "id, name, api_url, sealed_ca, sealed_token, allowed_namespaces, max_running_pods, " +
    "default_storage_class, version, created_at, updated_at, in_cluster, imported"
private val NAMES = ListSerializer(String.serializer())
