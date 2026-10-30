// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github

import dev.ostrom.server.gates.domain.services.GateChannel
import dev.ostrom.server.gates.domain.services.PullRequestHeads
import dev.ostrom.server.integrations.github.domain.GitHubApi
import dev.ostrom.server.integrations.github.domain.GitHubGateChecks
import dev.ostrom.server.integrations.github.domain.GitHubInstalls
import dev.ostrom.server.integrations.github.domain.GitHubPullRequestHeads
import dev.ostrom.server.integrations.github.domain.GitHubRepository
import dev.ostrom.server.integrations.github.domain.HandleWebhookUseCase
import dev.ostrom.server.integrations.github.domain.HandleWebhookUseCaseImpl
import dev.ostrom.server.integrations.github.infrastructure.AppJwt
import dev.ostrom.server.integrations.github.infrastructure.GitHubMigrations
import dev.ostrom.server.integrations.github.infrastructure.GitHubSettings
import dev.ostrom.server.integrations.github.infrastructure.HttpGitHubApi
import dev.ostrom.server.integrations.github.infrastructure.PostgresGitHubRepository
import dev.ostrom.server.integrations.github.infrastructure.WebhookSignature
import dev.ostrom.server.integrations.github.presentation.GitHubRoutesDependencies
import dev.ostrom.server.integrations.github.presentation.gitHubRoutes
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.dsl.onClose

/** Koin name of the HTTP client to GitHub. */
val GITHUB_HTTP = named("github-http")

/**
 * The integrations-github module: the GitHub App installed per organization, its webhooks, the head of a pull
 * request read from GitHub for the gates ([PullRequestHeads]), and the `ostrom-gate` check run ([GateChannel]).
 * Its settings come from the environment (`OSTROM_GITHUB_*`).
 */
object GitHubIntegrationModule : ServerModule {
    override val name: String = "integrations-github"

    override val koinModule: Module = module {
        single(GITHUB_HTTP) {
            HttpClient {
                expectSuccess = false
                install(HttpTimeout) {
                    connectTimeoutMillis = TIMEOUT_MS
                    requestTimeoutMillis = TIMEOUT_MS
                }
            }
        } onClose { it?.close() }
        // From the environment; a test binds its own.
        single { GitHubSettings.from(get()) }
        single<GitHubRepository> { PostgresGitHubRepository(get()) }
        single<GitHubApi> {
            val settings = get<GitHubSettings>()
            val jwt = settings.appId?.let { id -> settings.privateKeyPem?.let { AppJwt(id, it, get()) } }
            HttpGitHubApi(get(GITHUB_HTTP), settings.apiUrl, jwt, get(), settings.webUrl, settings.oauthClient)
        }
        single<PullRequestHeads> { GitHubPullRequestHeads(get(), get(), get()) }
        single { GitHubGateChecks(get(), get(), get<GitHubSettings>().webOrigin, get()) } bind GateChannel::class
        single<HandleWebhookUseCase> { HandleWebhookUseCaseImpl(get(), get(), get(), get()) }
        single {
            val settings = get<GitHubSettings>()
            GitHubInstalls(get(), get(), settings.installSlug, settings.webUrl, get())
        }
        single {
            val settings = get<GitHubSettings>()
            val signature = WebhookSignature(settings.webhookSecret)
            GitHubRoutesDependencies(get(), get(), get(), get(), get(), signature, settings)
        }
    }

    override val migrations: List<SchemaMigration> = GitHubMigrations

    override fun routes(route: Route, koin: Koin) {
        route.gitHubRoutes(koin.get())
    }

    private const val TIMEOUT_MS = 10_000L
}
