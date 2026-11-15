// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

/*
 * The tables of [ArtifactsMigrations], as Exposed reads and writes them. The migrations create the schema; these
 * objects only name its columns. Every one is a tenant table, read and written in `inOrg` (the tenant role with the
 * organization set), each query filtered on `org_id` too, except `artifacts_routes`, also read across organizations
 * by the router's functions ([ArtifactsRouteById], [ArtifactsRouteBySlug], [ArtifactsDueOrganizations]).
 */

/** The published artifacts; a draft has `latest_version` 0. */
internal object ArtifactsArtifacts : Table("artifacts_artifacts") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val ownerId = text("owner_id")
    val title = text("title")
    val access = text("access")
    val latestVersion = integer("latest_version")
    val linkSlug = text("link_slug").nullable()
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")
    val expiresAt = instant("expires_at").nullable()
    val originLaunchId = text("origin_launch_id").nullable()
    val originSessionId = text("origin_session_id").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** The versions of an artifact; their file list is JSON text. */
internal object ArtifactsVersions : Table("artifacts_versions") {
    val artifactId = uuid("artifact_id")
    val orgId = uuid("org_id")
    val number = integer("number")
    val entry = text("entry")
    val files = text("files")
    val createdAt = instant("created_at")
    val createdBy = text("created_by")
    val digest = text("digest").nullable()
    val baseVersion = integer("base_version").nullable()
    val credentialId = text("credential_id").nullable()
    val originLaunchId = text("origin_launch_id").nullable()
    val originSessionId = text("origin_session_id").nullable()

    override val primaryKey = PrimaryKey(artifactId, number)
}

/** The distinct contents of an artifact's folder (their size; the bytes are in the bucket). */
internal object ArtifactsBlobs : Table("artifacts_blobs") {
    val artifactId = uuid("artifact_id")
    val orgId = uuid("org_id")
    val sha256 = text("sha256")
    val size = long("size")
    val markedAt = instant("marked_at").nullable()

    override val primaryKey = PrimaryKey(artifactId, sha256)
}

/** The members an artifact is shared with. */
internal object ArtifactsMembers : Table("artifacts_members") {
    val artifactId = uuid("artifact_id")
    val orgId = uuid("org_id")
    val memberId = text("member_id")

    override val primaryKey = PrimaryKey(artifactId, memberId)
}

/** An artifact's id, organization and link slug, the one table also read across organizations (by functions). */
internal object ArtifactsRoutes : Table("artifacts_routes") {
    val artifactId = uuid("artifact_id")
    val orgId = uuid("org_id")
    val linkSlug = text("link_slug").nullable()

    override val primaryKey = PrimaryKey(artifactId)
}

/** An organization's bucket; the secret is sealed. */
internal object ArtifactsStorage : Table("artifacts_storage") {
    val orgId = uuid("org_id")
    val endpoint = text("endpoint")
    val region = text("region")
    val bucket = text("bucket")
    val prefix = text("prefix")
    val pathStyle = bool("path_style")
    val accessKeyId = text("access_key_id")

    // NOT NULL in the table; nullable here so that a row without one is refused by the table, and an update keeps the
    // stored one with a COALESCE as before.
    val secretSealed = text("secret_sealed").nullable()
    val serverSideEncryption = text("server_side_encryption")
    val verifiedAt = instant("verified_at").nullable()
    val version = integer("version")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(orgId)
}

/** An organization's artifact settings. */
internal object ArtifactsSettings : Table("artifacts_settings") {
    val orgId = uuid("org_id")
    val enabled = bool("enabled")
    val whoCanPublish = text("who_can_publish")
    val publicSharing = text("public_sharing")
    val quotaMemberBytes = long("quota_member_bytes")
    val quotaOrgBytes = long("quota_org_bytes")
    val quotaArtifactsPerMember = integer("quota_artifacts_per_member")
    val quotaPublishesPerHour = integer("quota_publishes_per_hour")
    val personalRetentionDays = integer("personal_retention_days").nullable()
    val sharedRetentionDays = integer("shared_retention_days").nullable()
    val maxVersionsPerArtifact = integer("max_versions_per_artifact")
    val version = integer("version")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(orgId)
}

/** The uploads a member holds open. */
internal object ArtifactsUploads : Table("artifacts_uploads") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val artifactId = uuid("artifact_id")
    val memberId = text("member_id")
    val credentialId = text("credential_id").nullable()
    val createdAt = instant("created_at")
    val expiresAt = instant("expires_at")

    override val primaryKey = PrimaryKey(id)
}

/** The files an upload announced, and where each one is. */
internal object ArtifactsUploadBlobs : Table("artifacts_upload_blobs") {
    val uploadId = uuid("upload_id")
    val orgId = uuid("org_id")
    val sha256 = text("sha256")
    val size = long("size")
    val state = text("state")
    val position = integer("position")

    override val primaryKey = PrimaryKey(uploadId, sha256)
}

/** The chunks of an upload's files that arrived. */
internal object ArtifactsUploadChunks : Table("artifacts_upload_chunks") {
    val uploadId = uuid("upload_id")
    val orgId = uuid("org_id")
    val sha256 = text("sha256")
    val chunkIndex = integer("chunk_index")

    override val primaryKey = PrimaryKey(uploadId, sha256, chunkIndex)
}

/** Each publication of a version, for the hourly limit. */
internal object ArtifactsPublications : Table("artifacts_publications") {
    val orgId = uuid("org_id")
    val memberId = text("member_id")
    val artifactId = uuid("artifact_id")
    val publishedAt = instant("published_at")
}

/** The folders of deleted artifacts the maintenance still has to empty from the bucket. */
internal object ArtifactsFolderDeletions : Table("artifacts_folder_deletions") {
    val artifactId = uuid("artifact_id")
    val orgId = uuid("org_id")
    val deletedAt = instant("deleted_at")

    override val primaryKey = PrimaryKey(artifactId)
}
