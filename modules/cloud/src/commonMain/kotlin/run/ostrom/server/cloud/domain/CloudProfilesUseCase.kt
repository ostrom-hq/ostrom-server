// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.domain

import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.secrets.domain.Caller
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** The organization's cloud profiles (TeamCode's `cloud_profiles`): versioned, assigned to members. */
interface CloudProfilesUseCase {
    /**
     * The profiles the caller may see: every one for an owner or an admin, the assigned ones for a member. With
     * [member] (`runners.cloud.profiles.member.v1`, TeamCode's `cloud.profiles.member.v1`), the profiles that member
     * can create sessions from, as an administrator creating on their behalf sees them.
     *
     * @param caller A member of the organization.
     * @param member Another member, by id or login; null, or the caller themselves, for the caller's.
     *
     * @return They, by name.
     *
     * @throws ProfilesMemberForbiddenException When a caller who does not administer the organization names another
     *   member.
     * @throws InvalidEffectiveMemberException When the member named is not an active member of the organization.
     */
    suspend fun list(caller: Caller, member: String? = null): List<CloudProfile>

    /**
     * Creates a profile at version 1.
     *
     * @param caller An owner or an admin.
     * @param input The profile.
     *
     * @return It.
     *
     * @throws InvalidCloudProfileException When a value is refused.
     */
    suspend fun create(caller: Caller, input: CloudProfileInput): CloudProfile

    /**
     * Publishes a new version of a profile; a running session keeps the version it started with.
     *
     * @param caller An owner or an admin.
     * @param id The profile.
     * @param input The profile.
     *
     * @return It.
     *
     * @throws CloudProfileNotFoundException When the organization has no such profile.
     * @throws InvalidCloudProfileException When a value is refused.
     * @throws CloudProfileConflictException When another save came first.
     */
    suspend fun update(caller: Caller, id: Uuid, input: CloudProfileInput): CloudProfile
}

/**
 * [CloudProfilesUseCase].
 *
 * @param repository The stored profiles.
 * @param clusters The instance's clusters a profile names.
 * @param clock The clock.
 * @param members Who the profile may be assigned to.
 * @param storage Reads the workspace storage class on the cluster (TeamCode's check at publish); null where sessions
 *   do not run on Kubernetes.
 * @param logins The members' logins, to name a member by login; none without the orgs module.
 */
class CloudProfilesUseCaseImpl(
    private val repository: CloudProfilesRepository,
    private val clusters: CloudClustersRepository,
    private val clock: Clock,
    private val members: MemberDirectory,
    private val storage: WorkspaceStorageClasses? = null,
    private val logins: MemberLogins? = null,
) : CloudProfilesUseCase {
    override suspend fun list(caller: Caller, member: String?): List<CloudProfile> {
        val self = caller.memberId.toString()
        val target = if (member == null) self else memberId(caller.orgId, member)
        if (target == self) return visible(caller.orgId, self, caller.role.isAdmin)
        // As a creation on someone's behalf: only an administrator names another member, who must be one.
        if (!caller.role.isAdmin) throw ProfilesMemberForbiddenException()
        if (target == null || !members.isActiveMember(caller.orgId, target)) throw InvalidEffectiveMemberException()
        return visible(caller.orgId, target, members.roleOf(caller.orgId, target) in ADMINISTRATORS)
    }

    /** Every profile for someone who administers the organization, the ones assigned to [memberId] otherwise. */
    private suspend fun visible(orgId: Uuid, memberId: String, administers: Boolean): List<CloudProfile> {
        val all = repository.list(orgId)
        return if (administers) all else all.filter { memberId in it.assignedMemberIds }
    }

    /** The member a reference names: an id as it is, a login through the members' logins; null for none. */
    private suspend fun memberId(orgId: Uuid, reference: String): String? =
        (Uuid.parseOrNull(reference) ?: logins?.idsOf(orgId, listOf(reference))?.get(reference))?.toString()

    override suspend fun create(caller: Caller, input: CloudProfileInput): CloudProfile {
        caller.requireAdmin()
        val clean = normalize(input)
        check(caller.orgId, clean, expectedVersion = 0)
        val now = clock.nowMillis()
        val profile =
            CloudProfile(Uuid.random(), caller.orgId, clean.name, 1, clean.spec, clean.assignedMemberIds, now, now)
        if (!repository.publish(profile)) throw CloudProfileConflictException()
        return profile
    }

    override suspend fun update(caller: Caller, id: Uuid, input: CloudProfileInput): CloudProfile {
        caller.requireAdmin()
        val before = repository.get(caller.orgId, id) ?: throw CloudProfileNotFoundException()
        val clean = normalize(input)
        check(caller.orgId, clean, expectedVersion = before.version)
        val profile = before.copy(
            name = clean.name,
            version = before.version + 1,
            spec = clean.spec,
            assignedMemberIds = clean.assignedMemberIds,
            updatedAt = clock.nowMillis(),
        )
        if (!repository.publish(profile)) throw CloudProfileConflictException()
        return profile
    }

    private suspend fun check(orgId: Uuid, input: CloudProfileInput, expectedVersion: Int) {
        if (input.version != expectedVersion) throw CloudProfileConflictException()
        val cluster = cluster(input)
        (profileProblem(input, cluster) ?: membersProblem(orgId, input))?.let { throw InvalidCloudProfileException(it) }
        checkStorageClass(cluster, input.spec.storageClass ?: cluster.defaultStorageClass)
    }

    /** The cluster the profile names, checked before the rest of the profile. */
    private suspend fun cluster(input: CloudProfileInput): CloudCluster =
        Uuid.parseOrNull(input.spec.clusterId)?.let { clusters.get(it)?.cluster }
            ?: throw InvalidCloudProfileException("spec.clusterId names no cluster.")

    /**
     * The workspace storage class must exist and delete the provider's volume with its claim, so that a deleted session
     * leaves no volume behind (TeamCode's). One that cannot be read now is checked again at each session's first start.
     */
    private suspend fun checkStorageClass(cluster: CloudCluster, name: String?) {
        val reader = storage ?: return
        when (val found = reader.find(cluster.id, name)) {
            StorageClassLookup.Missing -> throw StorageClassMissingException()
            is StorageClassLookup.Found -> if (found.reclaimPolicy != RECLAIM_DELETE) {
                throw StorageClassRetainsVolumesException(found.name, found.reclaimPolicy)
            }
            StorageClassLookup.Unknown -> Unit
        }
    }

    // Only the organization's members: an id of someone else, or of a member who left, never gets the profile.
    private suspend fun membersProblem(orgId: Uuid, input: CloudProfileInput): String? {
        return "assignedMemberIds names someone not a member of the organization."
            .takeIf { input.assignedMemberIds.any { !members.isActiveMember(orgId, it) } }
    }

    private companion object {
        /** The roles that see every profile. */
        val ADMINISTRATORS = setOf(OrgRole.OWNER, OrgRole.ADMIN)

        /** The reclaim policy that deletes the provider's volume with its claim. */
        const val RECLAIM_DELETE = "Delete"

        fun normalize(input: CloudProfileInput) = input.copy(
            name = input.name.trim(),
            spec = input.spec.copy(
                clusterId = input.spec.clusterId.trim().lowercase(),
                namespace = input.spec.namespace.trim(),
                image = input.spec.image.trim(),
                allowedImageOverrides = input.spec.allowedImageOverrides.map { it.trim() }.distinct(),
                imagePullSecrets = input.spec.imagePullSecrets.map { it.trim() }.distinct(),
                storageClass = input.spec.storageClass.trimmedOrNull(),
                agentVersion = input.spec.agentVersion.trimmedOrNull(),
            ),
            assignedMemberIds = input.assignedMemberIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        )
    }
}
