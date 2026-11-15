// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.runners.domain.AuthenticateRunnerUseCaseImpl
import run.ostrom.server.runners.domain.CheckRunnerTokenUseCaseImpl
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.ExecutionFencedException
import run.ostrom.server.runners.domain.PodTokenIdentity
import run.ostrom.server.runners.domain.PodTokenReviews
import run.ostrom.server.runners.domain.RunnerCredentialsImpl
import run.ostrom.server.runners.domain.RunnerExecution
import run.ostrom.server.runners.domain.RunnerPrincipalResolver
import run.ostrom.server.runners.domain.RunnerTokenPrincipalResolver
import run.ostrom.server.runners.domain.RunnerUnauthenticatedException
import run.ostrom.server.runners.domain.RuntimeNames
import run.ostrom.server.runners.domain.SessionProfile
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.FakeRunnerTokens
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** A runner proves its pod with a projected token its cluster reviews (TeamCode's `AuthenticateRunnerUseCase`). */
class RunnerCredentialsTest {
    private val repository = InMemoryCloudSessionsRepository()
    private val tokens = FakeRunnerTokens()
    private val clock = SettableClock()

    /** Reviews by token: the identity the cluster binds it to, null when it refuses it. */
    private class FakeReviews : PodTokenReviews {
        val bound = mutableMapOf<String, PodTokenIdentity>()
        val asked = mutableListOf<Triple<String, String, String>>()
        var failure: Exception? = null

        override suspend fun review(clusterId: String, token: String, audience: String): PodTokenIdentity? {
            asked += Triple(clusterId, token, audience)
            failure?.let { throw it }
            return bound[token]
        }
    }

    private class RecordingLogger : Logger {
        val events = mutableListOf<Triple<LogLevel, String, String>>()

        override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
            events += Triple(level, message, "$fields $error")
        }

        override fun with(fields: Map<String, Any?>): Logger = this
    }

    private val reviews = FakeReviews()
    private val logger = RecordingLogger()
    private val credentials = RunnerCredentialsImpl(repository, tokens, reviews, clock, logger)
    private val profile = SessionProfile(clusterId = "c1", namespace = "team", image = "img:1", workspaceGibibytes = 5)

    private fun running(epoch: Long = 3, configure: (CloudSession) -> CloudSession = { it }): CloudSession =
        configure(
            session(state = SessionState.RUNNING).copy(epoch = epoch, profile = profile, runnerTokenHash = "hash(r)"),
        ).also { repository.put(it) }

    /** A projected token whose payload names [pod]; [signature] tells two tokens of one pod apart. */
    private fun jwt(pod: String?, signature: String = "sig"): String {
        val claims = pod?.let { """{"kubernetes.io":{"namespace":"team","pod":{"name":"$it"}}}""" } ?: "{}"
        return jwt(claims.encodeToByteArray(), signature)
    }

    private fun jwt(payload: ByteArray, signature: String = "sig"): String {
        val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        return base64.encode("""{"alg":"RS256"}""".encodeToByteArray()) + "." + base64.encode(payload) + "." +
            base64.encode(signature.encodeToByteArray())
    }

    /** The token of [session]'s pod at [epoch], bound by its cluster to [namespace]. */
    private fun bound(session: CloudSession, epoch: Long = session.epoch, namespace: String = "team"): String {
        val pod = RuntimeNames.execution(session.id, epoch)
        return jwt(pod).also { reviews.bound[it] = PodTokenIdentity(namespace, pod) }
    }

    @Test
    fun theRunnerTokenStillWorksWithoutAReview() = runTest {
        val session = running()
        assertEquals(session, credentials.session("r"))
        assertNull(credentials.session("unknown"))
        assertEquals(session, RunnerCredentialsImpl(repository, tokens).session("r"))
        assertTrue(reviews.asked.isEmpty())
    }

    @Test
    fun theCurrentPodsTokenIsTheSessionReviewedInItsClusterForTheOstromAudience() = runTest {
        val session = running()
        val token = bound(session)
        assertEquals(session, credentials.session(token))
        assertEquals(listOf(Triple("c1", token, "ostrom")), reviews.asked)
        CheckRunnerTokenUseCaseImpl(credentials)(token)
    }

    @Test
    fun aPodOfTheSessionAtAnotherExecutionIsFenced() = runTest {
        val session = running(epoch = 3)
        assertFailsWith<ExecutionFencedException> { credentials.session(bound(session, epoch = 2)) }
    }

    @Test
    fun anotherNamespaceUnboundTokensAndAnotherSessionsPodAreUnauthenticated() = runTest {
        val session = running()
        assertNull(credentials.session(bound(session, namespace = "other")))
        // Bound to no pod.
        val unbound = jwt(RuntimeNames.execution(session.id, 3), "unbound")
        reviews.bound[unbound] = PodTokenIdentity("team", null)
        assertNull(credentials.session(unbound))
        // The claim names this session, the review another session's pod: the review decides.
        val forged = jwt(RuntimeNames.execution(session.id, 3), "forged")
        reviews.bound[forged] = PodTokenIdentity("team", RuntimeNames.execution(Uuid.random(), 3))
        assertNull(credentials.session(forged))
        // The cluster refuses it.
        assertNull(credentials.session(jwt(RuntimeNames.execution(session.id, 3), "refused")))
        assertFailsWith<RunnerUnauthenticatedException> {
            CheckRunnerTokenUseCaseImpl(credentials)(jwt(RuntimeNames.execution(session.id, 3), "refused"))
        }
    }

    @Test
    fun onlyTheCurrentExecutionOfAKubernetesSessionIsReviewed() = runTest {
        val stopped = running { it.copy(runnerTokenHash = null) }
        val docker = running { it.copy(profile = null) }
        val registered = running { it.copy(runnerId = Uuid.random()) }
        for (session in listOf(stopped, docker, registered)) assertNull(credentials.session(bound(session)))
        // A pod named after no session.
        assertNull(credentials.session(jwt(RuntimeNames.execution(Uuid.random(), 1))))
        assertTrue(reviews.asked.isEmpty())
    }

    @Test
    fun aMemberTokenOrAMalformedOneIsUnauthenticatedWithoutAReview() = runTest {
        running()
        val malformed = listOf(
            "ostrom_pat_0123456789abcdef",
            "a.b.c",
            "a".repeat(16_385) + ".b.c",
            "eyJhbGciOiJSUzI1NiJ9.a.sig",
            jwt("nope".encodeToByteArray()),
            jwt("[1]".encodeToByteArray()),
            jwt(null),
            jwt("""{"kubernetes.io":{}}""".encodeToByteArray()),
            jwt("""{"kubernetes.io":{"pod":{}}}""".encodeToByteArray()),
            jwt("""{"kubernetes.io":{"pod":{"name":3}}}""".encodeToByteArray()),
            jwt("other-pod"),
        )
        for (token in malformed) assertNull(credentials.session(token), token.take(40))
        assertTrue(reviews.asked.isEmpty())
    }

    @Test
    fun withoutPodIdentityAProjectedTokenIsRefusedWithoutAReview() = runTest {
        val session = running()
        val token = bound(session)
        assertNull(RunnerCredentialsImpl(repository, tokens).session(token))
        assertTrue(reviews.asked.isEmpty())
    }

    @Test
    fun aReviewIsCachedAMinuteAndARefusalTenSeconds() = runTest {
        val session = running()
        val token = bound(session)
        val refused = jwt(RuntimeNames.execution(session.id, 3), "refused")
        repeat(2) {
            assertEquals(session, credentials.session(token))
            assertNull(credentials.session(refused))
        }
        assertEquals(2, reviews.asked.size, "One review per token for two calls")

        clock.now += 11.seconds
        assertEquals(session, credentials.session(token))
        assertNull(credentials.session(refused))
        assertEquals(listOf(token, refused, refused), reviews.asked.map { it.second }, "The refusal is asked again")

        clock.now += 50.seconds
        assertEquals(session, credentials.session(token))
        assertEquals(token, reviews.asked.last().second, "The review is asked again after a minute")
        assertEquals(4, reviews.asked.size)
    }

    @Test
    fun aFullCacheEvictsItsOldestReview() = runTest {
        val session = running()
        val pod = RuntimeNames.execution(session.id, 3)
        val first = jwt(pod, "refused-0")
        repeat(RunnerCredentialsImpl.MAX_CACHED + 1) { assertNull(credentials.session(jwt(pod, "refused-$it"))) }
        val asked = reviews.asked.size
        assertNull(credentials.session(first))
        assertEquals(asked + 1, reviews.asked.size, "The oldest refusal was evicted")
    }

    @Test
    fun aFailedReviewIsARefusalCachedAndLoggedOncePerPeriodWithoutTheToken() = runTest {
        val session = running()
        val token = bound(session)
        val other = jwt(RuntimeNames.execution(session.id, 3), "other")
        reviews.failure = IllegalStateException("Kubernetes API: review token answered 403.")
        assertNull(credentials.session(token))
        val (level, message, details) = logger.events.single()
        assertEquals(LogLevel.WARN, level)
        assertEquals("pod token review failed", message)
        assertTrue("c1" in details && "403" in details)
        assertFalse(token in details)

        // Cached as a refusal: the same token does not reach the cluster again; another is reviewed, not logged.
        assertNull(credentials.session(token))
        assertNull(credentials.session(other))
        assertEquals(listOf(token, other), reviews.asked.map { it.second })
        assertEquals(1, logger.events.size)

        // Past the refusal period the token is reviewed again, and a failure is logged again.
        clock.now += 11.seconds
        assertNull(credentials.session(token))
        assertEquals(3, reviews.asked.size)
        assertEquals(2, logger.events.size)
        assertNull(RunnerCredentialsImpl(repository, tokens, reviews, clock).session(token), "Without a logger")

        reviews.failure = null
        clock.now += 11.seconds
        assertEquals(session, credentials.session(token))

        reviews.failure = CancellationException("stopped")
        val cancelled = jwt(RuntimeNames.execution(session.id, 3), "cancelled")
        assertFailsWith<CancellationException> { credentials.session(cancelled) }
    }

    @Test
    fun aPodTokenOpensTheRunnerTerminalAndAStaleOneLeavesTheNextResolversAsked() = runTest {
        val session = running(epoch = 3)
        val resolver = RunnerTokenPrincipalResolver(credentials)
        val authenticate = AuthenticateRunnerUseCaseImpl(listOf(resolver), repository)
        val token = bound(session)
        assertEquals(session, authenticate(token, session.id, 3))
        assertEquals(RunnerExecution(session.orgId, session.id, 3), authenticate.resolve(token))

        val stale = bound(session, epoch = 2)
        assertNull(resolver.resolve(stale), "Another execution's pod is not this resolver's credential")
        assertFailsWith<ExecutionFencedException> { authenticate(stale, session.id, 2) }
        val device = RunnerExecution(session.orgId, Uuid.random(), 1)
        val next = RunnerPrincipalResolver { credential -> device.takeIf { credential == stale } }
        assertEquals(device, AuthenticateRunnerUseCaseImpl(listOf(resolver, next), repository).resolve(stale))
    }
}
