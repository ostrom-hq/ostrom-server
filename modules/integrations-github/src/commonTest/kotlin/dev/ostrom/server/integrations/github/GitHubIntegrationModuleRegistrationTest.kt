package dev.ostrom.server.integrations.github

import dev.ostrom.server.integrations.github.domain.GitHubApi
import dev.ostrom.server.integrations.github.domain.GitHubCallException
import dev.ostrom.server.integrations.github.infrastructure.GitHubSettings
import dev.ostrom.server.integrations.github.infrastructure.HttpGitHubApi
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Clock

class GitHubIntegrationModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("integrations-github", GitHubIntegrationModule.name)
        assertEquals("create_github", GitHubIntegrationModule.migrations.single().name)
        koinApplication { modules(GitHubIntegrationModule.koinModule) }.close()
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
                    },
                )
            }
            val api = app.koin.get<GitHubApi>()
            assertIs<HttpGitHubApi>(api)
            assertFailsWith<GitHubCallException> { api.installationAccount(1) }
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
                },
            )
        }
        assertIs<HttpGitHubApi>(configured.koin.get<GitHubApi>(), "With a key: the App's JWT, read at the first call")
        configured.close()
    }
}
