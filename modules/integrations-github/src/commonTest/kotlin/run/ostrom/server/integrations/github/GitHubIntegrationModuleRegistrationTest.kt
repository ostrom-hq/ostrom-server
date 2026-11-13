// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github

import run.ostrom.server.gates.domain.services.CiChecks
import run.ostrom.server.integrations.github.domain.CloudSessionsSwitch
import run.ostrom.server.integrations.github.domain.DirectoryState
import run.ostrom.server.integrations.github.domain.GitHubAccess
import run.ostrom.server.integrations.github.domain.GitHubAccessResolver
import run.ostrom.server.integrations.github.domain.GitHubApi
import run.ostrom.server.integrations.github.domain.GitHubAppStore
import run.ostrom.server.integrations.github.domain.GitHubCallException
import run.ostrom.server.integrations.github.domain.GitHubCiApi
import run.ostrom.server.integrations.github.domain.GitHubCiChecks
import run.ostrom.server.integrations.github.domain.GitHubCiRun
import run.ostrom.server.integrations.github.domain.GitHubContentsApi
import run.ostrom.server.integrations.github.domain.GitHubDirectoryApi
import run.ostrom.server.integrations.github.domain.GitHubDirectoryRepository
import run.ostrom.server.integrations.github.domain.GitHubDirectorySync
import run.ostrom.server.integrations.github.domain.GitHubInstallation
import run.ostrom.server.integrations.github.domain.GitHubLinkActor
import run.ostrom.server.integrations.github.domain.GitHubLinkCloudOffException
import run.ostrom.server.integrations.github.domain.GitHubLinkStore
import run.ostrom.server.integrations.github.domain.GitHubLinks
import run.ostrom.server.integrations.github.domain.GitHubManifestApi
import run.ostrom.server.integrations.github.domain.GitHubRepository
import run.ostrom.server.integrations.github.domain.GitHubRepositoryReader
import run.ostrom.server.integrations.github.domain.GitHubTokenClaim
import run.ostrom.server.integrations.github.domain.GitHubUserAuthApi
import run.ostrom.server.integrations.github.infrastructure.GitHubSettings
import run.ostrom.server.integrations.github.infrastructure.HttpGitHubApi
import run.ostrom.server.integrations.github.infrastructure.HttpGitHubContentsApi
import run.ostrom.server.integrations.github.infrastructure.HttpGitHubUserAuth
import run.ostrom.server.integrations.github.infrastructure.NoGitHubLinkMembers
import run.ostrom.server.integrations.github.infrastructure.RunnersCloudSwitch
import run.ostrom.server.integrations.github.presentation.GitHubLinkRoutesDependencies
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.rules.domain.services.RepositoryReader
import run.ostrom.server.runners.infrastructure.RunnersConfig
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

class GitHubIntegrationModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("integrations-github", GitHubIntegrationModule.name)
        val migrations = GitHubIntegrationModule.migrations.map { it.version to it.name }
        val expected = listOf(
            1L to "create_github",
            2L to "create_github_directory_and_app",
            3L to "create_github_origins_and_deliveries",
            4L to "create_github_links",
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
    fun theRulesAnalysisReadsTheRepositoriesWithTheAppsInstallationTokens() = runTest {
        val app = koinApplication {
            allowOverride(true)
            modules(
                GitHubIntegrationModule.koinModule,
                module {
                    single<Clock> { SettableClock() }
                    single { GitHubSettings("1", null, null, null) }
                    single<GitHubAppStore> { MemoryGitHubAppStore() }
                    single<GitHubRepository> {
                        MemoryGitHubRepository().apply { installations += GitHubInstallation(ORG, 42, "acme") }
                    }
                },
            )
        }
        assertIs<GitHubRepositoryReader>(app.koin.get<RepositoryReader>())
        val contents = app.koin.get<GitHubContentsApi>()
        assertIs<HttpGitHubContentsApi>(contents)
        // The token comes from the App's installation: without its key on this server, nothing is read.
        val refused = assertFailsWith<GitHubCallException> { contents.tree(42, "acme/api", "main") }
        assertEquals("The GitHub App is not configured on this server.", refused.message)
        app.close()
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

    @Test
    fun theLinksAreWiredOnTheAppsClientTheMembersAndTheRunners() = runTest {
        val store = MemoryGitHubLinkStore()
        fun links(vararg extra: Module) = koinApplication {
            allowOverride(true)
            modules(
                GitHubIntegrationModule.koinModule,
                module {
                    single<Clock> { SettableClock() }
                    single<Logger> { RecordingLogger() }
                    single { GitHubSettings("1", null, null, null, clientId = CLIENT_ID, clientSecret = "secret") }
                    single<GitHubAppStore> { MemoryGitHubAppStore() }
                    single<GitHubLinkStore> { store }
                    single<ConfigSource> { MapConfigSource(mapOf("OSTROM_PUBLIC_URL" to "https://ostrom.example")) }
                    single<OrgAuditTrail> { RecordingAudit() }
                    single<PrincipalOrgResolver> {
                        object : PrincipalOrgResolver {
                            override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?) = null
                        }
                    }
                },
                *extra,
            )
        }
        val bare = links()
        assertIs<HttpGitHubUserAuth>(bare.koin.get<GitHubUserAuthApi>())
        assertEquals(false, bare.koin.get<CloudSessionsSwitch>().enabled(), "No runners module: no cloud sessions")
        val actor = GitHubLinkActor(ORG, DEV, false, AuthenticatedPrincipal("u", PrincipalKind.USER, emptySet()))
        assertFailsWith<GitHubLinkCloudOffException> { bare.koin.get<GitHubLinks>().startDevice(actor) }
        val nobody = bare.koin.get<GitHubAccessResolver>().access(ORG, DEV)
        assertEquals(GitHubAccess.NotLinked, nobody, "Without the orgs module, nobody is a member")
        assertEquals("http://localhost:5173", bare.koin.get<GitHubLinkRoutesDependencies>().webOrigin)
        bare.close()

        val docker = RunnersConfig.from(
            MapConfigSource(mapOf("OSTROM_RUNNERS_DRIVER" to "docker", "OSTROM_RUNNERS_SERVER_URL" to "http://ostrom")),
        )
        val full = links(
            module {
                single { docker }
                single<MemberDirectory> { SettableMembers(mutableSetOf(ORG to DEV)) }
                single<GitHubUserAuthApi> { FakeGitHubUserAuth() }
            },
        )
        assertTrue(full.koin.get<CloudSessionsSwitch>().enabled())
        assertEquals("ABCD-1234", full.koin.get<GitHubLinks>().startDevice(actor).userCode)
        store.claims += GitHubTokenClaim.NotLinked
        assertEquals(GitHubAccess.NotLinked, full.koin.get<GitHubAccessResolver>().access(ORG, DEV))
        assertTrue(store.claims.isEmpty(), "A member: their link is read")
        full.close()
    }

    @Test
    fun cloudSessionsAreOnWithARuntimeOnly() = runTest {
        assertFalse(RunnersCloudSwitch(null).enabled())
        assertFalse(RunnersCloudSwitch(RunnersConfig.from(MapConfigSource(emptyMap()))).enabled())
        val registered = MapConfigSource(
            mapOf("OSTROM_RUNNERS_DRIVER" to "registered", "OSTROM_RUNNERS_SERVER_URL" to "https://ostrom"),
        )
        assertTrue(RunnersCloudSwitch(RunnersConfig.from(registered)).enabled())
        assertFalse(NoGitHubLinkMembers.isActiveMember(ORG, DEV.toString()))
    }
}
