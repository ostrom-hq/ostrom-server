// SPDX-License-Identifier: Apache-2.0
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
import run.ostrom.server.artifacts.infrastructure.ArtifactsArtifacts
import run.ostrom.server.artifacts.infrastructure.ArtifactsSettings
import run.ostrom.server.artifacts.infrastructure.ArtifactsStorage
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.present
import run.ostrom.server.secrets.domain.SecretCipher
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import org.jetbrains.exposed.v1.jdbc.upsertReturning
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [ArtifactStorageRepository] on PostgreSQL (`artifacts_storage`, `artifacts_settings`), confined to one organization
 * by row-level security. The secret access key is sealed with the instance's secrets key, bound to the organization
 * (`artifact-storage:<org>`): an envelope copied to another organization's row does not open. The cipher suspends, so
 * no transaction calls it: the secret is sealed before the one that writes it and opened after the one that reads it.
 *
 * @param database The serving role's pool.
 * @param sealer Seals the secret; resolved at the first secret sealed or opened, so a server whose secrets key is
 *   not configured still starts (and serves every route that needs no bucket).
 */
internal class ArtifactStorageRepositoryImpl(
    private val database: JdbcDatabase,
    sealer: Lazy<SecretCipher>,
) : ArtifactStorageRepository {
    private val cipher by sealer

    override suspend fun overview(orgId: Uuid): ArtifactStorageOverview = database.inOrg(orgId) {
        val st = ArtifactsStorage
        val storage = st.select(STORAGE_COLUMNS).where { st.orgId eq orgId }.firstOrNull()?.toStorage()
        val se = ArtifactsSettings
        val settings = se.select(SETTINGS_COLUMNS).where { se.orgId eq orgId }.firstOrNull()
            .let { row -> if (row == null) ArtifactSettings() else row.toSettings() }
        val a = ArtifactsArtifacts
        val inUse = a.select(a.id).where { a.orgId eq orgId }.limit(1).any()
        ArtifactStorageOverview(storage, settings, inUse)
    }

    override suspend fun saveStorage(
        orgId: Uuid,
        expectedVersion: Int,
        input: ArtifactStorageInput,
        now: Instant,
    ): ArtifactStorage? {
        val sealed = input.secretAccessKey?.let { cipher.seal(it.encodeToByteArray(), context(orgId)) }
        val st = ArtifactsStorage
        return database.inOrg(orgId) {
            if (expectedVersion == 0) {
                // A replay or a race on the organization's row inserts nothing: `ON CONFLICT (org_id) DO NOTHING`
                // (a conflict on that target alone), as `DO UPDATE … WHERE FALSE`, which returns no row.
                st.upsertReturning(
                    st.orgId,
                    returning = STORAGE_COLUMNS,
                    onUpdate = { it[st.orgId] = st.orgId },
                    where = { Op.FALSE },
                ) {
                    it[this.orgId] = orgId
                    it[endpoint] = input.endpoint
                    it[region] = input.region
                    it[bucket] = input.bucket
                    it[prefix] = input.prefix
                    it[pathStyle] = input.pathStyle
                    it[accessKeyId] = input.accessKeyId
                    it[secretSealed] = sealed
                    it[serverSideEncryption] = input.serverSideEncryption.wire
                    it[verifiedAt] = null
                    it[version] = 1
                    it[updatedAt] = now
                }
            } else {
                st.updateReturning(
                    STORAGE_COLUMNS,
                    where = { (st.orgId eq orgId) and (st.version eq expectedVersion) },
                ) {
                    it[endpoint] = input.endpoint
                    it[region] = input.region
                    it[bucket] = input.bucket
                    it[prefix] = input.prefix
                    it[pathStyle] = input.pathStyle
                    it[accessKeyId] = input.accessKeyId
                    // Without a new secret the stored one stays (the former `COALESCE(:secret, secret_sealed)`).
                    if (sealed != null) it[secretSealed] = sealed
                    it[serverSideEncryption] = input.serverSideEncryption.wire
                    it[verifiedAt] = null
                    it[version] = version + 1
                    it[updatedAt] = now
                }
            }.firstOrNull()?.toStorage()
        }
    }

    override suspend fun deleteStorage(orgId: Uuid, now: Instant): Boolean = database.inOrg(orgId) {
        val st = ArtifactsStorage
        val removed = st.deleteWhere { st.orgId eq orgId } == 1
        // Removing the bucket turns the switch off, as a new version of the settings.
        val se = ArtifactsSettings
        se.update({ (se.orgId eq orgId) and se.enabled }) {
            it[enabled] = false
            it[version] = version + 1
            it[updatedAt] = now
        }
        removed
    }

    override suspend fun recordTest(orgId: Uuid, version: Int, passed: Boolean, now: Instant): Boolean =
        database.inOrg(orgId) {
            val st = ArtifactsStorage
            st.update({ (st.orgId eq orgId) and (st.version eq version) }) {
                it[verifiedAt] = now.takeIf { passed }
            } == 1
        }

    override suspend fun saveSettings(orgId: Uuid, settings: ArtifactSettings, now: Instant): ArtifactSettings? =
        database.inOrg(orgId) {
            val se = ArtifactsSettings
            val quotas = settings.quotas
            val retention = settings.retention
            if (settings.version == 0) {
                se.upsertReturning(
                    se.orgId,
                    returning = SETTINGS_COLUMNS,
                    onUpdate = { it[se.orgId] = se.orgId },
                    where = { Op.FALSE },
                ) {
                    it[this.orgId] = orgId
                    it[enabled] = settings.enabled
                    it[whoCanPublish] = settings.whoCanPublish.wire
                    it[publicSharing] = settings.publicSharing.wire
                    it[quotaMemberBytes] = quotas.memberBytes
                    it[quotaOrgBytes] = quotas.orgBytes
                    it[quotaArtifactsPerMember] = quotas.artifactsPerMember
                    it[quotaPublishesPerHour] = quotas.publishesPerHour
                    it[personalRetentionDays] = retention.personalDays
                    it[sharedRetentionDays] = retention.sharedDays
                    it[maxVersionsPerArtifact] = retention.maxVersionsPerArtifact
                    it[version] = 1
                    it[updatedAt] = now
                }
            } else {
                se.updateReturning(
                    SETTINGS_COLUMNS,
                    where = { (se.orgId eq orgId) and (se.version eq settings.version) },
                ) {
                    it[enabled] = settings.enabled
                    it[whoCanPublish] = settings.whoCanPublish.wire
                    it[publicSharing] = settings.publicSharing.wire
                    it[quotaMemberBytes] = quotas.memberBytes
                    it[quotaOrgBytes] = quotas.orgBytes
                    it[quotaArtifactsPerMember] = quotas.artifactsPerMember
                    it[quotaPublishesPerHour] = quotas.publishesPerHour
                    it[personalRetentionDays] = retention.personalDays
                    it[sharedRetentionDays] = retention.sharedDays
                    it[maxVersionsPerArtifact] = retention.maxVersionsPerArtifact
                    it[version] = version + 1
                    it[updatedAt] = now
                }
            }.firstOrNull()?.toSettings()
        }

    override suspend fun connection(orgId: Uuid): ArtifactStorageConnection? {
        val st = ArtifactsStorage
        val row = database.inOrg(orgId) {
            st.select(STORAGE_COLUMNS + st.secretSealed).where { st.orgId eq orgId }.firstOrNull()
        }
        // A secret that no longer opens (the secrets key changed) leaves the bucket unreachable, never half-read.
        val secret = row?.let { cipher.open(it.present(st.secretSealed, st.orgId), context(orgId)) }
        if (row == null || secret == null) return null
        val storage = row.toStorage()
        return ArtifactStorageConnection(
            storage.endpoint, storage.region, storage.bucket, storage.prefix, storage.pathStyle, storage.accessKeyId,
            secret.decodeToString(), storage.serverSideEncryption, storage.version,
        )
    }

    private fun ResultRow.toStorage(): ArtifactStorage {
        val st = ArtifactsStorage
        return ArtifactStorage(
            orgId = this[st.orgId],
            endpoint = this[st.endpoint],
            region = this[st.region],
            bucket = this[st.bucket],
            prefix = this[st.prefix],
            pathStyle = this[st.pathStyle],
            accessKeyId = this[st.accessKeyId],
            serverSideEncryption = this[st.serverSideEncryption].let { wire ->
                ServerSideEncryption.entries.first { it.wire == wire }
            },
            verifiedAt = this[st.verifiedAt],
            version = this[st.version],
            updatedAt = this[st.updatedAt],
        )
    }

    private fun ResultRow.toSettings(): ArtifactSettings {
        val se = ArtifactsSettings
        return ArtifactSettings(
            enabled = this[se.enabled],
            whoCanPublish = WhoCanPublish.entries.first { it.wire == this[se.whoCanPublish] },
            publicSharing = PublicSharing.entries.first { it.wire == this[se.publicSharing] },
            quotas = ArtifactQuotas(
                memberBytes = this[se.quotaMemberBytes],
                orgBytes = this[se.quotaOrgBytes],
                artifactsPerMember = this[se.quotaArtifactsPerMember],
                publishesPerHour = this[se.quotaPublishesPerHour],
            ),
            retention = ArtifactRetention(
                personalDays = this[se.personalRetentionDays],
                sharedDays = this[se.sharedRetentionDays],
                maxVersionsPerArtifact = this[se.maxVersionsPerArtifact],
            ),
            version = this[se.version],
            updatedAt = this[se.updatedAt],
        )
    }

    private companion object {
        fun context(orgId: Uuid) = "artifact-storage:$orgId"
    }
}

/** Every column but the sealed secret, which only `connection` reads. */
private val STORAGE_COLUMNS = ArtifactsStorage.columns - ArtifactsStorage.secretSealed

private val SETTINGS_COLUMNS = ArtifactsSettings.columns
