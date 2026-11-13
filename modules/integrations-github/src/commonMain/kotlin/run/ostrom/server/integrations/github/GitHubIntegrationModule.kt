// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.gates.domain.services.CiChecks
import run.ostrom.server.gates.domain.services.GateChannel
import run.ostrom.server.gates.domain.services.GateGitHubAccounts
import run.ostrom.server.gates.domain.services.PullRequestHeads
import run.ostrom.server.integrations.github.domain.CloudSessionsSwitch
import run.ostrom.server.integrations.github.domain.ConfiguredInstanceAdmins
import run.ostrom.server.integrations.github.domain.GitHubAccessResolver
import run.ostrom.server.integrations.github.domain.GitHubAccounts
import run.ostrom.server.integrations.github.domain.GitHubApi
import run.ostrom.server.integrations.github.domain.GitHubAppManifests
import run.ostrom.server.integrations.github.domain.GitHubAppSource
import run.ostrom.server.integrations.github.domain.GitHubAppSourceImpl
import run.ostrom.server.integrations.github.domain.GitHubAppStore
import run.ostrom.server.integrations.github.domain.GitHubCiApi
import run.ostrom.server.integrations.github.domain.GitHubCiChecks
import run.ostrom.server.integrations.github.domain.GitHubContentsApi
import run.ostrom.server.integrations.github.domain.GitHubDirectoryApi
import run.ostrom.server.integrations.github.domain.GitHubDirectoryRepository
import run.ostrom.server.integrations.github.domain.GitHubDirectorySync
import run.ostrom.server.integrations.github.domain.GitHubGateChecks
import run.ostrom.server.integrations.github.domain.GitHubInstalls
import run.ostrom.server.integrations.github.domain.GitHubLinkStore
import run.ostrom.server.integrations.github.domain.GitHubLinks
import run.ostrom.server.integrations.github.domain.GitHubManifestApi
import run.ostrom.server.integrations.github.domain.GitHubManifestTarget
import run.ostrom.server.integrations.github.domain.GitHubOriginsRepository
import run.ostrom.server.integrations.github.domain.GitHubPullRequestHeads
import run.ostrom.server.integrations.github.domain.GitHubRepository
import run.ostrom.server.integrations.github.domain.GitHubRepositoryReader
import run.ostrom.server.integrations.github.domain.GitHubUserAuthApi
import run.ostrom.server.integrations.github.domain.HandleWebhookUseCase
import run.ostrom.server.integrations.github.domain.HandleWebhookUseCaseImpl
import run.ostrom.server.integrations.github.domain.InstanceAdmins
import run.ostrom.server.integrations.github.domain.ListGitHubReposUseCase
import run.ostrom.server.integrations.github.domain.ListGitHubReposUseCaseImpl
import run.ostrom.server.integrations.github.domain.ListGitHubTeamsUseCase
import run.ostrom.server.integrations.github.domain.ListGitHubTeamsUseCaseImpl
import run.ostrom.server.integrations.github.domain.OrgReferences
import run.ostrom.server.integrations.github.domain.installPage
import run.ostrom.server.integrations.github.domain.oauthApp
import run.ostrom.server.integrations.github.infrastructure.AppJwts
import run.ostrom.server.integrations.github.infrastructure.GitHubAppSetup
import run.ostrom.server.integrations.github.infrastructure.GitHubMigrations
import run.ostrom.server.integrations.github.infrastructure.GitHubSettings
import run.ostrom.server.integrations.github.infrastructure.HttpGitHubApi
import run.ostrom.server.integrations.github.infrastructure.HttpGitHubContentsApi
import run.ostrom.server.integrations.github.infrastructure.HttpGitHubUserAuth
import run.ostrom.server.integrations.github.infrastructure.IdentityGitHubAccounts
import run.ostrom.server.integrations.github.infrastructure.LinkedRunnerGitHubTokens
import run.ostrom.server.integrations.github.infrastructure.NoGitHubLinkMembers
import run.ostrom.server.integrations.github.infrastructure.OrgsOrgReferences
import run.ostrom.server.integrations.github.infrastructure.PostgresGitHubAppStore
import run.ostrom.server.integrations.github.infrastructure.PostgresGitHubDirectoryRepository
import run.ostrom.server.integrations.github.infrastructure.PostgresGitHubLinkStore
import run.ostrom.server.integrations.github.infrastructure.PostgresGitHubOriginsRepository
import run.ostrom.server.integrations.github.infrastructure.PostgresGitHubRepository
import run.ostrom.server.integrations.github.infrastructure.RunnersCloudSwitch
import run.ostrom.server.integrations.github.infrastructure.WebhookSignature
import run.ostrom.server.integrations.github.infrastructure.credentials
import run.ostrom.server.integrations.github.infrastructure.oauthClient
import run.ostrom.server.integrations.github.presentation.GitHubDirectoryRoutesDependencies
import run.ostrom.server.integrations.github.presentation.GitHubLinkRoutesDependencies
import run.ostrom.server.integrations.github.presentation.GitHubManifestRoutesDependencies
import run.ostrom.server.integrations.github.presentation.GitHubRoutesDependencies
import run.ostrom.server.integrations.github.presentation.gitHubDirectoryRoutes
import run.ostrom.server.integrations.github.presentation.gitHubLinkRoutes
import run.ostrom.server.integrations.github.presentation.gitHubManifestRoutes
import run.ostrom.server.integrations.github.presentation.gitHubRoutes
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.rules.domain.services.RepositoryReader
import run.ostrom.server.runners.domain.RunnerGitHubTokens
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
 * manifest flow, which creates the instance's App when the environment names none; and the rules analysis's
 * [RepositoryReader], which reads the files, CODEOWNERS and branch protection of the installation's
 * repositories; and the members' GitHub links, which give each member's cloud sessions their own Git and `gh` access
 * (device flow, web flow, refresh, unlink). Its settings come from the
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
        single<GitHubOriginsRepository> { PostgresGitHubOriginsRepository(get()) }
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
        single<GitHubCiApi> { get<HttpGitHubApi>() }
        single<GitHubContentsApi> {
            val api = get<HttpGitHubApi>()
            val apiUrl = get<GitHubSettings>().apiUrl
            HttpGitHubContentsApi(get(GITHUB_HTTP), apiUrl, get()) { id, wanted -> api.installationToken(id, wanted) }
        }
        // The rules analysis reads each organization's repositories through its own installation, read-only.
        single<RepositoryReader> { GitHubRepositoryReader(get(), get(), get(), get<GitHubSettings>().webUrl) }
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
        single<CiChecks> {
            val apps = get<GitHubAppSource>()
            GitHubCiChecks(get(), get(), { apps.current().slug }, get())
        }
        single { GitHubGateChecks(get(), get(), get<GitHubSettings>().webOrigin, get()) } bind GateChannel::class
        single<HandleWebhookUseCase> { HandleWebhookUseCaseImpl(get(), get(), get(), get(), get(), get(), get()) }
        single { IdentityGitHubAccounts(get(), get()) }
        single<GitHubAccounts> { get<IdentityGitHubAccounts>() }
        single<GateGitHubAccounts> { get<IdentityGitHubAccounts>() }
        single {
            val apps = get<GitHubAppSource>()
            val sync = get<GitHubDirectorySync>()
            val webUrl = get<GitHubSettings>().webUrl
            GitHubInstalls(get(), get(), get(), { installPage(webUrl, apps.current().installSlug) }, get()) {
                sync.installed(it)
            }
        }
        single {
            val apps = get<GitHubAppSource>()
            val signature = WebhookSignature { apps.current().webhookSecret }
            GitHubRoutesDependencies(get(), get(), get(), get(), get(), signature, get(), apps, get())
        }
        single { GitHubDirectoryRoutesDependencies(get(), get(), get(), get(), get()) }
        // The members' links: the App's OAuth client links, refreshes and revokes; tokens sealed by the secrets key.
        single<GitHubUserAuthApi> {
            val settings = get<GitHubSettings>()
            HttpGitHubUserAuth(get(GITHUB_HTTP), settings.apiUrl, settings.webUrl, get())
        }
        single<GitHubLinkStore> { PostgresGitHubLinkStore(get(), get()) }
        // Cloud sessions are on with a runtime driver: a new link starts only then.
        single<CloudSessionsSwitch> { RunnersCloudSwitch(getOrNull()) }
        single {
            val redirect = "${get<GitHubAppSetup>().publicUrl}/api/v1/github/link/callback"
            val members = getOrNull<MemberDirectory>() ?: NoGitHubLinkMembers
            GitHubLinks(get(), get(), get(), members, get(), get(), redirect, get())
        }
        single {
            val members = getOrNull<MemberDirectory>() ?: NoGitHubLinkMembers
            GitHubAccessResolver(get(), get(), get(), members, get(), get(), get())
        }
        single<RunnerGitHubTokens> { LinkedRunnerGitHubTokens(get(), get()) }
        single { GitHubLinkRoutesDependencies(get(), get(), get<GitHubSettings>().webOrigin) }
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
        route.gitHubLinkRoutes(koin.get())
    }

    /**
     * The GitHub App integration, only once the instance has an App: the environment's, or the one the
     * manifest flow created (read again at each call, so an App created by the console counts at once); the members'
     * links once that App's OAuth client is known and cloud sessions are on.
     */
    override suspend fun features(koin: Koin): Set<String> {
        val app = koin.get<GitHubAppSource>().current()
        // Links only with the App's OAuth client (it alone links, refreshes and revokes a member's tokens), and only
        // where cloud sessions run, the one use of a link.
        val links = app.oauthApp() != null && koin.get<CloudSessionsSwitch>().enabled()
        return setOfNotNull(ServerFeatures.GITHUB.takeIf { app.named }, ServerFeatures.GITHUB_LINK.takeIf { links })
    }

    override fun start(scope: CoroutineScope, koin: Koin) {
        val sync = koin.get<GitHubDirectorySync>()
        scope.launch { sync.run() }
    }

    private const val TIMEOUT_MS = 10_000L
}
