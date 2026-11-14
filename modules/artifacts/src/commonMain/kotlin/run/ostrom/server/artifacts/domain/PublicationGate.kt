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

    /** Counts a version the caller published. */
    suspend fun published(caller: ArtifactCaller, artifactId: Uuid, at: Instant)
}

/**
 * [PublicationGate] on the organization's settings and its publications.
 *
 * @param storage The settings.
 * @param publications The publications of the last hour.
 * @param clock The time.
 */
class PublicationGateImpl(
    private val storage: ArtifactStorageRepository,
    private val publications: ArtifactPublicationsRepository,
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

    override suspend fun published(caller: ArtifactCaller, artifactId: Uuid, at: Instant) {
        publications.record(caller.orgId, caller.memberId, artifactId, at)
    }

    private suspend fun activeSettings(orgId: Uuid) = storage.overview(orgId).let { overview ->
        overview.availability.reason?.let { throw ArtifactsInactiveException(it) }
        overview.settings
    }
}

private val WINDOW = 1.hours
