// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.identity

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.identity.domain.AnswerDeviceAuthorizationUseCase
import run.ostrom.server.identity.domain.AnswerDeviceAuthorizationUseCaseImpl
import run.ostrom.server.identity.domain.ApiTokensRepository
import run.ostrom.server.identity.domain.AuditLog
import run.ostrom.server.identity.domain.AuditLogGovernanceAudit
import run.ostrom.server.identity.domain.AuditLogImpl
import run.ostrom.server.identity.domain.AuditRepository
import run.ostrom.server.identity.domain.AuditVerifier
import run.ostrom.server.identity.domain.AuditVerifierImpl
import run.ostrom.server.identity.domain.CompleteMobileSignInUseCase
import run.ostrom.server.identity.domain.CompleteMobileSignInUseCaseImpl
import run.ostrom.server.identity.domain.CompleteOAuthSignInUseCase
import run.ostrom.server.identity.domain.CompleteOAuthSignInUseCaseImpl
import run.ostrom.server.identity.domain.CreatePersonalTokenUseCase
import run.ostrom.server.identity.domain.CreatePersonalTokenUseCaseImpl
import run.ostrom.server.identity.domain.CreateServiceKeyUseCase
import run.ostrom.server.identity.domain.CreateServiceKeyUseCaseImpl
import run.ostrom.server.identity.domain.CreateSignInLinkUseCase
import run.ostrom.server.identity.domain.CreateSignInLinkUseCaseImpl
import run.ostrom.server.identity.domain.CredentialAuthenticatorImpl
import run.ostrom.server.identity.domain.DeleteServiceKeyUseCase
import run.ostrom.server.identity.domain.DeleteServiceKeyUseCaseImpl
import run.ostrom.server.identity.domain.DeviceAuthorizationsRepository
import run.ostrom.server.identity.domain.ElevatedTokensRepository
import run.ostrom.server.identity.domain.EndWebSessionUseCase
import run.ostrom.server.identity.domain.EndWebSessionUseCaseImpl
import run.ostrom.server.identity.domain.ExchangeMobileCodeUseCase
import run.ostrom.server.identity.domain.ExchangeMobileCodeUseCaseImpl
import run.ostrom.server.identity.domain.ExchangeSignInLinkUseCase
import run.ostrom.server.identity.domain.ExchangeSignInLinkUseCaseImpl
import run.ostrom.server.identity.domain.GetDeviceAuthorizationUseCase
import run.ostrom.server.identity.domain.GetDeviceAuthorizationUseCaseImpl
import run.ostrom.server.identity.domain.GetNotificationPreferencesUseCase
import run.ostrom.server.identity.domain.GetNotificationPreferencesUseCaseImpl
import run.ostrom.server.identity.domain.GetProfileUseCase
import run.ostrom.server.identity.domain.GetProfileUseCaseImpl
import run.ostrom.server.identity.domain.GitHubTokenVerifier
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.IssueAgentTokenUseCase
import run.ostrom.server.identity.domain.IssueAgentTokenUseCaseImpl
import run.ostrom.server.identity.domain.IssueCliTokenUseCase
import run.ostrom.server.identity.domain.IssueCliTokenUseCaseImpl
import run.ostrom.server.identity.domain.LauncherLoginUseCase
import run.ostrom.server.identity.domain.LauncherLoginUseCaseImpl
import run.ostrom.server.identity.domain.LinkIdentityUseCase
import run.ostrom.server.identity.domain.LinkIdentityUseCaseImpl
import run.ostrom.server.identity.domain.ListAuditEventsUseCase
import run.ostrom.server.identity.domain.ListAuditEventsUseCaseImpl
import run.ostrom.server.identity.domain.ListDevicesUseCase
import run.ostrom.server.identity.domain.ListDevicesUseCaseImpl
import run.ostrom.server.identity.domain.ListOrgAuditUseCase
import run.ostrom.server.identity.domain.ListOrgAuditUseCaseImpl
import run.ostrom.server.identity.domain.ListServiceKeysUseCase
import run.ostrom.server.identity.domain.ListServiceKeysUseCaseImpl
import run.ostrom.server.identity.domain.ListTokensUseCase
import run.ostrom.server.identity.domain.ListTokensUseCaseImpl
import run.ostrom.server.identity.domain.MagicLinksRepository
import run.ostrom.server.identity.domain.MailSender
import run.ostrom.server.identity.domain.MobileCodesRepository
import run.ostrom.server.identity.domain.MobileTokensRepository
import run.ostrom.server.identity.domain.NotificationPreferencesRepository
import run.ostrom.server.identity.domain.OAuthAttemptsRepository
import run.ostrom.server.identity.domain.OAuthProvider
import run.ostrom.server.identity.domain.PollDeviceAuthorizationUseCase
import run.ostrom.server.identity.domain.PollDeviceAuthorizationUseCaseImpl
import run.ostrom.server.identity.domain.RateLimiter
import run.ostrom.server.identity.domain.RequestMagicLinkUseCase
import run.ostrom.server.identity.domain.RequestMagicLinkUseCaseImpl
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.ResolveUserUseCaseImpl
import run.ostrom.server.identity.domain.RevokeAgentTokensUseCase
import run.ostrom.server.identity.domain.RevokeAgentTokensUseCaseImpl
import run.ostrom.server.identity.domain.RevokeTokenUseCase
import run.ostrom.server.identity.domain.RevokeTokenUseCaseImpl
import run.ostrom.server.identity.domain.RotateTokenUseCase
import run.ostrom.server.identity.domain.RotateTokenUseCaseImpl
import run.ostrom.server.identity.domain.SecretGenerator
import run.ostrom.server.identity.domain.SecretHasher
import run.ostrom.server.identity.domain.ServiceKeyAuditImpl
import run.ostrom.server.identity.domain.ServiceKeysRepository
import run.ostrom.server.identity.domain.SetNotificationPreferencesUseCase
import run.ostrom.server.identity.domain.SetNotificationPreferencesUseCaseImpl
import run.ostrom.server.identity.domain.SignInLinksRepository
import run.ostrom.server.identity.domain.SignInMethods
import run.ostrom.server.identity.domain.SignInServices
import run.ostrom.server.identity.domain.SignOutDeviceUseCase
import run.ostrom.server.identity.domain.SignOutDeviceUseCaseImpl
import run.ostrom.server.identity.domain.StartDeviceAuthorizationUseCase
import run.ostrom.server.identity.domain.StartDeviceAuthorizationUseCaseImpl
import run.ostrom.server.identity.domain.StartOAuthSignInUseCase
import run.ostrom.server.identity.domain.StartOAuthSignInUseCaseImpl
import run.ostrom.server.identity.domain.StartWebSessionUseCase
import run.ostrom.server.identity.domain.StartWebSessionUseCaseImpl
import run.ostrom.server.identity.domain.UnlockMobileAppUseCase
import run.ostrom.server.identity.domain.UnlockMobileAppUseCaseImpl
import run.ostrom.server.identity.domain.LinkedIdentities
import run.ostrom.server.identity.domain.UsersRepository
import run.ostrom.server.identity.domain.VerifyMagicLinkUseCase
import run.ostrom.server.identity.domain.VerifyMagicLinkUseCaseImpl
import run.ostrom.server.identity.domain.WebSessionsOfUserUseCase
import run.ostrom.server.identity.domain.WebSessionsOfUserUseCaseImpl
import run.ostrom.server.identity.domain.WebSessionsRepository
import run.ostrom.server.identity.infrastructure.ApiTokensRepositoryImpl
import run.ostrom.server.identity.infrastructure.AuditRepositoryImpl
import run.ostrom.server.identity.infrastructure.DeviceAuthorizationsRepositoryImpl
import run.ostrom.server.identity.infrastructure.DirectoryMailSender
import run.ostrom.server.identity.infrastructure.ElevatedTokensRepositoryImpl
import run.ostrom.server.identity.infrastructure.GitHubOAuthProvider
import run.ostrom.server.identity.infrastructure.GitHubTokenVerifierImpl
import run.ostrom.server.identity.infrastructure.IdentityMigrations
import run.ostrom.server.identity.infrastructure.IdentityOrgAuditTrail
import run.ostrom.server.identity.infrastructure.InMemoryRateLimiter
import run.ostrom.server.identity.infrastructure.MagicLinksRepositoryImpl
import run.ostrom.server.identity.infrastructure.MobileCodesRepositoryImpl
import run.ostrom.server.identity.infrastructure.MobileTokensRepositoryImpl
import run.ostrom.server.identity.infrastructure.NotificationPreferencesRepositoryImpl
import run.ostrom.server.identity.infrastructure.OAuthAttemptsRepositoryImpl
import run.ostrom.server.identity.infrastructure.OidcEndpoints
import run.ostrom.server.identity.infrastructure.OidcProvider
import run.ostrom.server.identity.infrastructure.PhoneUnlockProofsImpl
import run.ostrom.server.identity.infrastructure.ResendMailSender
import run.ostrom.server.identity.infrastructure.SecureSecretGenerator
import run.ostrom.server.identity.infrastructure.ServiceKeysRepositoryImpl
import run.ostrom.server.identity.infrastructure.Sha256SecretHasher
import run.ostrom.server.identity.infrastructure.SignInLinksRepositoryImpl
import run.ostrom.server.identity.infrastructure.TransactionalAuditLog
import run.ostrom.server.identity.infrastructure.UnconfiguredMailSender
import run.ostrom.server.identity.infrastructure.LinkedIdentitiesImpl
import run.ostrom.server.identity.infrastructure.UsersRepositoryImpl
import run.ostrom.server.identity.infrastructure.WebSessionsRepositoryImpl
import run.ostrom.server.identity.infrastructure.closeHttpClient
import run.ostrom.server.identity.infrastructure.magicLinkMail
import run.ostrom.server.identity.presentation.IdentityConfig
import run.ostrom.server.identity.presentation.IdentityRoutesDependencies
import run.ostrom.server.identity.presentation.KeysAndDevicesRoutesDependencies
import run.ostrom.server.identity.presentation.MobileRoutesDependencies
import run.ostrom.server.identity.presentation.OrgAuditRoutesDependencies
import run.ostrom.server.identity.presentation.SessionRoutesDependencies
import run.ostrom.server.identity.presentation.SignInConfig
import run.ostrom.server.identity.presentation.SignInRoutesDependencies
import run.ostrom.server.identity.presentation.identityRoutes
import run.ostrom.server.identity.presentation.keysAndDevicesRoutes
import run.ostrom.server.identity.presentation.mobileRoutes
import run.ostrom.server.identity.presentation.noStoreOnIdentityRoutes
import run.ostrom.server.identity.presentation.orgAuditRoutes
import run.ostrom.server.identity.presentation.sessionRoutes
import run.ostrom.server.identity.presentation.signInRoutes
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.GovernanceAudit
import run.ostrom.server.kernel.auth.PhoneUnlockProofs
import run.ostrom.server.kernel.auth.ServiceKeyAudit
import run.ostrom.server.kernel.auth.SignOutListener
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.module.ServerModule
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.routing.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
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
        single<LinkedIdentities> { LinkedIdentitiesImpl(get()) }
        single<WebSessionsRepository> { WebSessionsRepositoryImpl(get()) }
        single<ApiTokensRepository> { ApiTokensRepositoryImpl(get(), get()) }
        single<ServiceKeysRepository> { ServiceKeysRepositoryImpl(get()) }
        single<ElevatedTokensRepository> { ElevatedTokensRepositoryImpl(get()) }
        single<DeviceAuthorizationsRepository> { DeviceAuthorizationsRepositoryImpl(get()) }
        single<AuditRepository> { AuditRepositoryImpl(get()) }

        single<AuditLog> { AuditLogImpl(get(), get(), get(), get()) }
        single { TransactionalAuditLog(get()) }
        single<ServiceKeyAudit> { ServiceKeyAuditImpl(get()) }
        single<GovernanceAudit> { AuditLogGovernanceAudit(get()) }
        single<OrgAuditTrail> { IdentityOrgAuditTrail(get()) }
        single<AuditVerifier> { AuditVerifierImpl(get(), get()) }
        single<ListOrgAuditUseCase> { ListOrgAuditUseCaseImpl(get(), get(), get()) }
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
        single<ListDevicesUseCase> { ListDevicesUseCaseImpl(get(), get(), get()) }
        single<SignOutDeviceUseCase> { SignOutDeviceUseCaseImpl(get(), get(), get(), get()) }
        single { KeysAndDevicesRoutesDependencies(get(), get(), get(), get(), get(), get()) }
        single<StartDeviceAuthorizationUseCase> {
            StartDeviceAuthorizationUseCaseImpl(get(), get(), get(), get(), get())
        }
        single<GetDeviceAuthorizationUseCase> { GetDeviceAuthorizationUseCaseImpl(get(), get(), get()) }
        single<AnswerDeviceAuthorizationUseCase> { AnswerDeviceAuthorizationUseCaseImpl(get(), get(), get(), get()) }
        single<PollDeviceAuthorizationUseCase> {
            PollDeviceAuthorizationUseCaseImpl(get(), get(), get(), get(), get(), get())
        }
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
        single<PhoneUnlockProofs> { PhoneUnlockProofsImpl(get()) }
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

    /** How long an agent token is kept once expired: it no longer acts, and nothing reads it after its launch. */
    val AGENT_TOKEN_RETENTION: Duration = 7.days

    // Every hour, the agent tokens expired more than AGENT_TOKEN_RETENTION ago are deleted: one per launch, they were
    // kept for ever. A failed purge is logged and tried again the next hour; it never stops the server.
    override fun start(scope: CoroutineScope, koin: Koin) {
        val tokens = koin.get<ApiTokensRepository>()
        val clock = koin.get<Clock>()
        val logger = koin.get<Logger>()
        scope.launch {
            while (true) {
                try {
                    tokens.purgeAgentTokens(clock.now() - AGENT_TOKEN_RETENTION)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    logger.warn("agent token purge failed", mapOf("error" to error::class.simpleName))
                }
                delay(1.hours)
            }
        }
    }

    override fun routes(route: Route, koin: Koin) {
        route.noStoreOnIdentityRoutes()
        route.identityRoutes(koin.get())
        route.signInRoutes(koin.get())
        route.sessionRoutes(koin.get())
        route.mobileRoutes(koin.get())
        route.orgAuditRoutes(koin.get(), koin.get())
        route.keysAndDevicesRoutes(koin.get())
    }

    /** The audit log (`/api/v1/audit`, `/api/v1/auth/me/audit`) and the wide catalogue of service-key scopes. */
    override suspend fun features(koin: Koin): Set<String> =
        setOf(ServerFeatures.AUDIT, ServerFeatures.SERVICE_KEY_SCOPES)

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
