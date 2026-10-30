package dev.ostrom.server.secrets

import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.EnvironmentConfigSource
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.CreateSecretUseCase
import dev.ostrom.server.secrets.domain.CreateSecretUseCaseImpl
import dev.ostrom.server.secrets.domain.DeleteSecretUseCase
import dev.ostrom.server.secrets.domain.DeleteSecretUseCaseImpl
import dev.ostrom.server.secrets.domain.ListSecretsUseCase
import dev.ostrom.server.secrets.domain.ListSecretsUseCaseImpl
import dev.ostrom.server.secrets.domain.FindSecretUseCase
import dev.ostrom.server.secrets.domain.FindSecretUseCaseImpl
import dev.ostrom.server.secrets.domain.RevealSecretsUseCase
import dev.ostrom.server.secrets.domain.SecretDirectoryUseCase
import dev.ostrom.server.secrets.domain.SecretDirectoryUseCaseImpl
import dev.ostrom.server.secrets.domain.RevealSecretsUseCaseImpl
import dev.ostrom.server.secrets.domain.RotateSecretUseCase
import dev.ostrom.server.secrets.domain.RotateSecretUseCaseImpl
import dev.ostrom.server.secrets.domain.SecretCipher
import dev.ostrom.server.secrets.domain.SecretsRepository
import dev.ostrom.server.secrets.domain.OrgPrincipalCallerResolver
import dev.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import dev.ostrom.server.secrets.infrastructure.SecretsKey
import dev.ostrom.server.secrets.infrastructure.SecretsMigrations
import dev.ostrom.server.secrets.infrastructure.SecretsRepositoryImpl
import dev.ostrom.server.secrets.presentation.SecretsRoutesDependencies
import dev.ostrom.server.secrets.presentation.secretsRoutes
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The secrets module: the org's encrypted secrets, revealed only to server-side consumers (MCP relay, inference
 * gateway). Callers are resolved by the [CallerResolver] identity binds; without one, every call is refused.
 */
object SecretsModule : ServerModule {
    override val name: String = "secrets"

    override val koinModule: Module = module {
        single<SecretCipher> { AesGcmSecretCipher(SecretsKey.load(getOrNull<ConfigSource>() ?: EnvironmentConfigSource())) }
        single<SecretsRepository> { SecretsRepositoryImpl(get()) }
        single<CreateSecretUseCase> { CreateSecretUseCaseImpl(get(), get(), get()) }
        single<ListSecretsUseCase> { ListSecretsUseCaseImpl(get()) }
        single<RotateSecretUseCase> { RotateSecretUseCaseImpl(get(), get(), get()) }
        single<DeleteSecretUseCase> { DeleteSecretUseCaseImpl(get()) }
        single<RevealSecretsUseCase> { RevealSecretsUseCaseImpl(get(), get()) }
        single<FindSecretUseCase> { FindSecretUseCaseImpl(get()) }
        single<SecretDirectoryUseCase> { SecretDirectoryUseCaseImpl(get()) }
        single { SecretsRoutesDependencies(getOrNull<CallerResolver>() ?: OrgPrincipalCallerResolver(get()), get(), get(), get(), get(), get()) }
    }

    override val migrations: List<SchemaMigration> = SecretsMigrations

    override suspend fun initialize(koin: Koin) {
        // Loads (or creates) the key at start: a missing or malformed key stops the server now, not at the first use.
        koin.get<SecretCipher>()
    }

    override fun routes(route: Route, koin: Koin) {
        route.secretsRoutes(koin.get())
    }
}
