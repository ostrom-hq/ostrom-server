// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github

import dev.ostrom.server.gates.domain.services.CiChecks
import dev.ostrom.server.integrations.github.domain.GitHubApi
import dev.ostrom.server.integrations.github.domain.DirectoryState
import dev.ostrom.server.integrations.github.domain.GitHubAppStore
import dev.ostrom.server.integrations.github.domain.GitHubCallException
import dev.ostrom.server.integrations.github.domain.GitHubCiApi
import dev.ostrom.server.integrations.github.domain.GitHubCiChecks
import dev.ostrom.server.integrations.github.domain.GitHubCiRun
import dev.ostrom.server.integrations.github.domain.GitHubDirectoryApi
import dev.ostrom.server.integrations.github.domain.GitHubDirectoryRepository
import dev.ostrom.server.integrations.github.domain.GitHubDirectorySync
import dev.ostrom.server.integrations.github.domain.GitHubInstallation
import dev.ostrom.server.integrations.github.domain.GitHubManifestApi
import dev.ostrom.server.integrations.github.domain.GitHubRepository
import dev.ostrom.server.integrations.github.infrastructure.GitHubSettings
import dev.ostrom.server.integrations.github.infrastructure.HttpGitHubApi
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.MapConfigSource
import dev.ostrom.server.kernel.logging.Logger
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

class GitHubIntegrationModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("integrations-github", GitHubIntegrationModule.name)
        val migrations = GitHubIntegrationModule.migrations.map { it.version to it.name }
        val expected = listOf(
            1L to "create_github",
            2L to "create_github_directory_and_app",
            3L to "create_github_origins_and_deliveries",
        )
        assertEquals(expected, migrations)
        koinApplication { modules(GitHubIntegrationModule.koinModule) }.close()
    }

    @Test
    fun theSettingsComeFromTheServersConfiguration() {
        val app = koinApplication {
            modules(
                GitHubIntegrationModule.koinModule,
                module { single<ConfigSource> { MapConfigSource(mapOf("OSTROM_GITHUB_APP_ID" to " 42 ")) } },
            )
        }
        assertEquals("42", app.koin.get<GitHubSettings>().appId)
        app.close()
    }

    @Test
    fun theApiAuthenticatesOnlyWithAnAppIdAndAKey() = runTest {
        for (settings in listOf(GitHubSettings("1", null, null, null), GitHubSettings(null, "pem", null, null))) {
            val app = koinApplication {
                allowOverride(true)
                modules(
                    GitHubIntegrationModule.koinModule,
                    module {
                        single<Clock> { SettableClock() }
                        single { settings }
                        single<GitHubAppStore> { MemoryGitHubAppStore() }
                    },
                )
            }
            val api = app.koin.get<GitHubApi>()
            assertIs<HttpGitHubApi>(api)
            assertFailsWith<GitHubCallException> { api.installationAccount(1) }
            // The OAuth client of the install is the App's, read at each exchange: none in these environments.
            val noClient = assertFailsWith<GitHubCallException> { api.userToken("code") }
            assertEquals("The GitHub App has no OAuth client on this server.", noClient.message)
            app.koin.get<HttpClient>(GITHUB_HTTP)
            app.close()
        }
        val configured = koinApplication {
            allowOverride(true)
            modules(
                GitHubIntegrationModule.koinModule,
                module {
                    single<Clock> { SettableClock() }
                    single { GitHubSettings("1", "pem", null, null) }
                    single<GitHubAppStore> { MemoryGitHubAppStore() }
                },
            )
        }
        assertIs<HttpGitHubApi>(configured.koin.get<GitHubApi>(), "With a key: the App's JWT, read at the first call")
        assertSame<Any>(configured.koin.get<GitHubApi>(), configured.koin.get<GitHubDirectoryApi>())
        assertSame<Any>(configured.koin.get<GitHubApi>(), configured.koin.get<GitHubManifestApi>())
        assertSame<Any>(configured.koin.get<GitHubApi>(), configured.koin.get<GitHubCiApi>())
        configured.close()
    }

    @Test
    fun theCiProofIsGitHubsRecordWithoutTheChecksOfOstromsOwnApp() = runTest {
        val api = FakeGitHubCiApi()
        api.runs = listOf(
            GitHubCiRun(1, "test", SHA, "completed", "success", "ostrom-dev", null, NOW),
            GitHubCiRun(2, "test", SHA, "completed", "failure", "github-actions", null, NOW),
        )
        val app = koinApplication {
            allowOverride(true)
            modules(
                GitHubIntegrationModule.koinModule,
                module {
                    single<Clock> { SettableClock() }
                    single<Logger> { RecordingLogger() }
                    single { GitHubSettings("1", "pem", null, "ostrom-dev") }
                    single<GitHubAppStore> { MemoryGitHubAppStore() }
                    single<GitHubRepository> {
                        MemoryGitHubRepository().apply { installations += GitHubInstallation(ORG, 42, "acme") }
                    }
                    single<GitHubCiApi> { api }
                },
            )
        }
        val ci = app.koin.get<CiChecks>()
        assertIs<GitHubCiChecks>(ci)
        assertEquals(2L, ci.latest(ORG, "acme/api", SHA, "test")?.id, "The slug of the App in use is Ostrom's own")
        app.close()
    }

    @Test
    fun theDirectoryIsRefreshedInTheBackgroundOnceTheServerStarts() = runTest {
        val directory = MemoryGitHubDirectoryRepository()
        val api = FakeGitHubDirectoryApi()
        val logger = RecordingLogger()
        val app = koinApplication {
            allowOverride(true)
            modules(
                GitHubIntegrationModule.koinModule,
                module {
                    single<Clock> { SettableClock() }
                    single<Logger> { logger }
                    single<GitHubRepository> {
                        MemoryGitHubRepository().apply { installations += GitHubInstallation(ORG, 42, "acme") }
                    }
                    single<GitHubDirectoryRepository> { directory }
                    single<GitHubDirectoryApi> { api }
                },
            )
        }
        val sync = app.koin.get<GitHubDirectorySync>()
        directory.states[ORG] = DirectoryState(NOW - 1.hours, NOW - 1.hours)
        // On the test dispatcher: advanceUntilIdle runs this scope, never backgroundScope.
        val worker = CoroutineScope(coroutineContext + Job())
        GitHubIntegrationModule.start(worker, app.koin)
        assertTrue(sync.ensure(ORG))
        testScheduler.advanceUntilIdle()
        assertEquals(1, api.reads, "Read by the worker the module started, logged: ${logger.lines}")
        sync.stop()
        worker.cancel()
        app.close()
    }
}
