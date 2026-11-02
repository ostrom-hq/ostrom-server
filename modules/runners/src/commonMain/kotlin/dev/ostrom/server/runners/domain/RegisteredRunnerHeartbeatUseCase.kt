// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.domain

import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * A registered runner's sign of life (the contract's `runnerHeartbeat`), authenticated by its key: it marks the runner
 * seen, which shows it `online` for [ReconcileCloudSessionUseCaseImpl.HEARTBEAT_LOSS], and returns the work assigned to
 * it.
 */
interface RegisteredRunnerHeartbeatUseCase {
    /**
     * Records it.
     *
     * @param id The runner the request names.
     * @param key The key it presented.
     *
     * @return The cloud sessions assigned to it: none until sessions are scheduled on registered runners.
     *
     * @throws RunnerUnauthenticatedException When no runner has that id or the key is not its own; both answer the
     *   same, so neither the runner's existence nor its organization leaks.
     */
    suspend operator fun invoke(id: Uuid, key: String): List<CloudSession>
}

/**
 * [RegisteredRunnerHeartbeatUseCase] on the repository. The key is hashed and compared in constant time whether the
 * runner exists or not (against a hash no key has when it does not), so an unknown runner and a wrong key cost the same
 * single read; the sign of life is then written only if the runner still has that key in its organization.
 *
 * @param repository The runners.
 * @param tokens Hashes the key.
 * @param clock The time.
 */
class RegisteredRunnerHeartbeatUseCaseImpl(
    private val repository: RegisteredRunnersRepository,
    private val tokens: RunnerTokens,
    private val clock: Clock,
) : RegisteredRunnerHeartbeatUseCase {
    override suspend fun invoke(id: Uuid, key: String): List<CloudSession> {
        val hash = tokens.hash(key)
        val stored = repository.keyOf(id)
        val matches = constantTimeEquals(hash, if (stored == null) NO_KEY_HASH else stored.keyHash)
        if (stored == null || !matches || !repository.touch(stored.orgId, id, hash, clock.now())) {
            throw RunnerUnauthenticatedException()
        }
        return emptyList()
    }
}

/** Compared with when no runner has the id: a SHA-256 hex digest no key hashes to in practice. */
private const val NO_KEY_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

/**
 * Compares two strings in a time that depends on their length only, never on where they first differ (the lengths of
 * two SHA-256 hex digests are public).
 *
 * @param a One string.
 * @param b The other.
 *
 * @return True when they are equal.
 */
internal fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
    return diff == 0
}
