// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.infrastructure

import run.ostrom.server.cloud.domain.CloudProfile
import run.ostrom.server.cloud.domain.CloudProfileSpec
import run.ostrom.server.cloud.domain.CloudProfilesRepository
import run.ostrom.server.kernel.http.OstromJson
import run.ostrom.server.secrets.infrastructure.inTenant
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.uuid.Uuid

/**
 * [CloudProfilesRepository] on PostgreSQL, every query in the organization's tenant (row-level security): a profile
 * and its current version, each version kept as published.
 */
class CloudProfilesRepositoryImpl(private val driver: Driver) : CloudProfilesRepository {
    override suspend fun list(orgId: Uuid): List<CloudProfile> = driver.inTenant(orgId) {
        fetchAll(Statement.create("$SELECT WHERE p.org_id = :org ORDER BY p.name, p.id").bind("org", orgId))
            .getOrThrow().map { it.toProfile() }
    }

    override suspend fun get(orgId: Uuid, id: Uuid): CloudProfile? = driver.inTenant(orgId) {
        fetchAll(Statement.create("$SELECT WHERE p.org_id = :org AND p.id = :id").bind("org", orgId).bind("id", id))
            .getOrThrow().firstOrNull()?.toProfile()
    }

    override suspend fun publish(profile: CloudProfile): Boolean = driver.inTenant(profile.orgId) {
        val members = OstromJson.encodeToString(MEMBERS, profile.assignedMemberIds.sorted())
        val head = if (profile.version == 1) {
            Statement.create(
                "INSERT INTO cloud_profiles (id, org_id, name, version, assigned_member_ids, created_at, updated_at) " +
                    "VALUES (:id, :org, :name, 1, :members, :created_at, :updated_at) ON CONFLICT DO NOTHING",
            ).bind("created_at", profile.createdAt)
        } else {
            Statement.create(
                "UPDATE cloud_profiles SET name = :name, version = :version, assigned_member_ids = :members, " +
                    "updated_at = :updated_at WHERE id = :id AND org_id = :org AND version = :previous",
            ).bind("version", profile.version).bind("previous", profile.version - 1)
        }
        val moved = execute(
            head.bind("id", profile.id).bind("org", profile.orgId).bind("name", profile.name)
                .bind("members", members).bind("updated_at", profile.updatedAt),
        ).getOrThrow() == 1L
        if (moved) {
            execute(
                Statement.create(
                    "INSERT INTO cloud_profile_versions (profile_id, org_id, version, spec, created_at) " +
                        "VALUES (:id, :org, :version, :spec, :at)",
                ).bind("id", profile.id).bind("org", profile.orgId).bind("version", profile.version)
                    .bind("spec", OstromJson.encodeToString(CloudProfileSpec.serializer(), profile.spec))
                    .bind("at", profile.updatedAt),
            ).getOrThrow()
        }
        moved
    }

    private fun ResultSet.Row.toProfile() = CloudProfile(
        id = get("id").asUuid(),
        orgId = get("org_id").asUuid(),
        name = get("name").asString(),
        version = get("version").asInt(),
        spec = OstromJson.decodeFromString(CloudProfileSpec.serializer(), get("spec").asString()),
        assignedMemberIds = OstromJson.decodeFromString(MEMBERS, get("assigned_member_ids").asString()).toSet(),
        createdAt = get("created_at").asInstant(),
        updatedAt = get("updated_at").asInstant(),
    )

    private companion object {
        const val SELECT = "SELECT p.id, p.org_id, p.name, p.version, p.assigned_member_ids, p.created_at, " +
            "p.updated_at, v.spec FROM cloud_profiles p " +
            "JOIN cloud_profile_versions v ON v.profile_id = p.id AND v.version = p.version"
        val MEMBERS = ListSerializer(String.serializer())
    }
}
