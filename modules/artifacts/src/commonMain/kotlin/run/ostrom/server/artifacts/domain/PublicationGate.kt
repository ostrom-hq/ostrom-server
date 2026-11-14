// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.domain

import run.ostrom.server.artifacts.domain.storage.ArtifactStorageRepository
import run.ostrom.server.artifacts.domain.storage.ArtifactsInactiveException
import run.ostrom.server.artifacts.domain.storage.WhoCanPublish
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The publications a member made, kept for the hourly limit: pruning a version never gives its publication back
 * (TeamCode counts them from the audit log).
 */
interface ArtifactPublicationsRepository {
    /**
     * The member's publications since [since], oldest first.
     *
     * @param limit The most returned: the limit is all a check needs.
     *
     * @return Their times.
     */
    suspend fun since(orgId: Uuid, memberId: String, since: Instant, limit: Int): List<Instant>

    /** Records that the member published a version of [artifactId] at [at]. */
    suspend fun record(orgId: Uuid, memberId: String, artifactId: Uuid, at: Instant)
}

/**
 * Whether a caller may publish now, by the organization's settings (TeamCode's commit rules): artifacts are active,
 * `whoCanPublish` lets them, and they are under `publishesPerHour`.
 */
interface PublicationGate {
    /**
     * Admits a publication.
     *
     * @throws ArtifactsInactiveException When artifacts are inactive.
     * @throws ArtifactPublishForbiddenException When only administrators publish and the caller is not one.
     * @throws ArtifactPublishRateLimitedException When the caller is at the hourly limit.
     */
    suspend fun admit(caller: ArtifactCaller)

    /**
     * Refuses a publication that would put the owner or the organization over a quota, the version's folder counted
     * as it would be (TeamCode's `quotaMemberBytes`, `quotaTeamBytes` and `quotaArtifactsPerMember`).
     *
     * @param ownerId The artifact's owner, whose quota a version by anyone counts in.
     * @param added The bytes the folder gains.
     * @param newArtifact Whether the publication makes an artifact (counted in `artifactsPerMember`).
     *
     * @throws ArtifactQuotaExceededException When it would.
     */
    suspend fun withinQuotas(orgId: Uuid, ownerId: String, added: Long, newArtifact: Boolean)

    /** Counts a version the caller published. */
    suspend fun published(caller: ArtifactCaller, artifactId: Uuid, at: Instant)
}

/**
 * [PublicationGate] on the organization's settings and its publications.
 *
 * @param storage The settings.
 * @param publications The publications of the last hour.
 * @param usages What owners and organizations hold.
 * @param clock The time.
 */
class PublicationGateImpl(
    private val storage: ArtifactStorageRepository,
    private val publications: ArtifactPublicationsRepository,
    private val usages: ArtifactUsageRepository,
    private val clock: Clock = Clock.System,
) : PublicationGate {
    override suspend fun admit(caller: ArtifactCaller) {
        val settings = activeSettings(caller.orgId)
        if (settings.whoCanPublish == WhoCanPublish.ADMINS && !caller.administrator) {
            throw ArtifactPublishForbiddenException()
        }
        val now = clock.now()
        val limit = settings.quotas.publishesPerHour
        val recent = publications.since(caller.orgId, caller.memberId, now - WINDOW, limit)
        if (recent.size >= limit) {
            // The oldest of them leaves the window first.
            val wait = recent.first() + WINDOW - now
            throw ArtifactPublishRateLimitedException(wait.inWholeSeconds + 1)
        }
    }

    override suspend fun withinQuotas(orgId: Uuid, ownerId: String, added: Long, newArtifact: Boolean) {
        val quotas = storage.overview(orgId).settings.quotas
        val usage = usages.usage(orgId, ownerId)
        val refusal = when {
            newArtifact && usage.ownerArtifacts >= quotas.artifactsPerMember ->
                "The owner already holds ${quotas.artifactsPerMember} artifacts, the organization's limit."
            usage.ownerBytes + added > quotas.memberBytes ->
                "This publication would put its owner over ${quotas.memberBytes} bytes of artifacts."
            usage.orgBytes + added > quotas.orgBytes ->
                "This publication would put the organization over ${quotas.orgBytes} bytes of artifacts."
            else -> null
        }
        if (refusal != null) throw ArtifactQuotaExceededException(refusal)
    }

    override suspend fun published(caller: ArtifactCaller, artifactId: Uuid, at: Instant) {
        publications.record(caller.orgId, caller.memberId, artifactId, at)
    }

    private suspend fun activeSettings(orgId: Uuid) = storage.overview(orgId).let { overview ->
        overview.availability.reason?.let { throw ArtifactsInactiveException(it) }
        overview.settings
    }
}

private val WINDOW = 1.hours
