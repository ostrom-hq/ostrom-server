// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Gives every session created before cloud profiles the profile of the former variables' cluster
 * ([SessionProfiles.legacy]), at each start of a server whose sessions run on Kubernetes, besides the reconciler's
 * step: a session no step reaches (paused for months) never waits for one, so the former variables can go.
 */
interface BackfillSessionProfilesUseCase {
    /**
     * Gives it to every session without one.
     *
     * @return How many sessions were given one now; 0 when none lacks one.
     */
    suspend operator fun invoke(): Int
}

/**
 * [BackfillSessionProfilesUseCase]: the sessions are read by pages, and each is written by compare-and-set, so replicas
 * starting together, or a reconciler's step, never write one twice or over a newer state.
 *
 * @param repository The sessions.
 * @param profiles The former variables' profile.
 * @param clock The time.
 * @param pageSize How many sessions are read at once.
 */
class BackfillSessionProfilesUseCaseImpl(
    private val repository: CloudSessionsRepository,
    private val profiles: SessionProfiles,
    private val clock: Clock,
    private val pageSize: Int = PAGE_SIZE,
) : BackfillSessionProfilesUseCase {
    override suspend fun invoke(): Int {
        var given = 0
        var after: Uuid? = null
        var legacy: SessionProfile? = null
        do {
            val page = repository.withoutProfile(after, pageSize)
            // Asked once, and only when a session lacks one: where all have theirs, the first page is all it reads.
            val profile = legacy ?: page.firstOrNull()?.let { profiles.legacy() } ?: return given
            legacy = profile
            given += page.count { give(it, profile) }
            after = page.lastOrNull()?.id
        } while (page.size == pageSize)
        return given
    }

    // Another writer came first (an API call, a step): read again, unless it gave the profile itself.
    private suspend fun give(session: CloudSession, profile: SessionProfile): Boolean {
        var read: CloudSession? = session
        repeat(ATTEMPTS) {
            val current = read?.takeIf { it.profile == null } ?: return false
            val written = repository.compareAndSet(current.copy(profile = profile, updatedAt = clock.now()), null)
            if (written != null) return true
            read = repository.get(current.orgId, current.id)
        }
        // Still contended: its next step gives it, as before this pass.
        return false
    }

    private companion object {
        const val PAGE_SIZE = 200
        const val ATTEMPTS = 3
    }
}
