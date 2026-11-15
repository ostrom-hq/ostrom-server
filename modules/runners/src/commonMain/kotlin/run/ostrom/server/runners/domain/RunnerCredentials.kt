// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The pod a projected service-account token is bound to, as its cluster's TokenReview says (TeamCode's
 * `TokenIdentity`).
 *
 * @property namespace The service account's namespace, the pod's.
 * @property podName The pod the token is bound to, null for a token bound to no pod.
 */
data class PodTokenIdentity(val namespace: String, val podName: String?)

/** Reviews a projected service-account token in a cluster (TeamCode's `ClusterPort.reviewToken`). */
interface PodTokenReviews {
    /**
     * Reviews a token.
     *
     * @param clusterId The cluster that should have issued it.
     * @param token The token, as presented.
     * @param audience The audience it must carry.
     *
     * @return Who it is bound to, or null when the cluster does not authenticate it for [audience].
     *
     * @throws Exception When the review cannot be made (the cluster unreachable, the review forbidden).
     */
    suspend fun review(clusterId: String, token: String, audience: String): PodTokenIdentity?
}

/**
 * The one place a runner call's bearer credential becomes the session of its current execution, for every runner
 * channel (heartbeat, preparation, variables, GitHub, terminal, the other modules' runner routes).
 */
interface RunnerCredentials {
    /**
     * The session whose current execution presents [token].
     *
     * @param token The bearer credential: the execution's runner token, or its pod's projected token.
     *
     * @return The session, or null when the credential names no current execution.
     *
     * @throws ExecutionFencedException When the token is a pod's of the session at another execution.
     */
    suspend fun session(token: String): CloudSession?
}

/**
 * [RunnerCredentials] on the runner tokens and, where [reviews] is given (`OSTROM_RUNNERS_KUBERNETES_POD_IDENTITY`),
 * the pods' projected service-account tokens, as TeamCode's `AuthenticateRunnerUseCase`.
 *
 * A runner token is looked up by its hash, as always. A pod token (a JWT) is decoded without being trusted, only to
 * find the session its pod is named after; that session's cluster then reviews it (TokenReview, audience
 * [AUDIENCE]), and the namespace and pod name bound into it must be the session's namespace and the pod of its current
 * execution. The pod name that decides always comes from the review. A pod of the session at another execution is
 * fenced; anything else is unauthenticated, without saying why.
 *
 * Reviews are cached for at most [CACHE_SECONDS] per token and cluster, so a heartbeat every 15 seconds costs one
 * review a minute; refusals for [REFUSAL_CACHE_SECONDS], in a separate cache, so bad tokens never evict a runner's.
 *
 * @param repository The sessions.
 * @param tokens Hashes the presented token.
 * @param reviews The clusters' TokenReview, null when pod tokens are refused.
 * @param clock The time.
 * @param logger Where a failed review is reported, without the token.
 */
class RunnerCredentialsImpl(
    private val repository: CloudSessionsRepository,
    private val tokens: RunnerTokens,
    private val reviews: PodTokenReviews? = null,
    private val clock: Clock = Clock.System,
    private val logger: Logger? = null,
) : RunnerCredentials {
    private class Cached(val identity: PodTokenIdentity?, val until: Instant)

    // Accepted and refused reviews are kept apart, so a flood of bad tokens can never evict a runner's review.
    private val accepted = LinkedHashMap<String, Cached>()
    private val refused = LinkedHashMap<String, Cached>()
    private val lock = Mutex()

    // Every check that fails refuses the call the same way, without saying which.
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    override suspend fun session(token: String): CloudSession? {
        val reviews = reviews
        if (reviews == null || !looksLikeJwt(token)) return repository.findByRunnerTokenHash(tokens.hash(token))
        val sessionId = claimedSession(token) ?: return null
        val session = repository.getById(sessionId) ?: return null
        val profile = session.profile
        // A Kubernetes session, at an execution that is current (the runner token's condition).
        if (profile == null || session.runnerId != null || session.runnerTokenHash == null) return null
        val identity = review(reviews, profile.clusterId, token) ?: return null
        val pod = identity.podName ?: return null
        val prefix = RuntimeNames.execution(session.id, 0).removeSuffix("0")
        if (identity.namespace != profile.namespace || !pod.startsWith(prefix)) return null
        if (pod != RuntimeNames.execution(session.id, session.epoch)) throw ExecutionFencedException()
        return session
    }

    @Suppress("ReturnCount") // A cached answer, a failed review, or the review.
    private suspend fun review(reviews: PodTokenReviews, clusterId: String, token: String): PodTokenIdentity? {
        val key = clusterId + ":" + tokens.hash(token)
        val now = clock.now()
        lock.withLock { (accepted[key] ?: refused[key])?.takeIf { it.until > now }?.let { return it.identity } }
        val identity = try {
            reviews.review(clusterId, token, AUDIENCE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Unreachable, forbidden (no system:auth-delegator) or unreadable: this call is refused, the next one
            // asks again.
            logger?.warn("pod token review failed", mapOf("cluster" to clusterId), failure)
            return null
        }
        lock.withLock {
            // Refusals are cached briefly too, so a repeated bad token is not a cluster call every time.
            val cache = if (identity == null) refused else accepted
            cache.entries.removeAll { it.value.until <= now }
            if (cache.size >= MAX_CACHED) cache.remove(cache.keys.first())
            val lifetime = if (identity == null) REFUSAL_CACHE_SECONDS else CACHE_SECONDS
            cache[key] = Cached(identity, now + lifetime.seconds)
        }
        return identity
    }

    /** Constants and readers of the enclosing type. */
    companion object {
        /** The audience of the pods' projected tokens. */
        const val AUDIENCE: String = "ostrom"

        /** How long an accepted review is kept. */
        const val CACHE_SECONDS: Int = 60

        /** How long a refused review is kept. */
        const val REFUSAL_CACHE_SECONDS: Int = 10

        /** Reviews kept per cache. */
        const val MAX_CACHED: Int = 4_096

        private const val MIN_LENGTH = 16
        private const val MAX_LENGTH = 16_384
        private val JWT = Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
        private val POD = Regex("ostrom-s-([0-9a-f]{32})-e[0-9]+")
        private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

        private fun looksLikeJwt(token: String): Boolean = token.length in MIN_LENGTH..MAX_LENGTH && JWT.matches(token)

        /** The session the token's (unverified) pod name claim names, only to know which cluster to ask. */
        @Suppress("ReturnCount") // A payload that is not a pod's names no session.
        private fun claimedSession(token: String): Uuid? {
            val payload = try {
                Json.parseToJsonElement(base64.decode(token.split('.')[1]).decodeToString()) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            } ?: return null
            val kubernetes = payload["kubernetes.io"] as? JsonObject
            val pod = (kubernetes?.get("pod") as? JsonObject)?.get("name") as? JsonPrimitive
            val name = pod?.takeIf { it.isString } ?: return null
            val match = POD.matchEntire(name.content) ?: return null
            return Uuid.parseHex(match.groupValues[1])
        }
    }
}
