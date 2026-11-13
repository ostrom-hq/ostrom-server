// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github

import run.ostrom.server.integrations.github.domain.GitHubAccessResolver
import run.ostrom.server.integrations.github.domain.GitHubAppCredentials
import run.ostrom.server.integrations.github.domain.GitHubTokenClaim
import run.ostrom.server.integrations.github.infrastructure.LinkedRunnerGitHubTokens
import run.ostrom.server.runners.domain.RunnerGitHubToken
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class LinkedRunnerGitHubTokensTest {
    private val apps = SettableApps()
    private val store = MemoryGitHubLinkStore()
    private val github = FakeGitHubUserAuth()
    private val members = SettableMembers(mutableSetOf(ORG to DEV))
    private val resolver =
        GitHubAccessResolver(apps, store, github, members, RecordingAudit(), SettableClock(), RecordingLogger())
    private val tokens = LinkedRunnerGitHubTokens(apps, resolver)

    @Test
    fun theOwnersLinkGivesTheRunnerItsToken() = runTest {
        store.claims += GitHubTokenClaim.Ready(octocatLink(expiresAt = NOW + 8.hours), "ghu_stored")
        val linked = assertIs<RunnerGitHubToken.Linked>(tokens.token(ORG, DEV.toString()))
        val handed = Triple(linked.token, linked.expiresAt, linked.login)
        assertEquals(Triple("ghu_stored", NOW + 8.hours, "octocat"), handed)
    }

    @Test
    fun noLinkAnOwnerThatIsNotAMemberOrGitHubDownGiveNoToken() = runTest {
        store.claims += GitHubTokenClaim.NotLinked
        assertEquals(RunnerGitHubToken.NotLinked, tokens.token(ORG, DEV.toString()))
        assertEquals(RunnerGitHubToken.NotLinked, tokens.token(ORG, "service-account"), "Not a member id")
        assertTrue(store.claims.isEmpty())

        store.claims += GitHubTokenClaim.Refresh(octocatLink(expiresAt = NOW + 1.minutes), "ghu_old", "ghr_old", 3, NOW)
        store.completions += { false }
        assertEquals(RunnerGitHubToken.Unavailable, tokens.token(ORG, DEV.toString()))
    }

    @Test
    fun tokensAreAvailableOnlyWithTheAppsOAuthClient() = runTest {
        assertTrue(tokens.available())
        apps.app = GitHubAppCredentials()
        assertFalse(tokens.available())
    }
}
