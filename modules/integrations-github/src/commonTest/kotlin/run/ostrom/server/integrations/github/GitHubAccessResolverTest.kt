// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github

import run.ostrom.server.integrations.github.domain.GITHUB_ACTOR
import run.ostrom.server.integrations.github.domain.GitHubAccess
import run.ostrom.server.integrations.github.domain.GitHubAccessResolver
import run.ostrom.server.integrations.github.domain.GitHubAppCredentials
import run.ostrom.server.integrations.github.domain.GitHubDeviceAuthorization
import run.ostrom.server.integrations.github.domain.GitHubLink
import run.ostrom.server.integrations.github.domain.GitHubLinkAttempt
import run.ostrom.server.integrations.github.domain.GitHubLinkKind
import run.ostrom.server.integrations.github.domain.GitHubLinks
import run.ostrom.server.integrations.github.domain.GitHubRefresh
import run.ostrom.server.integrations.github.domain.GitHubRevocable
import run.ostrom.server.integrations.github.domain.GitHubSavedLink
import run.ostrom.server.integrations.github.domain.GitHubTokenClaim
import run.ostrom.server.integrations.github.domain.GitHubUnreachableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class GitHubAccessResolverTest {
    private val clock = SettableClock()
    private val apps = SettableApps()
    private val store = MemoryGitHubLinkStore()
    private val github = FakeGitHubUserAuth()
    private val members = SettableMembers(mutableSetOf(ORG to DEV))
    private val audit = RecordingAudit()
    private val logger = RecordingLogger()
    private val resolver = GitHubAccessResolver(apps, store, github, members, audit, clock, logger)

    private val lines get() = logger.lines.map { it.second.substringAfter("outcome=") }

    private fun refresh(link: GitHubLink = octocatLink(expiresAt = NOW + 1.minutes)) =
        GitHubTokenClaim.Refresh(link, "ghu_old", "ghr_old", 3, NOW)

    private suspend fun access() = resolver.access(ORG, DEV)

    @Test
    fun aValidTokenIsHandedOutAsStored() = runTest {
        store.claims += GitHubTokenClaim.Ready(octocatLink(expiresAt = NOW + 8.hours), "ghu_stored")
        val linked = assertIs<GitHubAccess.Linked>(access())
        assertEquals("ghu_stored", linked.token)
        assertEquals("octocat" to 7L, linked.login to linked.userId)
        assertEquals(NOW + 8.hours, linked.expiresAt)
        assertEquals(emptyList(), logger.lines, "No refresh, no line")
    }

    @Test
    fun noAppNoMemberOrNoLinkIsNotLinked() = runTest {
        store.claims += GitHubTokenClaim.NotLinked
        assertEquals(GitHubAccess.NotLinked, access())
        assertEquals(GitHubAccess.NotLinked, resolver.access(ORG, OWNER), "Not a member: the store is not read")
        apps.app = GitHubAppCredentials()
        assertEquals(GitHubAccess.NotLinked, access())
        assertTrue(store.claims.isEmpty())
    }

    @Test
    fun aLinkThatEndsIsAuditedOnceWhenItEnds() = runTest {
        store.claims += GitHubTokenClaim.ReconnectRequired("refresh_expired")
        store.claims += GitHubTokenClaim.ReconnectRequired()
        assertEquals(GitHubAccess.NotLinked, access())
        assertEquals(GitHubAccess.NotLinked, access())
        val ended = audit.records.single()
        assertEquals(GitHubLinks.RECONNECT_REQUIRED to GITHUB_ACTOR, ended.event to ended.actor)
        assertEquals("refresh_expired", ended.details["reason"])
    }

    @Test
    fun aTokenAboutToExpireIsRefreshedAndSaved() = runTest {
        store.claims += refresh()
        store.completions += { true }
        val linked = assertIs<GitHubAccess.Linked>(access())
        assertEquals("ghu_refreshed", linked.token)
        assertEquals(listOf("ghr_old"), github.refreshed)
        assertEquals(listOf("refreshed"), lines)
        assertTrue(logger.lines.single().second.startsWith("Ostrom github refresh member=$DEV "))
        assertTrue("ghu_" !in logger.lines.single().second && "ghr_" !in logger.lines.single().second)
    }

    @Test
    fun aRefreshThatCannotBeSavedStillHandsOutTheNewToken() = runTest {
        store.claims += refresh()
        store.completions += { error("database down") }
        store.completions += { true }
        assertEquals("ghu_refreshed", assertIs<GitHubAccess.Linked>(access()).token)
        assertEquals(listOf("refreshed"), lines, "Saved at the second try")

        store.claims += refresh()
        repeat(3) { store.completions += { error("database down") } }
        assertEquals("ghu_refreshed", assertIs<GitHubAccess.Linked>(access()).token)
        assertEquals("refreshed_unsaved", lines.last())
        assertTrue(store.completions.isEmpty(), "Tried three times")
    }

    @Test
    fun aFencedRefreshRevokesItsTokenAndHandsOutNothing() = runTest {
        store.claims += refresh()
        store.completions += { false }
        assertEquals(GitHubAccess.Unavailable, access())
        assertEquals(listOf("ghu_refreshed"), github.revoked)
        assertEquals(listOf("refreshed_fenced"), lines)
    }

    @Test
    fun aCancelledSaveStaysCancelled() = runTest {
        store.claims += refresh()
        store.completions += { throw CancellationException("gone") }
        assertFailsWith<CancellationException> { access() }
    }

    @Test
    fun aRejectedRefreshTokenEndsTheLinkOnceForItsOwner() = runTest {
        github.refresh = { GitHubRefresh.Rejected }
        store.claims += refresh()
        assertEquals(GitHubAccess.NotLinked, access())
        assertEquals(listOf(true), store.abandoned)
        assertEquals("refresh_rejected", audit.records.single().details["reason"])
        assertEquals(listOf("rejected"), lines)

        // Fenced meanwhile: another caller already ended or replaced the link.
        store.abandonAnswer = false
        store.claims += refresh()
        assertEquals(GitHubAccess.NotLinked, access())
        assertEquals(1, audit.records.size)
    }

    @Test
    fun aFailedRefreshHandsOutTheOldTokenWhileItLastsFiveMinutes() = runTest {
        github.refresh = { throw GitHubUnreachableException() }
        store.claims += refresh(octocatLink(expiresAt = NOW + 30.minutes))
        assertEquals("ghu_old", assertIs<GitHubAccess.Linked>(access()).token)
        assertEquals(listOf(false), store.abandoned, "Released, the link kept")
        assertEquals(listOf("failed cause=github_unreachable"), lines)

        store.claims += refresh(octocatLink(expiresAt = NOW + 4.minutes))
        assertEquals(GitHubAccess.Unavailable, access())
        store.claims += refresh(octocatLink(expiresAt = null))
        assertEquals(GitHubAccess.Unavailable, access())
    }

    @Test
    fun theCauseOfAFailureIsItsKindNeverItsMessage() = runTest {
        github.refresh = { throw IllegalStateException("ghr_secret leaked in a message") }
        store.claims += refresh()
        assertEquals(GitHubAccess.Unavailable, access())
        github.refresh = { throw object : Exception("anonymous") {} }
        store.claims += refresh()
        assertEquals(GitHubAccess.Unavailable, access())
        github.refresh = {
            delay(1.hours)
            GitHubRefresh.Rejected
        }
        store.claims += refresh()
        assertEquals(GitHubAccess.Unavailable, access())
        assertEquals(
            listOf("failed cause=IllegalStateException", "failed cause=error", "failed cause=timeout"),
            lines,
        )
    }

    @Test
    fun aCancelledRefreshReleasesItsClaimAndStaysCancelled() = runTest {
        github.refresh = { throw CancellationException("gone") }
        store.claims += refresh()
        assertFailsWith<CancellationException> { access() }
        assertEquals(listOf(false), store.abandoned)
        assertEquals(listOf("cancelled"), lines)
    }

    @Test
    fun aCallerWaitsForAnotherReplicasRefreshThenGivesUp() = runTest {
        store.claims += GitHubTokenClaim.Wait
        store.claims += GitHubTokenClaim.Ready(octocatLink(expiresAt = NOW + 1.hours), "ghu_theirs")
        assertEquals("ghu_theirs", assertIs<GitHubAccess.Linked>(access()).token)

        val impatient =
            GitHubAccessResolver(apps, store, github, members, audit, clock, logger, maxWait = 150.milliseconds)
        repeat(3) { store.claims += GitHubTokenClaim.Wait }
        assertEquals(GitHubAccess.Unavailable, impatient.access(ORG, DEV))
        assertTrue(store.claims.isEmpty(), "Asked again after 100 ms, then gave up after 200")
    }

    @Test
    fun noTokenShowsInTheClaimsOrTheAccess() {
        val ready = GitHubTokenClaim.Ready(octocatLink(), "ghu_secret")
        assertEquals("Ready(octocat)", ready.toString())
        assertEquals("Refresh(octocat)", refresh().toString())
        val linked = GitHubAccess.Linked("ghu_secret", NOW, "octocat", 7)
        assertEquals("Linked(octocat, expiresAt=$NOW)", linked.toString())
        assertEquals("GitHubTokens(accessExpiresAt=$NOW, refreshExpiresAt=null)", tokens().toString())
        assertEquals("GitHubOAuthApp(clientId=$CLIENT_ID)", OAUTH_APP.toString())
        assertEquals("GitHubSavedLink(octocat)", GitHubSavedLink(octocatLink(), null).toString())
        assertEquals("GitHubRevocable(clientId=$CLIENT_ID)", GitHubRevocable(CLIENT_ID, "ghu_secret").toString())
        val attempt = GitHubLinkAttempt("a1", ORG, DEV, GitHubLinkKind.WEB, "verifier", "binding", 0, NOW, NOW)
        assertEquals("GitHubLinkAttempt(id=a1, memberId=$DEV, kind=WEB)", attempt.toString())
        val device = GitHubDeviceAuthorization("device-secret", "ABCD-1234", "https://github.com/login/device", 9, 5)
        assertEquals("GitHubDeviceAuthorization(userCode=ABCD-1234, uri=https://github.com/login/device)", "$device")
    }
}
