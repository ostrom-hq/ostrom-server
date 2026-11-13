// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.runner

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** A clock the test moves. */
class MovingClock(var now: Instant = Instant.parse("2026-11-13T10:00:00Z")) : Clock {
    override fun now(): Instant = now
}

class GitHubCredentialsTest {
    private val clock = MovingClock()
    private val answers = ArrayDeque<() -> GitHubToken?>()
    private var asked = 0
    private val source = GitHubCredentialSource({
        asked++
        answers.removeFirst()()
    }, clock)
    private val github = CredentialRequest("https", "github.com", "acme/api.git")

    @Test
    fun githubOverHttpsGetsTheOwnersTokenAskedOnceUntilItIsDue() = runTest {
        val expiry = clock.now + 1.hours
        answers += { GitHubToken("ghu_one", "octocat", expiry) }
        assertEquals(clock.now, source.renewsAt(), "Before the first call")
        assertEquals(Credential("x-access-token", "ghu_one"), source.credential(github))
        clock.now += 10.minutes
        assertEquals(Credential("x-access-token", "ghu_one"), source.credential(github), "Not asked again")
        assertEquals(1, asked)
        assertEquals(expiry - 5.minutes, source.renewsAt())

        clock.now = expiry - 4.minutes
        answers += { GitHubToken("ghu_two", null, null) }
        assertEquals("ghu_two", source.credential(github)?.password)
        assertEquals(clock.now + 5.minutes, source.renewsAt(), "An unknown expiry is asked for again soon")
        assertEquals("Credential(username=x-access-token)", source.credential(github).toString())
        val shown = GitHubToken("ghu_x", "octocat", expiry).toString()
        assertEquals("GitHubToken(login=octocat, expiresAt=$expiry)", shown)
    }

    @Test
    fun anotherHostOrProtocolNeverGetsTheToken() = runTest {
        assertNull(source.credential(CredentialRequest("https", "gitlab.com")))
        assertNull(source.credential(CredentialRequest("http", "github.com")))
        assertNull(source.credential(CredentialRequest()))
        assertEquals(0, asked)
    }

    @Test
    fun withoutALinkTheServerIsAskedAgainOnlyAfterAWhile() = runTest {
        answers += { null }
        assertNull(source.credential(github))
        assertNull(source.credential(github))
        assertEquals(1, asked)
        assertEquals(clock.now + 30.seconds, source.renewsAt())

        // A token about to expire is not asked for again at every call either.
        clock.now += 30.seconds
        answers += { GitHubToken("ghu_short", "octocat", clock.now + 1.minutes) }
        assertEquals("ghu_short", source.token()?.token)
        assertEquals(clock.now + 30.seconds, source.renewsAt())
    }

    @Test
    fun aFailedCallLeavesTheScheduleAsItWas() = runTest {
        answers += { error("server down") }
        assertFailsWith<IllegalStateException> { source.credential(github) }
        answers += { GitHubToken("ghu_one", "octocat", null) }
        assertEquals("ghu_one", source.token()?.token, "Asked again at once")
    }
}
