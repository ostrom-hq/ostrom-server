// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.AnswerDeviceAuthorizationUseCase
import dev.ostrom.server.identity.domain.AnswerDeviceAuthorizationUseCaseImpl
import dev.ostrom.server.identity.domain.ApiTokensRepository
import dev.ostrom.server.identity.domain.AuditLog
import dev.ostrom.server.identity.domain.AuditLogImpl
import dev.ostrom.server.identity.domain.AuditRepository
import dev.ostrom.server.identity.domain.CreatePersonalTokenUseCase
import dev.ostrom.server.identity.domain.CreatePersonalTokenUseCaseImpl
import dev.ostrom.server.identity.domain.CredentialAuthenticatorImpl
import dev.ostrom.server.identity.domain.DeviceAuthorizationsRepository
import dev.ostrom.server.identity.domain.EndWebSessionUseCase
import dev.ostrom.server.identity.domain.EndWebSessionUseCaseImpl
import dev.ostrom.server.identity.domain.GetDeviceAuthorizationUseCase
import dev.ostrom.server.identity.domain.GetDeviceAuthorizationUseCaseImpl
import dev.ostrom.server.identity.domain.GetProfileUseCase
import dev.ostrom.server.identity.domain.GetProfileUseCaseImpl
import dev.ostrom.server.identity.domain.IssueAgentTokenUseCase
import dev.ostrom.server.identity.domain.IssueAgentTokenUseCaseImpl
import dev.ostrom.server.identity.domain.ListAuditEventsUseCase
import dev.ostrom.server.identity.domain.ListAuditEventsUseCaseImpl
import dev.ostrom.server.identity.domain.ListTokensUseCase
import dev.ostrom.server.identity.domain.ListTokensUseCaseImpl
import dev.ostrom.server.identity.domain.PollDeviceAuthorizationUseCase
import dev.ostrom.server.identity.domain.PollDeviceAuthorizationUseCaseImpl
import dev.ostrom.server.identity.domain.RateLimiter
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
import dev.ostrom.server.identity.domain.StartDeviceAuthorizationUseCase
import dev.ostrom.server.identity.domain.StartDeviceAuthorizationUseCaseImpl
import dev.ostrom.server.identity.domain.StartWebSessionUseCase
import dev.ostrom.server.identity.domain.StartWebSessionUseCaseImpl
import dev.ostrom.server.identity.domain.UsersRepository
import dev.ostrom.server.identity.domain.WebSessionsRepository
import dev.ostrom.server.identity.infrastructure.ApiTokensRepositoryImpl
import dev.ostrom.server.identity.infrastructure.AuditRepositoryImpl
import dev.ostrom.server.identity.infrastructure.DeviceAuthorizationsRepositoryImpl
import dev.ostrom.server.identity.infrastructure.IdentityMigrations
import dev.ostrom.server.identity.infrastructure.InMemoryRateLimiter
import dev.ostrom.server.identity.infrastructure.SecureSecretGenerator
import dev.ostrom.server.identity.infrastructure.Sha256SecretHasher
import dev.ostrom.server.identity.infrastructure.UsersRepositoryImpl
import dev.ostrom.server.identity.infrastructure.WebSessionsRepositoryImpl
import dev.ostrom.server.identity.presentation.IdentityConfig
import dev.ostrom.server.identity.presentation.IdentityRoutesDependencies
import dev.ostrom.server.identity.presentation.identityRoutes
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The identity module: users and their sign-in identities, browser sessions, API, CLI and agent tokens, the
 * `ostrom login` device flow, the signed audit log, and the server's [TokenAuthenticator].
 *
 * Other modules use it through the interfaces it binds: [AuditLog] to record a sensitive action,
 * [ResolveUserUseCase] and [StartWebSessionUseCase] to sign someone in (SSO), [IssueAgentTokenUseCase] and
 * [RevokeAgentTokensUseCase] for agent sessions.
 */
object IdentityModule : ServerModule {
    override val name: String = "identity"

    override val koinModule: Module = module {
        single { IdentityConfig.from(get()) }
        single<SecretGenerator> { SecureSecretGenerator() }
        single<SecretHasher> { Sha256SecretHasher() }
        single<RateLimiter> { InMemoryRateLimiter(get()) }

        single<UsersRepository> { UsersRepositoryImpl(get()) }
        single<WebSessionsRepository> { WebSessionsRepositoryImpl(get()) }
        single<ApiTokensRepository> { ApiTokensRepositoryImpl(get()) }
        single<DeviceAuthorizationsRepository> { DeviceAuthorizationsRepositoryImpl(get()) }
        single<AuditRepository> { AuditRepositoryImpl(get()) }

        single<AuditLog> { AuditLogImpl(get(), get(), get(), get()) }
        single<TokenAuthenticator> { CredentialAuthenticatorImpl(get(), get(), get(), get()) }
        single<ResolveUserUseCase> { ResolveUserUseCaseImpl(get(), get(), get()) }
        single<GetProfileUseCase> { GetProfileUseCaseImpl(get()) }
        single<StartWebSessionUseCase> { StartWebSessionUseCaseImpl(get(), get(), get(), get()) }
        single<EndWebSessionUseCase> { EndWebSessionUseCaseImpl(get(), get(), get()) }
        single<ListAuditEventsUseCase> { ListAuditEventsUseCaseImpl(get()) }
        single<CreatePersonalTokenUseCase> { CreatePersonalTokenUseCaseImpl(get(), get(), get(), get(), get()) }
        single<ListTokensUseCase> { ListTokensUseCaseImpl(get(), get()) }
        single<RevokeTokenUseCase> { RevokeTokenUseCaseImpl(get(), get(), get()) }
        single<RotateTokenUseCase> { RotateTokenUseCaseImpl(get(), get(), get(), get(), get()) }
        single<IssueAgentTokenUseCase> { IssueAgentTokenUseCaseImpl(get(), get(), get(), get()) }
        single<RevokeAgentTokensUseCase> { RevokeAgentTokensUseCaseImpl(get(), get()) }
        single<StartDeviceAuthorizationUseCase> { StartDeviceAuthorizationUseCaseImpl(get(), get(), get(), get(), get()) }
        single<GetDeviceAuthorizationUseCase> { GetDeviceAuthorizationUseCaseImpl(get(), get(), get()) }
        single<AnswerDeviceAuthorizationUseCase> { AnswerDeviceAuthorizationUseCaseImpl(get(), get(), get(), get()) }
        single<PollDeviceAuthorizationUseCase> { PollDeviceAuthorizationUseCaseImpl(get(), get(), get(), get(), get()) }
        single {
            IdentityRoutesDependencies(
                get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
            )
        }
    }

    override val migrations: List<SchemaMigration> = IdentityMigrations

    override fun routes(route: Route, koin: Koin) {
        route.identityRoutes(koin.get())
    }
}
