// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose
import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.integrations.slack.domain.GetSlackLinkUseCase
import run.ostrom.server.integrations.slack.domain.GetSlackLinkUseCaseImpl
import run.ostrom.server.integrations.slack.domain.LinkSlackUseCase
import run.ostrom.server.integrations.slack.domain.LinkSlackUseCaseImpl
import run.ostrom.server.integrations.slack.domain.ReceiveSlackInteractionUseCase
import run.ostrom.server.integrations.slack.domain.ReceiveSlackInteractionUseCaseImpl
import run.ostrom.server.integrations.slack.domain.SlackApi
import run.ostrom.server.integrations.slack.domain.SlackApprovalChannel
import run.ostrom.server.integrations.slack.domain.SlackApprovalMessagesRepository
import run.ostrom.server.integrations.slack.domain.SlackApps
import run.ostrom.server.integrations.slack.domain.SlackLinksRepository
import run.ostrom.server.integrations.slack.domain.SlackNotificationChannel
import run.ostrom.server.integrations.slack.domain.SlackPeople
import run.ostrom.server.integrations.slack.domain.SlackSettings
import run.ostrom.server.integrations.slack.domain.SlackSigner
import run.ostrom.server.integrations.slack.domain.SlackTexts
import run.ostrom.server.integrations.slack.domain.UnlinkSlackUseCase
import run.ostrom.server.integrations.slack.domain.UnlinkSlackUseCaseImpl
import run.ostrom.server.integrations.slack.infrastructure.HmacSlackSigner
import run.ostrom.server.integrations.slack.infrastructure.HttpSlackApi
import run.ostrom.server.integrations.slack.infrastructure.MemberSlackPeople
import run.ostrom.server.integrations.slack.infrastructure.PostgresSlackApprovalMessagesRepository
import run.ostrom.server.integrations.slack.infrastructure.PostgresSlackLinksRepository
import run.ostrom.server.integrations.slack.infrastructure.SlackMigrations
import run.ostrom.server.integrations.slack.infrastructure.SlackOrgDeletionListener
import run.ostrom.server.integrations.slack.presentation.SlackRoutesDependencies
import run.ostrom.server.integrations.slack.presentation.slackOperations
import run.ostrom.server.integrations.slack.presentation.slackRoutes
import run.ostrom.server.identity.domain.UsersRepository
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.module.OrgDeletionListener
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.kernel.network.outboundHttpClient
import run.ostrom.server.kernel.settings.SettingsSection
import run.ostrom.server.notifications.domain.ApprovalChannel
import run.ostrom.server.notifications.domain.NotificationChannel
import run.ostrom.server.orgs.domain.MembersRepository
import run.ostrom.server.orgs.domain.OrgsRepository

/**
 * The integrations-slack module: the instance's Slack app (Admin → Settings → `slack`: its bot token and signing
 * secret, sealed), each member's link to the Slack account of their proven e-mail address (`/api/v1/slack/link`), the
 * `slack` notification channel (one direct message per notification, through the notifications' queue and behind the
 * member's switches), and the yes requests in Slack: Approve opens the console's page of the request, Reject is
 * answered at `/webhooks/slack`, and the messages lose their buttons once the gate is decided. `slack.v1` is listed
 * whenever this module is in the server; `SlackLink.configured` says whether the app is. The app needs the bot scopes
 * `chat:write`, `users:read` and `users:read.email`, and its interactivity request URL at `/webhooks/slack`.
 */
object SlackIntegrationModule : ServerModule {
    // Declared before koinModule: the object initializes in order, and the module lambda runs at once.
    private val HTTP = named("slack-http")
    private const val HTTP_TIMEOUT_MS = 10_000L
    private const val CONSOLE_URL = "OSTROM_CONSOLE_URL"

    override val name: String = "integrations-slack"

    override val apiOperations: ApiCatalog.() -> Unit = { slackOperations() }

    override val koinModule: Module = module {
        single(HTTP) {
            outboundHttpClient {
                install(HttpTimeout) {
                    connectTimeoutMillis = HTTP_TIMEOUT_MS
                    requestTimeoutMillis = HTTP_TIMEOUT_MS
                }
            }
        } onClose { it?.close() }
        single<SlackApi> { HttpSlackApi(get<HttpClient>(HTTP)) }
        // Admin → Settings → slack, served and sealed by the admin module; a bot token is checked with Slack.
        single<SettingsSection>(named(SlackSettings.NAME)) {
            val scope = this
            SlackSettings.section { token -> scope.get<SlackApi>().authTest(token)?.let(::refusal) }
        }
        single { SlackApps(get()) }
        single<SlackSigner> { HmacSlackSigner() }
        single<SlackLinksRepository> { PostgresSlackLinksRepository(get()) }
        single<SlackApprovalMessagesRepository> { PostgresSlackApprovalMessagesRepository(get()) }
        single<SlackPeople> {
            val members = get<MembersRepository>()
            val users = get<UsersRepository>()
            val orgs = get<OrgsRepository>()
            MemberSlackPeople(members::findById, users::get, users::listEmails, orgs::get)
        }
        single { SlackTexts(SlackTexts.consoleUrl(get<ConfigSource>()[CONSOLE_URL])) }
        single<GetSlackLinkUseCase> { GetSlackLinkUseCaseImpl(get(), get()) }
        single<LinkSlackUseCase> { LinkSlackUseCaseImpl(get(), get(), get(), get(), get(), get()) }
        single<UnlinkSlackUseCase> { UnlinkSlackUseCaseImpl(get()) }
        single<ReceiveSlackInteractionUseCase> {
            ReceiveSlackInteractionUseCaseImpl(get(), get(), get(), get(), get(), get(), get())
        }
        single { SlackRoutesDependencies(get(), get(), get(), get(), get()) }
        // The notifications' channel and the approvals' way (its answers close the messages through the queue).
        single<NotificationChannel>(named(SlackSettings.NAME)) { SlackNotificationChannel(get(), get(), get(), get()) }
        single<ApprovalChannel>(named(SlackSettings.NAME)) { SlackApprovalChannel(get(), get(), get(), get(), get()) }
        single<OrgDeletionListener>(named("integrations-slack")) { SlackOrgDeletionListener(get(), get()) }
    }

    override val migrations: List<SchemaMigration> = SlackMigrations

    override fun routes(route: Route, koin: Koin) {
        route.slackRoutes(koin.get())
    }

    /** `slack.v1`: the link routes and the webhook are served; whether the app is configured is the link's. */
    override suspend fun features(koin: Koin): Set<String> = setOf(ServerFeatures.SLACK)

    private fun refusal(code: String): String = "Slack refuses this bot token ($code)."
}
