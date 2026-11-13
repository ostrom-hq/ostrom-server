// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.domain

import run.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Keeps an organization's directory (repositories and teams of its installation, `sync.ts` of the prototype) close to
 * GitHub without calling GitHub on every list: a list reads the stored rows. The first list after an install (or
 * ever) reads GitHub before answering, once for simultaneous lists and not again for 30 seconds after a failure
 * (AUDIT-SECU-2 B4), so the console never shows an empty list of a connected organization; after
 * that, a list older than [FRESH_FOR] answers what is stored and queues one read of GitHub, run by [run] in the
 * background and won by one server only ([GitHubDirectoryRepository.claim]). A read that fails keeps what is stored.
 *
 * @param installations The installations.
 * @param directory The stored directory.
 * @param api GitHub.
 * @param clock The clock.
 * @param logger Logs GitHub failing, never a body.
 */
class GitHubDirectorySync(
    private val installations: GitHubRepository,
    private val directory: GitHubDirectoryRepository,
    private val api: GitHubDirectoryApi,
    private val clock: Clock,
    private val logger: Logger,
) {
    // Bounded: a burst of lists of many organizations drops the extra reads, the next list queues them again.
    private val queue = Channel<GitHubInstallation>(QUEUE_SIZE, BufferOverflow.DROP_LATEST)

    // The first read of each organization, one at a time on this server (AUDIT-SECU-2 B4), and when it last failed.
    private val guard = Mutex()
    private val firstReads = mutableMapOf<Uuid, Mutex>()
    private val failedAt = mutableMapOf<Uuid, Instant>()

    /**
     * Makes the organization's directory listable.
     *
     * @param orgId The organization.
     * @return False when the organization has no installation (nothing to list).
     * @throws GitHubUnavailableException When nothing is stored yet and GitHub does not answer.
     */
    suspend fun ensure(orgId: Uuid): Boolean {
        val installation = installations.installation(orgId) ?: return false
        val now = clock.now()
        val state = directory.state(orgId)
        if (state?.syncedAt == null) {
            firstRead(installation)
        } else if (state.checkedAt < now - FRESH_FOR && directory.claim(orgId, now, now - FRESH_FOR)) {
            queue.trySend(installation)
        }
        return true
    }

    // Simultaneous first lists of a new organization read GitHub once: the others wait, then find it stored. After a
    // failure, GitHub is not asked again for [RETRY_AFTER]: a console that keeps reloading does not spend the quota.
    private suspend fun firstRead(installation: GitHubInstallation) {
        val orgId = installation.orgId
        val lock = guard.withLock { firstReads.getOrPut(orgId) { Mutex() } }
        lock.withLock {
            if (directory.state(orgId)?.syncedAt != null) return
            val failed = guard.withLock { failedAt[orgId] }
            if (failed != null && clock.now() < failed + RETRY_AFTER) throw GitHubUnavailableException()
            directory.replace(orgId, readOrRest(installation), clock.now())
            guard.withLock {
                failedAt.remove(orgId)
                firstReads.remove(orgId)
            }
        }
    }

    // GitHub failing on a first read: logged, an unavailable answer, and the organization rests [RETRY_AFTER].
    private suspend fun readOrRest(installation: GitHubInstallation): GitHubDirectory = try {
        read(installation)
    } catch (error: CancellationException) {
        throw error
    } catch (error: GitHubCallException) {
        logger.warn("github directory not read", mapOf("installation" to installation.installationId), error)
        guard.withLock { failedAt[installation.orgId] = clock.now() }
        throw GitHubUnavailableException()
    }

    /**
     * Forgets the stored read of a new installation: the next list reads GitHub again.
     *
     * @param installation The installation just linked.
     */
    suspend fun installed(installation: GitHubInstallation) {
        directory.forget(installation.orgId)
    }

    /** Runs the queued reads until [stop]; started once per server by the module. */
    suspend fun run() {
        for (installation in queue) refresh(installation)
    }

    /** Ends [run] once the queued reads are done. */
    fun stop() {
        queue.close()
    }

    private suspend fun refresh(installation: GitHubInstallation) {
        try {
            directory.replace(installation.orgId, read(installation), clock.now())
        } catch (error: CancellationException) {
            throw error
        } catch (error: GitHubCallException) {
            logger.warn("github directory not refreshed", mapOf("installation" to installation.installationId), error)
        }
    }

    private suspend fun read(installation: GitHubInstallation): GitHubDirectory {
        val teams = api.teams(installation.installationId, installation.account)
        val repos = api.repositories(installation.installationId).map { repo ->
            repo.copy(owners = teams.filter { repo.name in it.repos }.map { it.slug }.sorted())
        }
        return GitHubDirectory(repos, teams)
    }

    private companion object {
        const val QUEUE_SIZE = 64
    }
}

// Top-level, not in the private companion: a companion property has a getter nothing calls, which Kover counts.
private val FRESH_FOR = 10.minutes
private val RETRY_AFTER = 30.seconds
