// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.domain

import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.secrets.domain.Caller
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** The organization's cloud profiles (TeamCode's `cloud_profiles`): versioned, assigned to members. */
interface CloudProfilesUseCase {
    /**
     * The profiles the caller may see: every one for an owner or an admin, the assigned ones for a member.
     *
     * @param caller A member of the organization.
     *
     * @return They, by name.
     */
    suspend fun list(caller: Caller): List<CloudProfile>

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
 */
class CloudProfilesUseCaseImpl(
    private val repository: CloudProfilesRepository,
    private val clusters: CloudClustersRepository,
    private val clock: Clock,
    private val members: MemberDirectory? = null,
) : CloudProfilesUseCase {
    override suspend fun list(caller: Caller): List<CloudProfile> {
        val all = repository.list(caller.orgId)
        return if (caller.role.isAdmin) all else all.filter { caller.memberId.toString() in it.assignedMemberIds }
    }

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
        val cluster = Uuid.parseOrNull(input.spec.clusterId)?.let { clusters.get(it)?.cluster }
        (profileProblem(input, cluster) ?: membersProblem(orgId, input))?.let { throw InvalidCloudProfileException(it) }
    }

    // Only the organization's members: an id of someone else, or of a member who left, never gets the profile.
    private suspend fun membersProblem(orgId: Uuid, input: CloudProfileInput): String? {
        val directory = members ?: return null
        return "assignedMemberIds names someone not a member of the organization."
            .takeIf { input.assignedMemberIds.any { !directory.isActiveMember(orgId, it) } }
    }

    private companion object {
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
