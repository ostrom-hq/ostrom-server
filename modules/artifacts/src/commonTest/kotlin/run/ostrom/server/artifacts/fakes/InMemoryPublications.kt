// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.fakes

import run.ostrom.server.artifacts.domain.ArtifactPublicationsRepository
import run.ostrom.server.artifacts.domain.ArtifactUsage
import run.ostrom.server.artifacts.domain.ArtifactUsageRepository
import run.ostrom.server.artifacts.domain.PublicationGateImpl
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageRepository
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The publications in memory, by organization and member. */
class InMemoryPublications : ArtifactPublicationsRepository {
    val published = mutableListOf<Triple<Uuid, String, Instant>>()

    override suspend fun since(orgId: Uuid, memberId: String, since: Instant, limit: Int): List<Instant> =
        published.filter { (org, member, at) -> org == orgId && member == memberId && at > since }
            .map { it.third }.sorted().take(limit)

    override suspend fun record(orgId: Uuid, memberId: String, artifactId: Uuid, at: Instant) {
        published += Triple(orgId, memberId, at)
    }
}

/** Nothing held yet. */
val NO_USAGE = ArtifactUsageRepository { _, _ -> ArtifactUsage(0, 0, 0) }

/** A gate on [storage]'s settings with publications in memory, and [usage]. */
fun gateOf(storage: ArtifactStorageRepository, clock: Clock, usage: ArtifactUsageRepository = NO_USAGE) =
    PublicationGateImpl(storage, InMemoryPublications(), usage, clock)
