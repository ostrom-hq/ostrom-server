// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github

import run.ostrom.server.integrations.github.domain.GitHubCallException
import run.ostrom.server.integrations.github.domain.GitHubInstallation
import run.ostrom.server.integrations.github.domain.GitHubRepositoryReader
import run.ostrom.server.rules.domain.models.BranchProtection
import run.ostrom.server.rules.domain.models.RepositoryListing
import run.ostrom.server.rules.domain.models.RepositoryReadException
import run.ostrom.server.rules.domain.models.RepositoryRef
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [GitHubRepositoryReader]: each organization reads through its own installation, and only its account's code. */
class GitHubRepositoryReaderTest {
    private val installations = MemoryGitHubRepository()
    private val directory = FakeGitHubDirectoryApi().apply {
        repos[42] = listOf(repo("api"), repo("old", archived = true).copy(pushedAt = null, description = null))
        repos[43] = listOf(repo("globex-secret"))
    }
    private val contents = FakeGitHubContentsApi().acmeApi()
    private val reader = GitHubRepositoryReader(installations, directory, contents) { GITHUB }

    private suspend fun installBoth() {
        installations.saveInstallation(GitHubInstallation(ORG, 42, "acme"), NOW)
        installations.saveInstallation(GitHubInstallation(OTHER_ORG, 43, "globex"), NOW)
    }

    private fun ref(owner: String, name: String) =
        RepositoryRef(owner, name, "main", archived = false, description = null, pushedAt = null, webUrl = "")

    @Test
    fun anOrganizationWithoutTheAppIsNotConnected() = runTest {
        val listing = assertIs<RepositoryListing.NotConnected>(reader.repositories(ORG))
        assertTrue("not installed" in listing.reason, listing.reason)
        assertFailsWith<RepositoryReadException> { reader.tree(ORG, ref("acme", "api")) }
        assertEquals(0, directory.reads + contents.reads.size, "Nothing is read without an installation")
    }

    @Test
    fun theRepositoriesAreThoseOfTheOrganizationsOwnInstallation() = runTest {
        installBoth()
        val acme = assertIs<RepositoryListing.Connected>(reader.repositories(ORG)).repos
        val api = RepositoryRef(
            owner = "acme",
            name = "api",
            defaultBranch = "main",
            archived = false,
            description = "The api service",
            pushedAt = "2026-10-29T12:00:00Z",
            webUrl = "https://github.com/acme/api",
        )
        assertEquals(api, acme.first())
        assertEquals(RepositoryRef("acme", "old", "main", true, null, null, "https://github.com/acme/old"), acme[1])
        val globex = assertIs<RepositoryListing.Connected>(reader.repositories(OTHER_ORG)).repos
        assertEquals(listOf("globex/globex-secret"), globex.map { "${it.owner}/${it.name}" })
    }

    @Test
    fun theFilesAndTheProtectionAreReadWithTheOrganizationsInstallation() = runTest {
        installBoth()
        val api = ref("ACME", "api")
        assertEquals(5, reader.tree(ORG, api).paths.size, "The account's name is matched without case")
        assertEquals("* @acme/platform", reader.file(ORG, api, ".github/CODEOWNERS")?.lines()?.first())
        assertNull(reader.file(ORG, api, "package.json"))
        assertEquals(BranchProtection(true, 1, true, listOf("build"), true), reader.protection(ORG, api))
        assertEquals(setOf(Triple(42L, "acme/api", "main")), contents.reads.toSet(), "As the installation names it")
    }

    @Test
    fun anotherOrganizationsRepositoryIsRefusedBeforeAnyCall() = runTest {
        installBoth()
        val acmeApi = ref("acme", "api")
        for (read in listOf<suspend () -> Unit>(
            { reader.tree(OTHER_ORG, acmeApi) },
            { reader.file(OTHER_ORG, acmeApi, ".github/CODEOWNERS") },
            { reader.protection(OTHER_ORG, acmeApi) },
            // A name GitHub would not accept never reaches a path either.
            { reader.tree(ORG, ref("acme", "../globex")) },
        )) {
            val refused = assertFailsWith<RepositoryReadException> { read() }
            assertTrue("not one the organization's GitHub installation sees" in refused.message, refused.message)
        }
        assertEquals(emptyList(), contents.reads, "Org B's token never read acme's code")
    }

    @Test
    fun gitHubFailingIsAReadFailureWithItsSentence() = runTest {
        installBoth()
        directory.failure = gitHubDown()
        val listing = assertFailsWith<RepositoryReadException> { reader.repositories(ORG) }
        assertEquals("GitHub did not answer GET /repos/acme/api/pulls/6.", listing.message)
        assertIs<GitHubCallException>(listing.cause, "The cause is kept")
        contents.failure = gitHubDown()
        val api = ref("acme", "api")
        assertFailsWith<RepositoryReadException> { reader.tree(ORG, api) }
        assertFailsWith<RepositoryReadException> { reader.file(ORG, api, "CODEOWNERS") }
        assertFailsWith<RepositoryReadException> { reader.protection(ORG, api) }
    }
}
