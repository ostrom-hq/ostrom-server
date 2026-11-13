// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts.infrastructure.storage

import run.ostrom.server.artifacts.domain.storage.ArtifactQuotas
import run.ostrom.server.artifacts.domain.storage.ArtifactRetention
import run.ostrom.server.artifacts.domain.storage.ArtifactSettings
import run.ostrom.server.artifacts.domain.storage.ArtifactStorage
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageConnection
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageInput
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageOverview
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageRepository
import run.ostrom.server.artifacts.domain.storage.PublicSharing
import run.ostrom.server.artifacts.domain.storage.ServerSideEncryption
import run.ostrom.server.artifacts.domain.storage.WhoCanPublish
import run.ostrom.server.artifacts.infrastructure.inTenant
import run.ostrom.server.secrets.domain.SecretCipher
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asIntOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [ArtifactStorageRepository] on PostgreSQL (`artifacts_storage`, `artifacts_settings`), confined to one organization
 * by row-level security. The secret access key is sealed with the instance's secrets key, bound to the organization
 * (`artifact-storage:<org>`): an envelope copied to another organization's row does not open.
 *
 * @param driver The database.
 * @param cipher Seals the secret.
 */
internal class ArtifactStorageRepositoryImpl(
    private val driver: Driver,
    private val cipher: SecretCipher,
) : ArtifactStorageRepository {
    override suspend fun overview(orgId: Uuid): ArtifactStorageOverview = driver.inTenant(orgId) {
        val storage = fetchAll(select(STORAGE_COLUMNS, "artifacts_storage", orgId)).getOrThrow()
            .firstOrNull()?.toStorage()
        val settings = fetchAll(select(SETTINGS_COLUMNS, "artifacts_settings", orgId)).getOrThrow()
            .firstOrNull()?.toSettings() ?: ArtifactSettings()
        val inUse = fetchAll(
            Statement.create("SELECT EXISTS (SELECT 1 FROM artifacts_artifacts WHERE org_id = :org) AS used")
                .bind("org", orgId),
        ).getOrThrow().single().get("used").asBoolean()
        ArtifactStorageOverview(storage, settings, inUse)
    }

    override suspend fun saveStorage(
        orgId: Uuid,
        expectedVersion: Int,
        input: ArtifactStorageInput,
        now: Instant,
    ): ArtifactStorage? {
        val sealed = input.secretAccessKey?.let { cipher.seal(it.encodeToByteArray(), context(orgId)) }
        val statement = if (expectedVersion == 0) {
            Statement.create(
                """
                INSERT INTO artifacts_storage (org_id, endpoint, region, bucket, prefix, path_style, access_key_id,
                secret_sealed, server_side_encryption, verified_at, version, updated_at) VALUES (:org, :endpoint,
                :region, :bucket, :prefix, :path_style, :key, :secret, :sse, NULL, 1, :now)
                ON CONFLICT (org_id) DO NOTHING RETURNING $STORAGE_COLUMNS
                """,
            )
        } else {
            Statement.create(
                """
                UPDATE artifacts_storage SET endpoint = :endpoint, region = :region, bucket = :bucket,
                prefix = :prefix, path_style = :path_style, access_key_id = :key,
                secret_sealed = COALESCE(:secret, secret_sealed), server_side_encryption = :sse, verified_at = NULL,
                version = version + 1, updated_at = :now WHERE org_id = :org AND version = :expected
                RETURNING $STORAGE_COLUMNS
                """,
            ).bind("expected", expectedVersion)
        }
        statement.bind("org", orgId).bind("endpoint", input.endpoint).bind("region", input.region)
            .bind("bucket", input.bucket).bind("prefix", input.prefix).bind("path_style", input.pathStyle)
            .bind("key", input.accessKeyId).bind("secret", sealed).bind("sse", input.serverSideEncryption.wire)
            .bind("now", now)
        return driver.inTenant(orgId) { fetchAll(statement).getOrThrow().firstOrNull()?.toStorage() }
    }

    override suspend fun deleteStorage(orgId: Uuid, now: Instant): Boolean = driver.inTenant(orgId) {
        val removed = execute(
            Statement.create("DELETE FROM artifacts_storage WHERE org_id = :org").bind("org", orgId),
        ).getOrThrow() == 1L
        // Removing the bucket turns the switch off, as a new version of the settings.
        execute(
            Statement.create(
                "UPDATE artifacts_settings SET enabled = FALSE, version = version + 1, updated_at = :now " +
                    "WHERE org_id = :org AND enabled",
            ).bind("org", orgId).bind("now", now),
        ).getOrThrow()
        removed
    }

    override suspend fun recordTest(orgId: Uuid, version: Int, passed: Boolean, now: Instant): Boolean =
        driver.inTenant(orgId) {
            execute(
                Statement.create(
                    "UPDATE artifacts_storage SET verified_at = :at WHERE org_id = :org AND version = :version",
                ).bind("at", now.takeIf { passed }).bind("org", orgId).bind("version", version),
            ).getOrThrow() == 1L
        }

    override suspend fun saveSettings(orgId: Uuid, settings: ArtifactSettings, now: Instant): ArtifactSettings? {
        val statement = if (settings.version == 0) {
            Statement.create(
                """
                INSERT INTO artifacts_settings (org_id, enabled, who_can_publish, public_sharing, quota_member_bytes,
                quota_org_bytes, quota_artifacts_per_member, quota_publishes_per_hour, personal_retention_days,
                shared_retention_days, max_versions_per_artifact, version, updated_at) VALUES (:org, :enabled, :who,
                :public, :member_bytes, :org_bytes, :artifacts, :publishes, :personal, :shared, :versions, 1, :now)
                ON CONFLICT (org_id) DO NOTHING RETURNING $SETTINGS_COLUMNS
                """,
            )
        } else {
            Statement.create(
                """
                UPDATE artifacts_settings SET enabled = :enabled, who_can_publish = :who, public_sharing = :public,
                quota_member_bytes = :member_bytes, quota_org_bytes = :org_bytes,
                quota_artifacts_per_member = :artifacts, quota_publishes_per_hour = :publishes,
                personal_retention_days = :personal, shared_retention_days = :shared,
                max_versions_per_artifact = :versions, version = version + 1, updated_at = :now
                WHERE org_id = :org AND version = :expected RETURNING $SETTINGS_COLUMNS
                """,
            ).bind("expected", settings.version)
        }
        val quotas = settings.quotas
        val retention = settings.retention
        statement.bind("org", orgId).bind("enabled", settings.enabled).bind("who", settings.whoCanPublish.wire)
            .bind("public", settings.publicSharing.wire).bind("member_bytes", quotas.memberBytes)
            .bind("org_bytes", quotas.orgBytes).bind("artifacts", quotas.artifactsPerMember)
            .bind("publishes", quotas.publishesPerHour).bind("personal", retention.personalDays)
            .bind("shared", retention.sharedDays).bind("versions", retention.maxVersionsPerArtifact).bind("now", now)
        return driver.inTenant(orgId) { fetchAll(statement).getOrThrow().firstOrNull()?.toSettings() }
    }

    override suspend fun connection(orgId: Uuid): ArtifactStorageConnection? {
        val row = driver.inTenant(orgId) {
            fetchAll(select("$STORAGE_COLUMNS, secret_sealed", "artifacts_storage", orgId)).getOrThrow().firstOrNull()
        }
        // A secret that no longer opens (the secrets key changed) leaves the bucket unreachable, never half-read.
        val secret = row?.let { cipher.open(it.get("secret_sealed").asString(), context(orgId)) }
        if (row == null || secret == null) return null
        val storage = row.toStorage()
        return ArtifactStorageConnection(
            storage.endpoint, storage.region, storage.bucket, storage.prefix, storage.pathStyle, storage.accessKeyId,
            secret.decodeToString(), storage.serverSideEncryption, storage.version,
        )
    }

    private fun select(columns: String, table: String, orgId: Uuid): Statement =
        Statement.create("SELECT $columns FROM $table WHERE org_id = :org").bind("org", orgId)

    private fun ResultSet.Row.toStorage() = ArtifactStorage(
        orgId = get("org_id").asUuid(),
        endpoint = get("endpoint").asString(),
        region = get("region").asString(),
        bucket = get("bucket").asString(),
        prefix = get("prefix").asString(),
        pathStyle = get("path_style").asBoolean(),
        accessKeyId = get("access_key_id").asString(),
        serverSideEncryption = get("server_side_encryption").asString().let { wire ->
            ServerSideEncryption.entries.first { it.wire == wire }
        },
        verifiedAt = get("verified_at").asInstantOrNull(),
        version = get("version").asInt(),
        updatedAt = get("updated_at").asInstant(),
    )

    private fun ResultSet.Row.toSettings() = ArtifactSettings(
        enabled = get("enabled").asBoolean(),
        whoCanPublish = WhoCanPublish.entries.first { it.wire == get("who_can_publish").asString() },
        publicSharing = PublicSharing.entries.first { it.wire == get("public_sharing").asString() },
        quotas = ArtifactQuotas(
            memberBytes = get("quota_member_bytes").asLong(),
            orgBytes = get("quota_org_bytes").asLong(),
            artifactsPerMember = get("quota_artifacts_per_member").asInt(),
            publishesPerHour = get("quota_publishes_per_hour").asInt(),
        ),
        retention = ArtifactRetention(
            personalDays = get("personal_retention_days").asIntOrNull(),
            sharedDays = get("shared_retention_days").asIntOrNull(),
            maxVersionsPerArtifact = get("max_versions_per_artifact").asInt(),
        ),
        version = get("version").asInt(),
        updatedAt = get("updated_at").asInstant(),
    )

    private companion object {
        const val STORAGE_COLUMNS = "org_id, endpoint, region, bucket, prefix, path_style, access_key_id, " +
            "server_side_encryption, verified_at, version, updated_at"
        const val SETTINGS_COLUMNS = "enabled, who_can_publish, public_sharing, quota_member_bytes, quota_org_bytes, " +
            "quota_artifacts_per_member, quota_publishes_per_hour, personal_retention_days, shared_retention_days, " +
            "max_versions_per_artifact, version, updated_at"

        fun context(orgId: Uuid) = "artifact-storage:$orgId"
    }
}
