// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose
import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.integrations.discord.domain.CompleteDiscordLinkUseCase
import run.ostrom.server.integrations.discord.domain.CompleteDiscordLinkUseCaseImpl
import run.ostrom.server.integrations.discord.domain.DiscordApi
import run.ostrom.server.integrations.discord.domain.DiscordApprovalChannel
import run.ostrom.server.integrations.discord.domain.DiscordApprovalMessagesRepository
import run.ostrom.server.integrations.discord.domain.DiscordApps
import run.ostrom.server.integrations.discord.domain.DiscordCallException
import run.ostrom.server.integrations.discord.domain.DiscordLinkAttemptsRepository
import run.ostrom.server.integrations.discord.domain.DiscordLinksRepository
import run.ostrom.server.integrations.discord.domain.DiscordNotificationChannel
import run.ostrom.server.integrations.discord.domain.DiscordPeople
import run.ostrom.server.integrations.discord.domain.DiscordSettings
import run.ostrom.server.integrations.discord.domain.DiscordTexts
import run.ostrom.server.integrations.discord.domain.DiscordVerifier
import run.ostrom.server.integrations.discord.domain.GetDiscordLinkUseCase
import run.ostrom.server.integrations.discord.domain.GetDiscordLinkUseCaseImpl
import run.ostrom.server.integrations.discord.domain.ReceiveDiscordInteractionUseCase
import run.ostrom.server.integrations.discord.domain.ReceiveDiscordInteractionUseCaseImpl
import run.ostrom.server.integrations.discord.domain.StartDiscordLinkUseCase
import run.ostrom.server.integrations.discord.domain.StartDiscordLinkUseCaseImpl
import run.ostrom.server.integrations.discord.domain.UnlinkDiscordUseCase
import run.ostrom.server.integrations.discord.domain.UnlinkDiscordUseCaseImpl
import run.ostrom.server.integrations.discord.infrastructure.DiscordMigrations
import run.ostrom.server.integrations.discord.infrastructure.DiscordOrgDeletionListener
import run.ostrom.server.integrations.discord.infrastructure.Ed25519DiscordVerifier
import run.ostrom.server.integrations.discord.infrastructure.HttpDiscordApi
import run.ostrom.server.integrations.discord.infrastructure.MemberDiscordPeople
import run.ostrom.server.integrations.discord.infrastructure.PostgresDiscordApprovalMessagesRepository
import run.ostrom.server.integrations.discord.infrastructure.PostgresDiscordLinkAttemptsRepository
import run.ostrom.server.integrations.discord.infrastructure.PostgresDiscordLinksRepository
import run.ostrom.server.integrations.discord.presentation.DiscordRoutesDependencies
import run.ostrom.server.integrations.discord.presentation.discordOperations
import run.ostrom.server.integrations.discord.presentation.discordRoutes
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
 * The integrations-discord module: the instance's Discord application (Admin → Settings → `discord`: its bot token and
 * client secret, sealed, its application id, public key and server id), each member's link to their Discord account
 * (`/api/v1/discord/link`, by Discord's OAuth2 `identify` in the member's browser, the account a member of the
 * instance's server), the `discord` notification channel (one direct message per notification, through the
 * notifications' queue and behind the member's switches), and the yes requests in Discord: Approve opens the console's
 * page of the request, Reject is answered at `/webhooks/discord` (the application's interactions endpoint URL), and
 * the messages lose their buttons once the gate is decided. `discord.v1` is listed whenever this module is in the
 * server; `DiscordLink.configured` says whether the application is. The application's OAuth2 redirects must hold
 * `<OSTROM_PUBLIC_URL>/api/v1/discord/link/callback`.
 */
object DiscordIntegrationModule : ServerModule {
    // Declared before koinModule: the object initializes in order, and the module lambda runs at once.
    private val HTTP = named("discord-http")
    private const val HTTP_TIMEOUT_MS = 10_000L
    private const val CONSOLE_URL = "OSTROM_CONSOLE_URL"
    private const val PUBLIC_URL = "OSTROM_PUBLIC_URL"
    private const val DEFAULT_PUBLIC_URL = "http://localhost:8080"
    private const val CALLBACK = "/api/v1/discord/link/callback"
    private val REDIRECT = named("discord-redirect")
    private val CONSOLE = named("discord-console")

    override val name: String = "integrations-discord"

    override val apiOperations: ApiCatalog.() -> Unit = { discordOperations() }

    override val koinModule: Module = module {
        single(HTTP) {
            outboundHttpClient {
                install(HttpTimeout) {
                    connectTimeoutMillis = HTTP_TIMEOUT_MS
                    requestTimeoutMillis = HTTP_TIMEOUT_MS
                }
            }
        } onClose { it?.close() }
        single<DiscordApi> { HttpDiscordApi(get<HttpClient>(HTTP)) }
        // Admin → Settings → discord, served and sealed by the admin module; a bot token is checked with Discord.
        single<SettingsSection>(named(DiscordSettings.NAME)) {
            val scope = this
            DiscordSettings.section { token -> verify(scope.get<DiscordApi>(), token) }
        }
        single(REDIRECT) { redirectUri(get()) }
        single(CONSOLE) { DiscordTexts.consoleUrl(get<ConfigSource>()[CONSOLE_URL]) }
        single { DiscordApps(get()) }
        single<DiscordVerifier> { Ed25519DiscordVerifier() }
        single<DiscordLinksRepository> { PostgresDiscordLinksRepository(get()) }
        single<DiscordLinkAttemptsRepository> { PostgresDiscordLinkAttemptsRepository(get()) }
        single<DiscordApprovalMessagesRepository> { PostgresDiscordApprovalMessagesRepository(get()) }
        single<DiscordPeople> {
            val members = get<MembersRepository>()
            val users = get<UsersRepository>()
            val orgs = get<OrgsRepository>()
            MemberDiscordPeople(members::findById, users::get, orgs::get)
        }
        single { DiscordTexts(get(CONSOLE)) }
        single<GetDiscordLinkUseCase> { GetDiscordLinkUseCaseImpl(get(), get()) }
        single<StartDiscordLinkUseCase> { StartDiscordLinkUseCaseImpl(get(), get(), get(REDIRECT), get()) }
        single<CompleteDiscordLinkUseCase> {
            CompleteDiscordLinkUseCaseImpl(get(), get(), get(), get(), get(), get(), get(REDIRECT), get())
        }
        single<UnlinkDiscordUseCase> { UnlinkDiscordUseCaseImpl(get()) }
        single<ReceiveDiscordInteractionUseCase> {
            ReceiveDiscordInteractionUseCaseImpl(get(), get(), get(), get(), get(), get(), get())
        }
        single { DiscordRoutesDependencies(get(), get(), get(), get(), get(), get(), get(CONSOLE)) }
        // The notifications' channel and the approvals' way (its answers close the messages through the queue).
        single<NotificationChannel>(named(DiscordSettings.NAME)) {
            DiscordNotificationChannel(get(), get(), get(), get())
        }
        single<ApprovalChannel>(named(DiscordSettings.NAME)) {
            DiscordApprovalChannel(get(), get(), get(), get(), get())
        }
        single<OrgDeletionListener>(named("integrations-discord")) { DiscordOrgDeletionListener(get(), get(), get()) }
    }

    override val migrations: List<SchemaMigration> = DiscordMigrations

    override fun routes(route: Route, koin: Koin) {
        route.discordRoutes(koin.get())
    }

    /** `discord.v1`: the link routes and the webhook are served; the link says whether Discord is configured. */
    override suspend fun features(koin: Koin): Set<String> = setOf(ServerFeatures.DISCORD)

    /**
     * @param config The server's configuration.
     *
     * @return The link's callback, `<OSTROM_PUBLIC_URL>/api/v1/discord/link/callback` (a development server's when
     *   unset), which the application's OAuth2 redirects must hold.
     */
    internal fun redirectUri(config: ConfigSource): String =
        (config[PUBLIC_URL] ?: DEFAULT_PUBLIC_URL).trim().trimEnd('/') + CALLBACK

    /**
     * @param api Discord.
     * @param token A bot token.
     *
     * @return Why it is refused, or null; Discord down is said as such, not as a refused token.
     */
    internal suspend fun verify(api: DiscordApi, token: String): String? = try {
        api.checkBot(token)?.let { "Discord refuses this bot token ($it)." }
    } catch (_: DiscordCallException) {
        "Discord does not answer; save again in a moment."
    }
}
