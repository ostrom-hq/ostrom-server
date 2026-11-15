// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.infrastructure

import run.ostrom.server.cloud.domain.CloudProfile
import run.ostrom.server.cloud.domain.CloudProfileSpec
import run.ostrom.server.cloud.domain.CloudProfilesRepository
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.http.OstromJson
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.uuid.Uuid

/**
 * [CloudProfilesRepository] on PostgreSQL through Exposed, every statement in the organization's tenant
 * ([JdbcDatabase.inOrg], row-level security): a profile and its current version, each version kept as published.
 *
 * @param database The serving role's pool.
 */
class CloudProfilesRepositoryImpl(private val database: JdbcDatabase) : CloudProfilesRepository {
    override suspend fun list(orgId: Uuid): List<CloudProfile> = database.inOrg(orgId) {
        current { CloudProfiles.orgId eq orgId }
            .orderBy(CloudProfiles.name to SortOrder.ASC, CloudProfiles.id to SortOrder.ASC)
            .map { it.toProfile() }
    }

    override suspend fun get(orgId: Uuid, id: Uuid): CloudProfile? = database.inOrg(orgId) {
        current { (CloudProfiles.orgId eq orgId) and (CloudProfiles.id eq id) }.firstOrNull()?.toProfile()
    }

    override suspend fun publish(profile: CloudProfile): Boolean {
        val members = OstromJson.encodeToString(MEMBERS, profile.assignedMemberIds.sorted())
        val spec = OstromJson.encodeToString(CloudProfileSpec.serializer(), profile.spec)
        val p = CloudProfiles
        return database.inOrg(profile.orgId) {
            val moved = if (profile.version == 1) {
                p.insertIgnore {
                    it[id] = profile.id
                    it[orgId] = profile.orgId
                    it[name] = profile.name
                    it[version] = 1
                    it[assignedMemberIds] = members
                    it[createdAt] = profile.createdAt
                    it[updatedAt] = profile.updatedAt
                }.insertedCount == 1
            } else {
                val previous = (p.id eq profile.id) and (p.orgId eq profile.orgId) and
                    (p.version eq profile.version - 1)
                p.update({ previous }) {
                    it[name] = profile.name
                    it[version] = profile.version
                    it[assignedMemberIds] = members
                    it[updatedAt] = profile.updatedAt
                } == 1
            }
            if (moved) {
                CloudProfileVersions.insert {
                    it[profileId] = profile.id
                    it[orgId] = profile.orgId
                    it[version] = profile.version
                    it[CloudProfileVersions.spec] = spec
                    it[createdAt] = profile.updatedAt
                }
            }
            moved
        }
    }
}

// A profile joined to its current version.
private fun current(where: () -> Op<Boolean>): Query {
    val p = CloudProfiles
    val v = CloudProfileVersions
    return p.join(v, JoinType.INNER, additionalConstraint = { (v.profileId eq p.id) and (v.version eq p.version) })
        .select(p.id, p.orgId, p.name, p.version, p.assignedMemberIds, p.createdAt, p.updatedAt, v.spec)
        .where(where)
}

private fun ResultRow.toProfile() = CloudProfile(
    id = this[CloudProfiles.id],
    orgId = this[CloudProfiles.orgId],
    name = this[CloudProfiles.name],
    version = this[CloudProfiles.version],
    spec = OstromJson.decodeFromString(CloudProfileSpec.serializer(), this[CloudProfileVersions.spec]),
    assignedMemberIds = OstromJson.decodeFromString(MEMBERS, this[CloudProfiles.assignedMemberIds]).toSet(),
    createdAt = this[CloudProfiles.createdAt],
    updatedAt = this[CloudProfiles.updatedAt],
)

private val MEMBERS = ListSerializer(String.serializer())
