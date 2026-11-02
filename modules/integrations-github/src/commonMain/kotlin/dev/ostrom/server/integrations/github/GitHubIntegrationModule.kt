// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github

import dev.ostrom.server.gates.domain.services.GateChannel
import dev.ostrom.server.gates.domain.services.PullRequestHeads
import dev.ostrom.server.integrations.github.domain.ConfiguredInstanceAdmins
import dev.ostrom.server.integrations.github.domain.GitHubApi
import dev.ostrom.server.integrations.github.domain.GitHubAppManifests
import dev.ostrom.server.integrations.github.domain.GitHubAppSource
import dev.ostrom.server.integrations.github.domain.GitHubAppSourceImpl
import dev.ostrom.server.integrations.github.domain.GitHubAppStore
import dev.ostrom.server.integrations.github.domain.GitHubDirectoryApi
import dev.ostrom.server.integrations.github.domain.GitHubDirectoryRepository
import dev.ostrom.server.integrations.github.domain.GitHubDirectorySync
import dev.ostrom.server.integrations.github.domain.GitHubGateChecks
import dev.ostrom.server.integrations.github.domain.GitHubInstalls
import dev.ostrom.server.integrations.github.domain.GitHubManifestApi
import dev.ostrom.server.integrations.github.domain.GitHubManifestTarget
import dev.ostrom.server.integrations.github.domain.GitHubPullRequestHeads
import dev.ostrom.server.integrations.github.domain.GitHubRepository
import dev.ostrom.server.integrations.github.domain.HandleWebhookUseCase
import dev.ostrom.server.integrations.github.domain.HandleWebhookUseCaseImpl
import dev.ostrom.server.integrations.github.domain.InstanceAdmins
import dev.ostrom.server.integrations.github.domain.ListGitHubReposUseCase
import dev.ostrom.server.integrations.github.domain.ListGitHubReposUseCaseImpl
import dev.ostrom.server.integrations.github.domain.ListGitHubTeamsUseCase
import dev.ostrom.server.integrations.github.domain.ListGitHubTeamsUseCaseImpl
import dev.ostrom.server.integrations.github.domain.OrgReferences
import dev.ostrom.server.integrations.github.infrastructure.AppJwts
import dev.ostrom.server.integrations.github.infrastructure.GitHubAppSetup
import dev.ostrom.server.integrations.github.infrastructure.GitHubMigrations
import dev.ostrom.server.integrations.github.infrastructure.GitHubSettings
import dev.ostrom.server.integrations.github.infrastructure.HttpGitHubApi
import dev.ostrom.server.integrations.github.infrastructure.OrgsOrgReferences
import dev.ostrom.server.integrations.github.infrastructure.PostgresGitHubAppStore
import dev.ostrom.server.integrations.github.infrastructure.PostgresGitHubDirectoryRepository
import dev.ostrom.server.integrations.github.infrastructure.PostgresGitHubRepository
import dev.ostrom.server.integrations.github.infrastructure.WebhookSignature
import dev.ostrom.server.integrations.github.infrastructure.credentials
import dev.ostrom.server.integrations.github.infrastructure.oauthClient
import dev.ostrom.server.integrations.github.presentation.GitHubDirectoryRoutesDependencies
import dev.ostrom.server.integrations.github.presentation.GitHubManifestRoutesDependencies
import dev.ostrom.server.integrations.github.presentation.GitHubRoutesDependencies
import dev.ostrom.server.integrations.github.presentation.gitHubDirectoryRoutes
import dev.ostrom.server.integrations.github.presentation.gitHubManifestRoutes
import dev.ostrom.server.integrations.github.presentation.gitHubRoutes
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
 * request read from GitHub for the gates ([PullRequestHeads]), and the `ostrom-gate` check run ([GateChannel]); each
 * organization's directory (repositories and teams) read from its installation and kept in the database; and the
 * manifest flow, which creates the instance's App when the environment names none. Its settings come from the
 * environment (`OSTROM_GITHUB_*`, `OSTROM_PUBLIC_URL`, `OSTROM_INSTANCE_ADMINS`).
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
        single { GitHubAppSetup.from(get()) }
        single<GitHubRepository> { PostgresGitHubRepository(get()) }
        // The orgs module's lookup: `?org=` and `{org}` read an id or a short name, as the `Ostrom-Org` header does.
        single<OrgReferences> { OrgsOrgReferences(get()) }
        single<GitHubAppStore> { PostgresGitHubAppStore(get(), get()) }
        single<GitHubAppSource> { GitHubAppSourceImpl(get<GitHubSettings>().credentials(), get(), get()) }
        single { AppJwts(get(), get()) }
        single {
            val jwts = get<AppJwts>()
            val apps = get<GitHubAppSource>()
            val settings = get<GitHubSettings>()
            // The OAuth client is the App's, read like its key: the environment's, or the one the manifest created.
            HttpGitHubApi(get(GITHUB_HTTP), settings.apiUrl, { jwts.current() }, get(), settings.webUrl) {
                apps.current().oauthClient()
            }
        }
        single<GitHubApi> { get<HttpGitHubApi>() }
        single<GitHubDirectoryApi> { get<HttpGitHubApi>() }
        single<GitHubManifestApi> { get<HttpGitHubApi>() }
        single<GitHubDirectoryRepository> { PostgresGitHubDirectoryRepository(get()) }
        single { GitHubDirectorySync(get(), get(), get(), get(), get()) }
        single<ListGitHubReposUseCase> { ListGitHubReposUseCaseImpl(get(), get()) }
        single<ListGitHubTeamsUseCase> { ListGitHubTeamsUseCaseImpl(get(), get()) }
        single<InstanceAdmins> { ConfiguredInstanceAdmins(get<GitHubAppSetup>().instanceAdmins) }
        single {
            val settings = get<GitHubSettings>()
            val setup = get<GitHubAppSetup>()
            GitHubManifestTarget(setup.publicUrl, settings.webOrigin, settings.webUrl, setup.appName)
        }
        single { GitHubAppManifests(get(), get(), get(), get(), get(), get()) }
        single<PullRequestHeads> { GitHubPullRequestHeads(get(), get(), get()) }
        single { GitHubGateChecks(get(), get(), get<GitHubSettings>().webOrigin, get()) } bind GateChannel::class
        single<HandleWebhookUseCase> { HandleWebhookUseCaseImpl(get(), get(), get(), get(), get()) }
        single {
            val apps = get<GitHubAppSource>()
            val sync = get<GitHubDirectorySync>()
            GitHubInstalls(get(), get(), { apps.current().installSlug }, get<GitHubSettings>().webUrl, get()) {
                sync.installed(it)
            }
        }
        single {
            val apps = get<GitHubAppSource>()
            val signature = WebhookSignature { apps.current().webhookSecret }
            GitHubRoutesDependencies(get(), get(), get(), get(), get(), signature, get(), apps, get())
        }
        single { GitHubDirectoryRoutesDependencies(get(), get(), get(), get(), get()) }
        single {
            val settings = get<GitHubSettings>()
            GitHubManifestRoutesDependencies(get(), settings.webUrl, settings.webOrigin)
        }
    }

    override val migrations: List<SchemaMigration> = GitHubMigrations

    override fun routes(route: Route, koin: Koin) {
        route.gitHubRoutes(koin.get())
        route.gitHubDirectoryRoutes(koin.get())
        route.gitHubManifestRoutes(koin.get())
    }

    override fun start(scope: CoroutineScope, koin: Koin) {
        val sync = koin.get<GitHubDirectorySync>()
        scope.launch { sync.run() }
    }

    private const val TIMEOUT_MS = 10_000L
}
