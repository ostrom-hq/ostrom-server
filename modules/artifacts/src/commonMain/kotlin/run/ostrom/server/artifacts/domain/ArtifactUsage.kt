// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.domain

import run.ostrom.server.artifacts.domain.storage.ArtifactAdministratorRequiredException
import run.ostrom.server.artifacts.domain.storage.StorageCaller
import kotlin.uuid.Uuid

/**
 * What an owner and their organization hold, against the quotas.
 *
 * @property ownerBytes The bytes of the owner's artifacts' folders.
 * @property orgBytes The bytes of every folder of the organization.
 * @property ownerArtifacts The owner's published artifacts (a draft does not count).
 */
data class ArtifactUsage(val ownerBytes: Long, val orgBytes: Long, val ownerArtifacts: Int)

/** Reads the usage the quotas are checked on. */
fun interface ArtifactUsageRepository {
    /** The usage of [ownerId] and of [orgId]. */
    suspend fun usage(orgId: Uuid, ownerId: String): ArtifactUsage
}

/**
 * One owner's published artifacts and the bytes of their folders.
 *
 * @property memberId The owner.
 * @property artifacts Their published artifacts (a draft does not count).
 * @property bytes Each content counted once per artifact, as the quotas count it.
 */
data class MemberArtifactUsage(val memberId: String, val artifacts: Int, val bytes: Long)

/**
 * What the organization stores against its quota, and per owner, largest first (TeamCode's Settings → Artifacts).
 *
 * @property orgBytes The bytes of the published artifacts' folders.
 * @property members The largest owners.
 */
data class ArtifactUsageReport(val orgBytes: Long, val members: List<MemberArtifactUsage>)

/** Reads the usage an owner or an administrator is shown. */
fun interface ArtifactUsageReports {
    /** The usage of [orgId] and of its [limit] largest owners. */
    suspend fun report(orgId: Uuid, limit: Int): ArtifactUsageReport
}

/**
 * `GET /api/v1/artifacts/usage` (TC-ART-11): a person who owns or administers the organization.
 *
 * @param reports The usage.
 */
class ReadArtifactUsageUseCase(private val reports: ArtifactUsageReports) {
    /**
     * The organization's usage and its 200 largest owners.
     *
     * @throws ArtifactAdministratorRequiredException When the caller is not a person who administers.
     */
    suspend fun usage(caller: StorageCaller): ArtifactUsageReport {
        if (!caller.administrator || !caller.person) throw ArtifactAdministratorRequiredException()
        return reports.report(caller.orgId, LARGEST_OWNERS)
    }
}

/** The owners the usage lists (TeamCode shows the same). */
private const val LARGEST_OWNERS = 200
