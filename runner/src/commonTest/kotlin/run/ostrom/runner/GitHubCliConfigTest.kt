// SPDX-License-Identifier: Apache-2.0
package run.ostrom.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class GitHubCliConfigTest {
    private val directory = Path(SystemTemporaryDirectory, "ostrom-gh-${Uuid.random()}").toString()
    private val config = GitHubCliConfig(directory)
    private val clock = MovingClock()
    private val answers = ArrayDeque<() -> GitHubToken?>()
    private val source = GitHubCredentialSource({ answers.removeFirst()() }, clock)
    private val logs = mutableListOf<String>()
    private val renewal = GitHubCliRenewal(source, config, clock, { logs += it })

    private fun read(path: Path) = SystemFileSystem.source(path).buffered().use { it.readString() }

    private fun exists(path: Path) = SystemFileSystem.exists(path)

    @Test
    fun theOwnersTokenIsWrittenForGhOrTheNoticeInItsPlace() {
        assertTrue(config.write(GitHubToken("ghu_one", "octocat", null)))
        assertEquals(
            """
            github.com:
                users:
                    "octocat":
                        oauth_token: ghu_one
                git_protocol: https
                oauth_token: ghu_one
                user: "octocat"

            """.trimIndent(),
            read(config.hosts),
        )
        assertFalse(exists(Path(directory, "hosts.yml.tmp")))

        assertTrue(config.write(null))
        assertFalse(exists(config.hosts))
        assertEquals(GitHubCliConfig.NOTICE, read(config.notice))
        assertFalse("ghu_" in GitHubCliConfig.NOTICE)

        assertTrue(config.write(GitHubToken("ghu_two", "not a login", null)))
        assertEquals("github.com:\n    git_protocol: https\n    oauth_token: ghu_two\n", read(config.hosts))
        assertFalse(exists(config.notice))
        assertEquals(read(config.hosts), GitHubCliConfig.hostsYaml(GitHubToken("ghu_two", null, null)))

        assertFalse(config.write(GitHubToken("ghu_x\nuser: evil", "octocat", null)), "Not GitHub's alphabet")
        assertFalse(exists(config.hosts) || exists(config.notice))

        config.write(GitHubToken("ghu_three", "octocat", null))
        config.clear()
        assertFalse(exists(config.hosts) || exists(config.notice))
    }

    @Test
    fun theRenewalFollowsTheTokenAndSaysOnceWhenThereIsNone() = runTest {
        answers += { GitHubToken("ghu_one", "octocat", clock.now + 1.hours) }
        assertEquals(55.minutes, renewal.refresh())
        assertTrue("ghu_one" in read(config.hosts))

        clock.now += 55.minutes
        answers += { null }
        assertEquals(5.minutes, renewal.refresh())
        answers += { null }
        clock.now += 5.minutes
        assertEquals(5.minutes, renewal.refresh())
        assertEquals(listOf("the session owner has no usable GitHub link: gh and git are signed out"), logs)
        assertTrue(exists(config.notice))

        clock.now += 5.minutes
        answers += { GitHubToken("bad token", "octocat", clock.now + 6.minutes) }
        assertEquals(5.minutes, renewal.refresh())
        clock.now += 5.minutes
        answers += { GitHubToken("bad token", "octocat", clock.now + 6.minutes) }
        assertEquals(5.minutes, renewal.refresh())
        assertEquals("the GitHub token was not written to the gh configuration: unexpected format", logs.last())
        assertEquals(2, logs.size, "Once per streak")

        // A token that expires soon is renewed no sooner than the retry.
        clock.now += 5.minutes
        answers += { GitHubToken("ghu_two", "octocat", clock.now + 1.minutes) }
        assertEquals(30.seconds, renewal.refresh())
        assertTrue("ghu_two" in read(config.hosts))
    }

    @Test
    fun aFailureKeepsTheTokenUntilItExpiresThenRemovesIt() = runTest {
        answers += { GitHubToken("ghu_one", "octocat", clock.now + 10.minutes) }
        renewal.refresh()
        clock.now += 5.minutes
        answers += { error("server down") }
        assertEquals(30.seconds, renewal.refresh())
        assertTrue(exists(config.hosts), "Still valid")
        answers += { error("server down") }
        clock.now += 5.minutes
        assertEquals(5.minutes, renewal.refresh())
        assertFalse(exists(config.hosts), "Expired: removed")
        clock.now += 30.seconds
        answers += { error("server down") }
        assertEquals(5.minutes, renewal.refresh())
        assertEquals(listOf("the gh configuration could not be renewed"), logs)

        // A failure before anything was written removes nothing.
        val fresh = GitHubCliRenewal(source, config, clock, { logs += it })
        clock.now += 30.seconds
        answers += { error("server down") }
        assertEquals(30.seconds, fresh.refresh())
    }

    @Test
    fun aTokenWithoutExpiryStaysThroughFailures() = runTest {
        answers += { GitHubToken("ghu_one", "octocat", null) }
        renewal.refresh()
        clock.now += 5.minutes
        answers += { error("server down") }
        renewal.refresh()
        assertTrue(exists(config.hosts))
    }

    @Test
    fun theRenewalRunsUntilCancelledThenRemovesTheFile() = runTest {
        val waits = mutableListOf<Duration>()
        val running = GitHubCliRenewal(source, config, clock, { logs += it }, sleep = { wait ->
            waits += wait
            if (waits.size == 3) throw CancellationException("stop")
        })
        answers += { GitHubToken("ghu_one", "octocat", null) }
        assertFailsWith<CancellationException> { running.run(1.minutes) }
        assertEquals(listOf(1.minutes, 5.minutes, 5.minutes), waits, "Asked once: kept for five minutes")
        assertFalse(exists(config.hosts))

        answers += { throw CancellationException("stop") }
        clock.now += 1.hours
        assertFailsWith<CancellationException> { running.refresh() }
    }

    @Test
    fun aFileThatCannotBeRemovedIsLogged() = runTest {
        // A directory in the file's place: gh reads nothing, the runner cannot remove it.
        SystemFileSystem.createDirectories(config.hosts)
        SystemFileSystem.sink(Path(config.hosts, "x")).buffered().use { it.writeString("x") }
        val running = GitHubCliRenewal(source, config, clock, { logs += it }, sleep = { throw CancellationException() })
        assertFailsWith<CancellationException> { running.run(1.minutes) }
        assertEquals(listOf("the gh configuration could not be removed"), logs)
    }

    @Test
    fun theRenewalWaitsWithTheCoroutinesClockByDefault() = runTest {
        val running = launch { renewal.run(1.minutes) }
        runCurrent()
        assertTrue(running.isActive, "Waiting")
        running.cancelAndJoin()
    }
}
