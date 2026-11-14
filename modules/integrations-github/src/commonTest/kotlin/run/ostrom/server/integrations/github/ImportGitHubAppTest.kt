// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github

import run.ostrom.server.integrations.github.domain.CreatedGitHubApp
import run.ostrom.server.integrations.github.domain.GitHubAppCredentials
import run.ostrom.server.integrations.github.domain.GitHubAppStore
import run.ostrom.server.integrations.github.domain.ImportGitHubAppUseCaseImpl
import run.ostrom.server.kernel.logging.LogLevel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ImportGitHubAppTest {
    private val clock = SettableClock()
    private val logger = RecordingLogger()
    private val full = GitHubAppCredentials("7", "ostrom-env", "pem", "hook", "Iv1.env", "client-secret")

    @Test
    fun theFormerVariablesAreImportedOnceIntoTheStoredApp() = runTest {
        val store = MemoryGitHubAppStore()
        val import = ImportGitHubAppUseCaseImpl(store, logger, clock)
        import(full)
        assertEquals(full, store.stored)
        assertEquals(LogLevel.WARN, logger.lines.single().first)
        assertTrue("Imported" in logger.lines.single().second)

        // The next start: the stored App wins, the variables are ignored.
        import(full.copy(appId = "8"))
        assertEquals("7", store.stored?.appId)
        assertTrue("Ignored" in logger.lines.last().second)
    }

    @Test
    fun anAppWithoutItsOAuthClientIsImportedToo() = runTest {
        val store = MemoryGitHubAppStore()
        ImportGitHubAppUseCaseImpl(store, logger, clock)(GitHubAppCredentials("7", "ostrom-env", "pem"))
        assertEquals(GitHubAppCredentials("7", "ostrom-env", "pem"), store.stored)
    }

    @Test
    fun nothingNamedIsSilentAndAnIncompleteAppIsNotImported() = runTest {
        val store = MemoryGitHubAppStore()
        val import = ImportGitHubAppUseCaseImpl(store, logger, clock)
        import(GitHubAppCredentials(webhookSecret = "hook"))
        assertTrue(logger.lines.isEmpty())
        val partials = listOf(
            full.copy(appId = "x"),
            full.copy(appId = null),
            full.copy(privateKeyPem = null),
            full.copy(slug = null),
        )
        for (partial in partials) {
            import(partial)
        }
        assertNull(store.stored)
        assertEquals(4, logger.lines.count { "not imported" in it.second })
    }

    @Test
    fun anotherReplicaStoringFirstKeepsItsApp() = runTest {
        val racing = object : GitHubAppStore {
            override suspend fun app(): GitHubAppCredentials? = null

            override suspend fun saveApp(app: CreatedGitHubApp, by: String, at: Instant) = false

            override suspend fun replaceApp(app: CreatedGitHubApp, expectedVersion: Int?, by: String, at: Instant) =
                false

            override suspend fun saveState(stateHash: String, by: String, expiresAt: Instant) = Unit

            override suspend fun takeState(stateHash: String, now: Instant): String? = null
        }
        ImportGitHubAppUseCaseImpl(racing, logger, clock)(full)
        assertTrue(logger.lines.isEmpty())
    }
}
