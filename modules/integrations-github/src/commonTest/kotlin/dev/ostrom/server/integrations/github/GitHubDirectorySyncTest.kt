// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github

import dev.ostrom.server.integrations.github.domain.DirectoryState
import dev.ostrom.server.integrations.github.domain.GitHubDirectorySync
import dev.ostrom.server.integrations.github.domain.GitHubInstallation
import dev.ostrom.server.integrations.github.domain.GitHubTeam
import dev.ostrom.server.integrations.github.domain.GitHubUnavailableException
import dev.ostrom.server.integrations.github.domain.InvalidPageException
import dev.ostrom.server.integrations.github.domain.ListGitHubReposUseCaseImpl
import dev.ostrom.server.integrations.github.domain.ListGitHubTeamsUseCaseImpl
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.logging.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class GitHubDirectorySyncTest {
    private val clock = SettableClock()
    private val logger = RecordingLogger()
    private val installations = MemoryGitHubRepository()
    private val directory = MemoryGitHubDirectoryRepository()
    private val api = FakeGitHubDirectoryApi().apply {
        repos[42] = listOf(repo("web"), repo("api"), repo("old", archived = true))
        teams[42] = listOf(
            GitHubTeam("platform", "Platform", listOf("dev", "lead"), listOf("api", "web")),
            GitHubTeam("backend", "Backend", listOf("dev"), listOf("api")),
        )
    }
    private val sync = GitHubDirectorySync(installations, directory, api, clock, logger)
    private val repos = ListGitHubReposUseCaseImpl(sync, directory)
    private val teams = ListGitHubTeamsUseCaseImpl(sync, directory)

    private suspend fun installed() = installations.saveInstallation(GitHubInstallation(ORG, 42, "acme"), NOW)

    @Test
    fun anOrganizationWithoutAnInstallationListsNothingAndCallsNoOne() = runTest {
        assertEquals(emptyList(), repos(ORG, null, null).items)
        assertEquals(emptyList(), teams(ORG, null, null).items)
        assertEquals(0, api.reads)
        // No page was ever given: a cursor is refused.
        assertFailsWith<dev.ostrom.server.kernel.errors.InvalidPageException> { repos(ORG, "api", null) }
        assertFailsWith<dev.ostrom.server.kernel.errors.InvalidPageException> { teams(ORG, "backend", null) }
    }

    @Test
    fun theFirstListReadsGitHubThenTheStoredRowsWithTheTeamsThatOwnEachRepository() = runTest {
        installed()
        val first = repos(ORG, null, null)
        assertEquals(listOf("api", "old", "web"), first.items.map { it.name })
        val owners = listOf(listOf("backend", "platform"), emptyList(), listOf("platform"))
        assertEquals(owners, first.items.map { it.owners })
        assertNull(first.next)
        assertEquals(listOf("backend", "platform"), teams(ORG, null, null).items.map { it.slug })
        assertEquals(1, api.reads, "The second list is served from the database")
        // After the last row, nothing; after a name the directory does not hold, a 400.
        assertEquals(emptyList(), repos(ORG, "web", null).items)
        assertEquals(emptyList(), teams(ORG, "platform", null).items)
        assertFailsWith<dev.ostrom.server.kernel.errors.InvalidPageException> { repos(ORG, "zzz", null) }
        assertFailsWith<dev.ostrom.server.kernel.errors.InvalidPageException> { teams(ORG, "zzz", null) }
    }

    @Test
    fun aStaleListAnswersWhatIsStoredAndQueuesOneReadForTheBackground() = runTest {
        installed()
        repos(ORG, null, null)
        api.repos[42] = listOf(repo("new"))
        clock.now += 11.minutes
        assertEquals(listOf("api", "old", "web"), repos(ORG, null, null).items.map { it.name }, "Not waited for")
        assertEquals(listOf("api", "old", "web"), repos(ORG, null, null).items.map { it.name }, "Claimed once")
        val worker = launch { sync.run() }
        sync.stop()
        worker.join()
        assertEquals(2, api.reads)
        assertEquals(listOf("new"), repos(ORG, null, null).items.map { it.name })
    }

    @Test
    fun aBackgroundReadThatFailsKeepsWhatIsStoredAndIsLogged() = runTest {
        installed()
        repos(ORG, null, null)
        clock.now += 11.minutes
        repos(ORG, null, null)
        api.failure = gitHubDown()
        val worker = launch { sync.run() }
        sync.stop()
        worker.join()
        assertEquals(listOf(LogLevel.WARN to "github directory not refreshed"), logger.lines)
        assertEquals(3, repos(ORG, null, null).items.size)
    }

    @Test
    fun withNothingStoredGitHubFailingIsAnUnavailableAnswer() = runTest {
        installed()
        api.failure = gitHubDown()
        val error = assertFailsWith<GitHubUnavailableException> { repos(ORG, null, null) }
        assertEquals(ErrorKind.UNAVAILABLE, error.kind)
        assertEquals(listOf(LogLevel.WARN to "github directory not read"), logger.lines)
    }

    @Test
    fun simultaneousFirstListsReadGitHubOnce() = runTest {
        installed()
        val pause = CompletableDeferred<Unit>()
        api.pause = pause
        val first = async { sync.ensure(ORG) }
        val second = async { sync.ensure(ORG) }
        testScheduler.runCurrent()
        pause.complete(Unit)
        assertTrue(first.await())
        assertTrue(second.await(), "Found stored once the first read ended")
        assertEquals(1, api.reads)
    }

    @Test
    fun afterAFailedFirstReadGitHubRestsThirtySeconds() = runTest {
        installed()
        api.failure = gitHubDown()
        assertFailsWith<GitHubUnavailableException> { sync.ensure(ORG) }
        api.failure = null
        assertFailsWith<GitHubUnavailableException>("Not asked again at once") { sync.ensure(ORG) }
        assertEquals(0, api.reads)
        clock.now += 31.seconds
        assertTrue(sync.ensure(ORG))
        assertEquals(1, api.reads)
        clock.now += 1.seconds
        installations.saveInstallation(GitHubInstallation(OTHER_ORG, 43, "globex"), NOW)
        assertTrue(sync.ensure(OTHER_ORG), "Another organization never waits for this one's failure")
    }

    @Test
    fun aCancellationIsNeverTurnedIntoAnAnswer() = runTest {
        installed()
        api.failure = CancellationException("stopped")
        assertFailsWith<CancellationException> { repos(ORG, null, null) }
        api.failure = null
        repos(ORG, null, null)
        clock.now += 11.minutes
        repos(ORG, null, null)
        api.failure = CancellationException("stopped")
        assertFailsWith<CancellationException> { sync.run() }
        assertTrue(logger.lines.isEmpty())
    }

    @Test
    fun aNewInstallationIsReadAgainAtTheNextList() = runTest {
        installed()
        repos(ORG, null, null)
        sync.installed(GitHubInstallation(ORG, 42, "acme"))
        repos(ORG, null, null)
        assertEquals(2, api.reads)
        directory.states[ORG] = DirectoryState(null, NOW)
        repos(ORG, null, null)
        assertEquals(3, api.reads, "Checked but never read: read now")
    }

    @Test
    fun aReadAnotherServerClaimedIsNotQueuedHere() = runTest {
        installed()
        repos(ORG, null, null)
        clock.now += 11.minutes
        directory.claimable = false
        repos(ORG, null, null)
        sync.stop()
        sync.run()
        assertEquals(1, api.reads)
    }

    @Test
    fun aListIsPagedByNameWithTheLastNameAsCursor() = runTest {
        installed()
        val first = repos(ORG, null, 2)
        assertEquals(listOf("api", "old"), first.items.map { it.name })
        assertEquals("old", first.next)
        val last = repos(ORG, first.next, 2)
        assertEquals(listOf("web"), last.items.map { it.name })
        assertNull(last.next)
        val team = teams(ORG, null, 1)
        assertEquals("backend" to "backend", team.items.single().slug to team.next)
        assertEquals(listOf("platform"), teams(ORG, "backend", 1).items.map { it.slug })
    }

    @Test
    fun aPageOutOfBoundsOrAForeignCursorIsRefused() = runTest {
        installed()
        for (limit in listOf(0, 101, -1)) {
            assertFailsWith<InvalidPageException> { repos(ORG, null, limit) }
            assertFailsWith<InvalidPageException> { teams(ORG, null, limit) }
        }
        assertFailsWith<InvalidPageException> { repos(ORG, "x".repeat(201), null) }
        // A cursor no page gave (here past every name) is refused; the last name of the list reads an empty page.
        assertFailsWith<InvalidPageException> { repos(ORG, "x".repeat(200), 100) }
        assertEquals(emptyList(), repos(ORG, "web", 100).items)
    }
}
