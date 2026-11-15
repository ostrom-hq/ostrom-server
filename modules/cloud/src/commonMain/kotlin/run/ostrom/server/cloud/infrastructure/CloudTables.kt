// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

// The tables of [CloudMigrations] as Exposed reads and writes them. The migrations create the schema; these objects
// only name its columns and their types.

/** `cloud_runtime`: the instance's one row (id 1), its fields as JSON. */
internal object CloudRuntimeRows : Table("cloud_runtime") {
    val id = short("id")
    val body = text("body")

    // NOT NULL in the schema; typed as the domain's date, null while nothing is stored (a save always has one).
    val updatedAt = instant("updated_at").nullable()
    val updatedBy = uuid("updated_by").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** `cloud_clusters`: the instance's Kubernetes clusters, no organization. */
internal object CloudClusters : Table("cloud_clusters") {
    val id = uuid("id")
    val name = text("name")
    val apiUrl = text("api_url")
    val sealedCa = text("sealed_ca")
    val sealedToken = text("sealed_token")
    val allowedNamespaces = text("allowed_namespaces")
    val maxRunningPods = integer("max_running_pods")
    val defaultStorageClass = text("default_storage_class").nullable()
    val version = integer("version")
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")

    // Version 4: the server's own cluster, and the one imported from the former variables.
    val inCluster = bool("in_cluster")
    val imported = bool("imported")

    // Version 5: the garbage collection's report, read but never written by an administrator's save.
    val orphanedVolumes = integer("orphaned_volumes").nullable()
    val lastGarbageCollectionAt = instant("last_garbage_collection_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** `cloud_profiles`: an organization's profile and its current version (row-level security). */
internal object CloudProfiles : Table("cloud_profiles") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val name = text("name")
    val version = integer("version")
    val assignedMemberIds = text("assigned_member_ids")
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/** `cloud_profile_versions`: each version of a profile, kept as published (row-level security). */
internal object CloudProfileVersions : Table("cloud_profile_versions") {
    val profileId = uuid("profile_id")
    val orgId = uuid("org_id")
    val version = integer("version")
    val spec = text("spec")
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(profileId, version)
}

/** `cloud_org_settings`: one row per organization (row-level security). */
internal object CloudOrgSettings : Table("cloud_org_settings") {
    val orgId = uuid("org_id")
    val body = text("body")

    // NOT NULL in the schema; typed as the domain's, null while nothing is stored (a save always has both).
    val updatedAt = instant("updated_at").nullable()
    val updatedBy = uuid("updated_by").nullable()

    override val primaryKey = PrimaryKey(orgId)
}
