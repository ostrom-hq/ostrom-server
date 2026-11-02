// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.AnswerDeviceAuthorizationUseCase
import dev.ostrom.server.identity.domain.AnswerDeviceAuthorizationUseCaseImpl
import dev.ostrom.server.identity.domain.ApiTokensRepository
import dev.ostrom.server.identity.domain.AuditLog
import dev.ostrom.server.identity.domain.AuditLogGovernanceAudit
import dev.ostrom.server.identity.domain.AuditLogImpl
import dev.ostrom.server.identity.domain.AuditRepository
import dev.ostrom.server.identity.domain.CompleteMobileSignInUseCase
import dev.ostrom.server.identity.domain.CompleteMobileSignInUseCaseImpl
import dev.ostrom.server.identity.domain.CompleteOAuthSignInUseCase
import dev.ostrom.server.identity.domain.CompleteOAuthSignInUseCaseImpl
import dev.ostrom.server.identity.domain.CreatePersonalTokenUseCase
import dev.ostrom.server.identity.domain.CreatePersonalTokenUseCaseImpl
import dev.ostrom.server.identity.domain.CreateServiceKeyUseCase
import dev.ostrom.server.identity.domain.CreateServiceKeyUseCaseImpl
import dev.ostrom.server.identity.domain.CreateSignInLinkUseCase
import dev.ostrom.server.identity.domain.CreateSignInLinkUseCaseImpl
import dev.ostrom.server.identity.domain.CredentialAuthenticatorImpl
import dev.ostrom.server.identity.domain.DeleteServiceKeyUseCase
import dev.ostrom.server.identity.domain.DeleteServiceKeyUseCaseImpl
import dev.ostrom.server.identity.domain.DeviceAuthorizationsRepository
import dev.ostrom.server.identity.domain.EndWebSessionUseCase
import dev.ostrom.server.identity.domain.EndWebSessionUseCaseImpl
import dev.ostrom.server.identity.domain.ExchangeMobileCodeUseCase
import dev.ostrom.server.identity.domain.ExchangeMobileCodeUseCaseImpl
import dev.ostrom.server.identity.domain.ExchangeSignInLinkUseCase
import dev.ostrom.server.identity.domain.ExchangeSignInLinkUseCaseImpl
import dev.ostrom.server.identity.domain.GetDeviceAuthorizationUseCase
import dev.ostrom.server.identity.domain.GetDeviceAuthorizationUseCaseImpl
import dev.ostrom.server.identity.domain.GetNotificationPreferencesUseCase
import dev.ostrom.server.identity.domain.GetNotificationPreferencesUseCaseImpl
import dev.ostrom.server.identity.domain.GetProfileUseCase
import dev.ostrom.server.identity.domain.GetProfileUseCaseImpl
import dev.ostrom.server.identity.domain.GitHubTokenVerifier
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.IssueAgentTokenUseCase
import dev.ostrom.server.identity.domain.IssueAgentTokenUseCaseImpl
import dev.ostrom.server.identity.domain.IssueCliTokenUseCase
import dev.ostrom.server.identity.domain.IssueCliTokenUseCaseImpl
import dev.ostrom.server.identity.domain.LauncherLoginUseCase
import dev.ostrom.server.identity.domain.LauncherLoginUseCaseImpl
import dev.ostrom.server.identity.domain.LinkIdentityUseCase
import dev.ostrom.server.identity.domain.LinkIdentityUseCaseImpl
import dev.ostrom.server.identity.domain.ListAuditEventsUseCase
import dev.ostrom.server.identity.domain.ListAuditEventsUseCaseImpl
import dev.ostrom.server.identity.domain.ListDevicesUseCase
import dev.ostrom.server.identity.domain.ListDevicesUseCaseImpl
import dev.ostrom.server.identity.domain.ListOrgAuditUseCase
import dev.ostrom.server.identity.domain.ListOrgAuditUseCaseImpl
import dev.ostrom.server.identity.domain.ListServiceKeysUseCase
import dev.ostrom.server.identity.domain.ListServiceKeysUseCaseImpl
import dev.ostrom.server.identity.domain.ListTokensUseCase
import dev.ostrom.server.identity.domain.ListTokensUseCaseImpl
import dev.ostrom.server.identity.domain.MagicLinksRepository
import dev.ostrom.server.identity.domain.MailSender
import dev.ostrom.server.identity.domain.MobileCodesRepository
import dev.ostrom.server.identity.domain.MobileTokensRepository
import dev.ostrom.server.identity.domain.NotificationPreferencesRepository
import dev.ostrom.server.identity.domain.OAuthAttemptsRepository
import dev.ostrom.server.identity.domain.OAuthProvider
import dev.ostrom.server.identity.domain.PollDeviceAuthorizationUseCase
import dev.ostrom.server.identity.domain.PollDeviceAuthorizationUseCaseImpl
import dev.ostrom.server.identity.domain.RateLimiter
import dev.ostrom.server.identity.domain.RequestMagicLinkUseCase
import dev.ostrom.server.identity.domain.RequestMagicLinkUseCaseImpl
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.ResolveUserUseCaseImpl
import dev.ostrom.server.identity.domain.RevokeAgentTokensUseCase
import dev.ostrom.server.identity.domain.RevokeAgentTokensUseCaseImpl
import dev.ostrom.server.identity.domain.RevokeTokenUseCase
import dev.ostrom.server.identity.domain.RevokeTokenUseCaseImpl
import dev.ostrom.server.identity.domain.RotateTokenUseCase
import dev.ostrom.server.identity.domain.RotateTokenUseCaseImpl
import dev.ostrom.server.identity.domain.SecretGenerator
import dev.ostrom.server.identity.domain.SecretHasher
import dev.ostrom.server.identity.domain.ServiceKeysRepository
import dev.ostrom.server.identity.domain.SetNotificationPreferencesUseCase
import dev.ostrom.server.identity.domain.SetNotificationPreferencesUseCaseImpl
import dev.ostrom.server.identity.domain.SignInLinksRepository
import dev.ostrom.server.identity.domain.SignInMethods
import dev.ostrom.server.identity.domain.SignInServices
import dev.ostrom.server.identity.domain.SignOutDeviceUseCase
import dev.ostrom.server.identity.domain.SignOutDeviceUseCaseImpl
import dev.ostrom.server.identity.domain.StartDeviceAuthorizationUseCase
import dev.ostrom.server.identity.domain.StartDeviceAuthorizationUseCaseImpl
import dev.ostrom.server.identity.domain.StartOAuthSignInUseCase
import dev.ostrom.server.identity.domain.StartOAuthSignInUseCaseImpl
import dev.ostrom.server.identity.domain.StartWebSessionUseCase
import dev.ostrom.server.identity.domain.StartWebSessionUseCaseImpl
import dev.ostrom.server.identity.domain.UnlockMobileAppUseCase
import dev.ostrom.server.identity.domain.UnlockMobileAppUseCaseImpl
import dev.ostrom.server.identity.domain.UsersRepository
import dev.ostrom.server.identity.domain.VerifyMagicLinkUseCase
import dev.ostrom.server.identity.domain.VerifyMagicLinkUseCaseImpl
import dev.ostrom.server.identity.domain.WebSessionsOfUserUseCase
import dev.ostrom.server.identity.domain.WebSessionsOfUserUseCaseImpl
import dev.ostrom.server.identity.domain.WebSessionsRepository
import dev.ostrom.server.identity.infrastructure.ApiTokensRepositoryImpl
import dev.ostrom.server.identity.infrastructure.AuditRepositoryImpl
import dev.ostrom.server.identity.infrastructure.DeviceAuthorizationsRepositoryImpl
import dev.ostrom.server.identity.infrastructure.DirectoryMailSender
import dev.ostrom.server.identity.infrastructure.GitHubOAuthProvider
import dev.ostrom.server.identity.infrastructure.GitHubTokenVerifierImpl
import dev.ostrom.server.identity.infrastructure.IdentityMigrations
import dev.ostrom.server.identity.infrastructure.IdentityOrgAuditTrail
import dev.ostrom.server.identity.infrastructure.InMemoryRateLimiter
import dev.ostrom.server.identity.infrastructure.MagicLinksRepositoryImpl
import dev.ostrom.server.identity.infrastructure.MobileCodesRepositoryImpl
import dev.ostrom.server.identity.infrastructure.MobileTokensRepositoryImpl
import dev.ostrom.server.identity.infrastructure.NotificationPreferencesRepositoryImpl
import dev.ostrom.server.identity.infrastructure.OAuthAttemptsRepositoryImpl
import dev.ostrom.server.identity.infrastructure.OidcEndpoints
import dev.ostrom.server.identity.infrastructure.OidcProvider
import dev.ostrom.server.identity.infrastructure.ResendMailSender
import dev.ostrom.server.identity.infrastructure.SecureSecretGenerator
import dev.ostrom.server.identity.infrastructure.ServiceKeysRepositoryImpl
import dev.ostrom.server.identity.infrastructure.Sha256SecretHasher
import dev.ostrom.server.identity.infrastructure.SignInLinksRepositoryImpl
import dev.ostrom.server.identity.infrastructure.UnconfiguredMailSender
import dev.ostrom.server.identity.infrastructure.UsersRepositoryImpl
import dev.ostrom.server.identity.infrastructure.WebSessionsRepositoryImpl
import dev.ostrom.server.identity.infrastructure.closeHttpClient
import dev.ostrom.server.identity.infrastructure.magicLinkMail
import dev.ostrom.server.identity.presentation.IdentityConfig
import dev.ostrom.server.identity.presentation.IdentityRoutesDependencies
import dev.ostrom.server.identity.presentation.KeysAndDevicesRoutesDependencies
import dev.ostrom.server.identity.presentation.MobileRoutesDependencies
import dev.ostrom.server.identity.presentation.OrgAuditRoutesDependencies
import dev.ostrom.server.identity.presentation.SessionRoutesDependencies
import dev.ostrom.server.identity.presentation.SignInConfig
import dev.ostrom.server.identity.presentation.SignInRoutesDependencies
import dev.ostrom.server.identity.presentation.identityRoutes
import dev.ostrom.server.identity.presentation.keysAndDevicesRoutes
import dev.ostrom.server.identity.presentation.mobileRoutes
import dev.ostrom.server.identity.presentation.noStoreOnIdentityRoutes
import dev.ostrom.server.identity.presentation.orgAuditRoutes
import dev.ostrom.server.identity.presentation.sessionRoutes
import dev.ostrom.server.identity.presentation.signInRoutes
import dev.ostrom.server.kernel.audit.OrgAuditTrail
import dev.ostrom.server.kernel.auth.GovernanceAudit
import dev.ostrom.server.kernel.auth.SignOutListener
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.routing.Route
import kotlin.time.Clock
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

/**
 * The identity module: users and their sign-in identities, browser sessions, API, CLI and agent tokens, the
 * `ostrom login` device flow, the signed audit log, and the server's [TokenAuthenticator].
 *
 * Other modules use it through the interfaces it binds: [AuditLog] to record a sensitive action,
 * [ResolveUserUseCase] and [StartWebSessionUseCase] to sign someone in (SSO), [IssueAgentTokenUseCase] and
 * [RevokeAgentTokensUseCase] for agent sessions.
 */
object IdentityModule : ServerModule {
    // Declared before koinModule: the object initializes in order, and the module lambda runs at once.
    private val HTTP = named("identity-http")
    private const val HTTP_TIMEOUT_MS = 10_000L

    override val name: String = "identity"

    override val koinModule: Module = module {
        single { IdentityConfig.from(get()) }
        single<SecretGenerator> { SecureSecretGenerator() }
        single<SecretHasher> { Sha256SecretHasher() }
        single<RateLimiter> { InMemoryRateLimiter(get()) }

        single<UsersRepository> { UsersRepositoryImpl(get()) }
        single<WebSessionsRepository> { WebSessionsRepositoryImpl(get()) }
        single<ApiTokensRepository> { ApiTokensRepositoryImpl(get()) }
        single<ServiceKeysRepository> { ServiceKeysRepositoryImpl(get()) }
        single<DeviceAuthorizationsRepository> { DeviceAuthorizationsRepositoryImpl(get()) }
        single<AuditRepository> { AuditRepositoryImpl(get()) }

        single<AuditLog> { AuditLogImpl(get(), get(), get(), get()) }
        single<GovernanceAudit> { AuditLogGovernanceAudit(get()) }
        single<OrgAuditTrail> { IdentityOrgAuditTrail(get()) }
        single<ListOrgAuditUseCase> { ListOrgAuditUseCaseImpl(get(), get()) }
        single { OrgAuditRoutesDependencies(get(), get()) }
        single<TokenAuthenticator> {
            val config = get<IdentityConfig>()
            CredentialAuthenticatorImpl(get(), get(), get(), get(), config.allowedOrigins, config.sessionCookiePrefix)
        }
        single<ResolveUserUseCase> { ResolveUserUseCaseImpl(get(), get(), get()) }
        single<LinkIdentityUseCase> { LinkIdentityUseCaseImpl(get(), get(), get()) }
        single<GetProfileUseCase> { GetProfileUseCaseImpl(get()) }
        single<StartWebSessionUseCase> { StartWebSessionUseCaseImpl(get(), get(), get(), get()) }
        single<EndWebSessionUseCase> { EndWebSessionUseCaseImpl(get(), get(), get()) }
        single<WebSessionsOfUserUseCase> { WebSessionsOfUserUseCaseImpl(get(), get(), get()) }
        single<ListAuditEventsUseCase> { ListAuditEventsUseCaseImpl(get()) }
        single<CreatePersonalTokenUseCase> { CreatePersonalTokenUseCaseImpl(get(), get(), get(), get(), get()) }
        single<ListTokensUseCase> { ListTokensUseCaseImpl(get(), get()) }
        single<RevokeTokenUseCase> { RevokeTokenUseCaseImpl(get(), get(), get()) }
        single<RotateTokenUseCase> { RotateTokenUseCaseImpl(get(), get(), get(), get(), get()) }
        single<IssueAgentTokenUseCase> { IssueAgentTokenUseCaseImpl(get(), get(), get(), get()) }
        single<RevokeAgentTokensUseCase> { RevokeAgentTokensUseCaseImpl(get(), get()) }
        single<CreateServiceKeyUseCase> { CreateServiceKeyUseCaseImpl(get(), get(), get(), get(), get(), get()) }
        single<ListServiceKeysUseCase> { ListServiceKeysUseCaseImpl(get(), get()) }
        single<DeleteServiceKeyUseCase> { DeleteServiceKeyUseCaseImpl(get(), get(), get()) }
        single<ListDevicesUseCase> { ListDevicesUseCaseImpl(get(), get()) }
        single<SignOutDeviceUseCase> { SignOutDeviceUseCaseImpl(get(), get(), get()) }
        single { KeysAndDevicesRoutesDependencies(get(), get(), get(), get(), get(), get()) }
        single<StartDeviceAuthorizationUseCase> {
            StartDeviceAuthorizationUseCaseImpl(get(), get(), get(), get(), get())
        }
        single<GetDeviceAuthorizationUseCase> { GetDeviceAuthorizationUseCaseImpl(get(), get(), get()) }
        single<AnswerDeviceAuthorizationUseCase> { AnswerDeviceAuthorizationUseCaseImpl(get(), get(), get(), get()) }
        single<PollDeviceAuthorizationUseCase> { PollDeviceAuthorizationUseCaseImpl(get(), get(), get(), get(), get()) }
        single { SignInConfig.from(get()) }
        single(HTTP) {
            HttpClient {
                expectSuccess = false
                install(HttpTimeout) {
                    connectTimeoutMillis = HTTP_TIMEOUT_MS
                    requestTimeoutMillis = HTTP_TIMEOUT_MS
                }
            }
        } onClose ::closeHttpClient
        single { signInMethods(get(), get(HTTP), get()) }
        single<MailSender> { mailSender(get(), get(HTTP), get()) }
        single<OAuthAttemptsRepository> { OAuthAttemptsRepositoryImpl(get()) }
        single<MagicLinksRepository> { MagicLinksRepositoryImpl(get()) }
        single<StartOAuthSignInUseCase> { StartOAuthSignInUseCaseImpl(get(), get(), get()) }
        single { SignInServices(get(), get(), get(), get(), get<IdentityConfig>()::callbackUrl) }
        single<CompleteOAuthSignInUseCase> { CompleteOAuthSignInUseCaseImpl(get(), get(), get(), get(), get(), get()) }
        single<RequestMagicLinkUseCase> {
            val config = get<IdentityConfig>()
            RequestMagicLinkUseCaseImpl(get(), get(), get(), get(), ::magicLinkMail, config::magicLinkUrl)
        }
        single<VerifyMagicLinkUseCase> { VerifyMagicLinkUseCaseImpl(get(), get(), get(), get(), get()) }
        single<GitHubTokenVerifier> {
            val config = get<SignInConfig>()
            GitHubTokenVerifierImpl(config.github, get(HTTP), config.githubApiUrl)
        }
        single<IssueCliTokenUseCase> { IssueCliTokenUseCaseImpl(get(), get(), get(), get(), get()) }
        single<LauncherLoginUseCase> { LauncherLoginUseCaseImpl(get(), get(), get(), get()) }
        single { SignInRoutesDependencies(get(), get(), get(), get(), get(), get(), get(), get()) }
        single<MobileCodesRepository> { MobileCodesRepositoryImpl(get()) }
        single<MobileTokensRepository> { MobileTokensRepositoryImpl(get()) }
        single<NotificationPreferencesRepository> { NotificationPreferencesRepositoryImpl(get()) }
        single<CompleteMobileSignInUseCase> { CompleteMobileSignInUseCaseImpl(get(), get(), get(), get(), get()) }
        single<ExchangeMobileCodeUseCase> { ExchangeMobileCodeUseCaseImpl(get(), get(), get(), get(), get(), get()) }
        single<UnlockMobileAppUseCase> { UnlockMobileAppUseCaseImpl(get(), get(), get(), get(), get()) }
        single<GetNotificationPreferencesUseCase> { GetNotificationPreferencesUseCaseImpl(get()) }
        single<SetNotificationPreferencesUseCase> { SetNotificationPreferencesUseCaseImpl(get(), get()) }
        single { MobileRoutesDependencies(get(), get(), get(), get()) }
        single<SignInLinksRepository> { SignInLinksRepositoryImpl(get()) }
        single<CreateSignInLinkUseCase> { CreateSignInLinkUseCaseImpl(get(), get(), get(), get(), get(), get()) }
        single<ExchangeSignInLinkUseCase> { ExchangeSignInLinkUseCaseImpl(get(), get(), get(), get(), get(), get()) }
        single { SessionRoutesDependencies(get(), get(), get()) }
        single {
            IdentityRoutesDependencies(
                get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
                getAll<SignOutListener>(),
                get(),
            )
        }
    }

    override val migrations: List<SchemaMigration> = IdentityMigrations

    override fun routes(route: Route, koin: Koin) {
        route.noStoreOnIdentityRoutes()
        route.identityRoutes(koin.get())
        route.signInRoutes(koin.get())
        route.sessionRoutes(koin.get())
        route.mobileRoutes(koin.get())
        route.orgAuditRoutes(koin.get())
        route.keysAndDevicesRoutes(koin.get())
    }

    private fun signInMethods(config: SignInConfig, http: HttpClient, clock: Clock): SignInMethods {
        val oauth = buildMap<IdentityProvider, OAuthProvider> {
            config.github?.let {
                put(IdentityProvider.GITHUB, GitHubOAuthProvider(it, http, config.githubWebUrl, config.githubApiUrl))
            }
            config.google?.let {
                put(
                    IdentityProvider.GOOGLE,
                    OidcProvider(IdentityProvider.GOOGLE, config.googleEndpoints.withAccountPicker(), it, http, clock),
                )
            }
        }
        return SignInMethods(oauth, email = config.resendApiKey != null || config.mailDirectory != null)
    }

    /** Google's consent page lets the person pick an account instead of reusing the last one silently. */
    private fun OidcEndpoints.withAccountPicker() =
        copy(extraParameters = extraParameters + ("prompt" to "select_account"))

    private fun mailSender(config: SignInConfig, http: HttpClient, clock: Clock): MailSender = when {
        config.resendApiKey != null -> ResendMailSender(http, config.resendApiKey, config.mailFrom)
        config.mailDirectory != null -> DirectoryMailSender(config.mailDirectory, clock)
        else -> UnconfiguredMailSender()
    }
}
